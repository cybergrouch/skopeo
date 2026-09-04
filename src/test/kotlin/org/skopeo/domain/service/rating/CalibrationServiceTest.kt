// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.rating

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.settings.SettingsService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRatingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The calibration window (#881) — who is in it, and for how long.
 *
 * The claims worth pinning are the ones that would fail silently:
 *
 * - the window is **derived**, so changing N moves everyone at once with no sweep;
 * - the boundary is the **Nth** rated match, since an off-by-one here either suppresses an opponent's
 *   rating that should have moved or moves one that should have been suppressed;
 * - only **manual** designations open a window, and every one of them restarts it;
 * - the rollout is **prospective**, so an existing player is not silently frozen.
 */
class CalibrationServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val matches = MatchRepository()
    private val ratings = RatingAssembler()
    private val ratingService = RatingService()
    private val settings = SettingsService()
    private val service = CalibrationService()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        sex = "Male",
                        capabilities = roles,
                    ),
            ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    /** A manual designation — the trigger that opens a calibration window. */
    private fun designate(
        raterUid: String,
        userId: UUID,
        value: String = "4.0",
    ) = ratingService.setRating(token = token(uid = raterUid), userId = userId, value = value).shouldBeRight()

    /**
     * A rating row as it looks **before this feature shipped**: rated, but with no designation stamp.
     *
     * There is no live code path that produces this — every route into `setRating` is a manual
     * designation, and `applyMatchRating` only updates a rating that already exists. So the pre-feature
     * state has to be written directly, and it is worth testing precisely because it is the state every
     * existing player is in on deploy.
     */
    private fun simulatePreFeatureRating(
        userId: UUID,
        value: String = "4.0",
    ) {
        ratings.setRating(userId = userId, rating = BigDecimal(value), level = value)
        transaction {
            UserRatingsTable.update(where = { UserRatingsTable.userId eq userId }) {
                it[calibrationStartedAt] = null
            }
        }
    }

    /** Play a match between [one] and [two] and stamp it rated, which is what advances the clock. */
    private fun playRatedMatch(
        one: User,
        two: User,
    ) {
        val fixture =
            matches
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.of(2026, 1, 5),
                            team1UserIds = listOf(element = one.id),
                            team2UserIds = listOf(element = two.id),
                            team1Name = "T1",
                            team2Name = "T2",
                            createdBy = one.id,
                        ),
                ).toDomain()
        matches.addResult(
            matchId = fixture.id,
            sets =
                listOf(
                    element =
                        MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = fixture.team1.teamId),
                ),
            winnerTeamId = fixture.team1.teamId,
            recordedBy = one.id,
            completedAt = LocalDateTime.now(),
        )
        matches.markRated(matchId = fixture.id, ratedAt = LocalDateTime.now(), ratedBy = one.id)
    }

    @Test
    fun `a manual designation opens the window, and it closes on the Nth rated match (#881)`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val player = provision(uid = "player")
        val opponent = provision(uid = "opponent")
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        settings.setCalibrationMatches(token = token(uid = "admin"), matches = 3).shouldBeRight()

        designate(raterUid = "rater", userId = player.id)

        // Match 1 and 2 of 3: still calibrating. The window covers the 1st THROUGH the Nth.
        service.statusFor(userId = player.id).inCalibration.shouldBeTrue()
        playRatedMatch(one = player, two = opponent)
        withClue(clue = "after 1 of 3") {
            service.statusFor(userId = player.id).let {
                it.inCalibration.shouldBeTrue()
                it.matchesRated shouldBe 1
                it.matchesRequired shouldBe 3
            }
        }
        playRatedMatch(one = player, two = opponent)
        withClue(clue = "after 2 of 3") { service.statusFor(userId = player.id).inCalibration.shouldBeTrue() }

        // The 3rd rated match completes it — one more and they are out.
        playRatedMatch(one = player, two = opponent)
        withClue(clue = "after 3 of 3, the window is done") {
            service.statusFor(userId = player.id).let {
                it.inCalibration.shouldBeFalse()
                it.matchesRated shouldBe 3
            }
        }
    }

    @Test
    fun `lowering N ends an in-flight calibration immediately, with no sweep (#881)`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        val opponent = provision(uid = "opponent")
        designate(raterUid = "rater", userId = player.id)
        repeat(times = 4) { playRatedMatch(one = player, two = opponent) }

        // 4 rated matches into the default window of 10.
        service.statusFor(userId = player.id).inCalibration.shouldBeTrue()

        settings.setCalibrationMatches(token = token(uid = "admin"), matches = 3).shouldBeRight()

        // Immediately out, with nothing written against the player: this is the property that makes
        // calibration derived rather than stored. A stored boolean would still say "calibrating" here.
        service.statusFor(userId = player.id).inCalibration.shouldBeFalse()

        // ...and raising it re-opens the window for a player just past the old boundary.
        settings.setCalibrationMatches(token = token(uid = "admin"), matches = 10).shouldBeRight()
        service.statusFor(userId = player.id).inCalibration.shouldBeTrue()
    }

    @Test
    fun `a re-designation restarts the window (#881)`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        val opponent = provision(uid = "opponent")
        settings.setCalibrationMatches(token = token(uid = "admin"), matches = 2).shouldBeRight()

        designate(raterUid = "rater", userId = player.id)
        repeat(times = 2) { playRatedMatch(one = player, two = opponent) }
        service.statusFor(userId = player.id).inCalibration.shouldBeFalse()

        // A fresh designation is a fresh guess, so the clock restarts — and the matches played under the
        // previous window do not count against the new one.
        designate(raterUid = "rater", userId = player.id, value = "3.5")
        service.statusFor(userId = player.id).let {
            it.inCalibration.shouldBeTrue()
            it.matchesRated shouldBe 0
        }
    }

    @Test
    fun `a rating that predates the feature opens no window, so the rollout is prospective (#881)`() {
        val player = provision(uid = "player")
        val opponent = provision(uid = "opponent")
        // THE rollout guarantee: every existing row has a null designation stamp on deploy, so no settled
        // player is retroactively put into calibration. Getting this wrong would silently freeze rating
        // changes for every manually-rated player the moment this ships.
        simulatePreFeatureRating(userId = player.id)
        playRatedMatch(one = player, two = opponent)

        service.statusFor(userId = player.id).inCalibration.shouldBeFalse()
        service.isCalibrating(userId = player.id).shouldBeFalse()
    }

    @Test
    fun `every manual designation path opens a window, including the assembler used by the service (#881)`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val viaService = provision(uid = "via-service")
        val viaAssembler = provision(uid = "via-assembler")

        designate(raterUid = "rater", userId = viaService.id)
        // The assembler is what RatingService.setRating delegates to, so a self-assessment approval and a
        // rater's override both land here — neither needs to know calibration exists. Asserted because a
        // future path that bypassed this would silently never calibrate anyone.
        ratings.setRating(userId = viaAssembler.id, rating = BigDecimal("4.0"), level = "4.0")

        service.isCalibrating(userId = viaService.id).shouldBeTrue()
        service.isCalibrating(userId = viaAssembler.id).shouldBeTrue()
    }

    @Test
    fun `an unrated player is not calibrating (#881)`() {
        val player = provision(uid = "player")

        service.statusFor(userId = player.id).let {
            it.inCalibration.shouldBeFalse()
            it.matchesRated shouldBe 0
            // N is still reported, so a caller can say "0 of 10" without a second lookup.
            it.matchesRequired shouldBe 10
        }
    }

    @Test
    fun `only matches rated AFTER the designation advance the clock (#881)`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        val opponent = provision(uid = "opponent")
        settings.setCalibrationMatches(token = token(uid = "admin"), matches = 2).shouldBeRight()

        // Two matches rated BEFORE the designation. A player with a long history who is then re-assessed
        // must get a full window, not an instantly-expired one.
        repeat(times = 2) { playRatedMatch(one = player, two = opponent) }
        designate(raterUid = "rater", userId = player.id)

        service.statusFor(userId = player.id).let {
            it.inCalibration.shouldBeTrue()
            it.matchesRated shouldBe 0
        }
    }

    @Test
    fun `a completed but unrated match does not advance the clock (#881)`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        val opponent = provision(uid = "opponent")
        settings.setCalibrationMatches(token = token(uid = "admin"), matches = 1).shouldBeRight()
        designate(raterUid = "rater", userId = player.id)

        // Completed, result recorded — but ratings are computed later by an admin-triggered batch, and
        // the calibrating effect only applies at that point. So the clock must not have moved.
        val fixture =
            matches
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.of(2026, 1, 5),
                            team1UserIds = listOf(element = player.id),
                            team2UserIds = listOf(element = opponent.id),
                            team1Name = "T1",
                            team2Name = "T2",
                            createdBy = player.id,
                        ),
                ).toDomain()
        matches.addResult(
            matchId = fixture.id,
            sets =
                listOf(
                    element =
                        MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = fixture.team1.teamId),
                ),
            winnerTeamId = fixture.team1.teamId,
            recordedBy = player.id,
            completedAt = LocalDateTime.now(),
        )

        service.statusFor(userId = player.id).inCalibration.shouldBeTrue()
    }

    @Test
    fun `statusesFor answers for several players in one call (#881)`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val calibrating = provision(uid = "calibrating")
        val settled = provision(uid = "settled")
        designate(raterUid = "rater", userId = calibrating.id)
        simulatePreFeatureRating(userId = settled.id)

        // The per-match paths need every participant at once (doubles: four), with N read once so the two
        // sides of one calculation cannot disagree about it.
        val statuses = service.statusesFor(userIds = listOf(calibrating.id, settled.id, calibrating.id))

        statuses.size shouldBe 2
        statuses.getValue(key = calibrating.id).inCalibration.shouldBeTrue()
        statuses.getValue(key = settled.id).inCalibration.shouldBeFalse()
    }
}
