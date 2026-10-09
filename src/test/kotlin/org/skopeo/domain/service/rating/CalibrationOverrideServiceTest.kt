// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.rating

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditCategory
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CalibrationOverride
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.model.category
import org.skopeo.domain.service.settings.SettingsService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRatingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.fixtureEventId
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The per-player calibration override (#1126): what each value does to the verdict, who may set it, the
 * reason and audit it requires, the Ratings tab's list, and its behaviour across re-rating, N and merges.
 */
class CalibrationOverrideServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val matches = MatchRepository()
    private val ratingRows = RatingRepository()
    private val ratingService = RatingService()
    private val settings = SettingsService()
    private val calibration = CalibrationService()
    private val audit = AuditRepository()
    private val service = CalibrationOverrideService()

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
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        sex = "Male",
                        capabilities = roles,
                    ),
            ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun admin(): User = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    /** A manual designation, which opens a calibration window. */
    private fun designate(userId: UUID) =
        ratingService.setRating(token = token(uid = "admin"), userId = userId, value = "4.0").shouldBeRight()

    /** A rated match between [one] and [two], which advances both players' calibration count. */
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
                            eventId = fixtureEventId(),
                        ),
                ).toDomain()
        matches.addResult(
            matchId = fixture.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = fixture.team1.teamId)),
            winnerTeamId = fixture.team1.teamId,
            recordedBy = one.id,
            completedAt = LocalDateTime.now(),
        )
        matches.markRated(matchId = fixture.id, ratedAt = LocalDateTime.now(), ratedBy = one.id)
    }

    private fun setOverride(
        userId: UUID,
        override: String,
        reason: String = "a reason",
        byUid: String = "admin",
    ) = service.setOverride(token = token(uid = byUid), userId = userId, override = override, reason = reason)

    @Test
    fun `forcing calibration off ends it now, and the Activity Log says who, what and why`() {
        val admin = admin()
        val player = provision(uid = "player").also { designate(userId = it.id) }
        calibration.isCalibrating(userId = player.id).shouldBeTrue()

        val result = setOverride(userId = player.id, override = "FORCED_OFF", reason = "Rated elsewhere for years").shouldBeRight()

        result.inCalibration.shouldBeFalse()
        result.override shouldBe "FORCED_OFF"
        result.overrideReason shouldBe "Rated elsewhere for years"
        result.overrideByPublicCode shouldBe admin.publicCode
        calibration.isCalibrating(userId = player.id).shouldBeFalse()

        val entry = audit.list(actions = listOf(element = AuditAction.CALIBRATION_OVERRIDE_CHANGED), limit = 10, offset = 0).first.single()
        AuditAction.CALIBRATION_OVERRIDE_CHANGED.category shouldBe AuditCategory.RATING_CHANGE
        entry.actorUserId shouldBe admin.id
        entry.entityId shouldBe player.id
        entry.summary shouldBe "Set calibration for ${player.publicCode} to Forced off (was Automatic): Rated elsewhere for years"
        entry.details["previousOverride"] shouldBe "AUTOMATIC"
        entry.details["override"] shouldBe "FORCED_OFF"
        entry.details["matchesRated"] shouldBe "0"
        entry.details["matchesRequired"] shouldBe "10"
    }

    @Test
    fun `forcing calibration on keeps a player calibrating past N, even a rating that never opened a window`() {
        admin()
        settings.setCalibrationMatches(token = token(uid = "admin"), matches = 1).shouldBeRight()
        val player = provision(uid = "player").also { designate(userId = it.id) }
        val opponent = provision(uid = "opponent").also { designate(userId = it.id) }
        playRatedMatch(one = player, two = opponent)
        calibration.isCalibrating(userId = player.id).shouldBeFalse()

        setOverride(userId = player.id, override = "FORCED_ON").shouldBeRight().inCalibration.shouldBeTrue()
        calibration.statusFor(userId = player.id).let {
            it.inCalibration.shouldBeTrue()
            it.matchesRated shouldBe 1
            it.override shouldBe CalibrationOverride.FORCED_ON
        }

        // A rating from before #881 has no window at all; forcing it on still works, from zero.
        val legacy = provision(uid = "legacy")
        ratingRows.setRating(userId = legacy.id, rating = BigDecimal("4.0"), level = "4.0")
        transaction { UserRatingsTable.update(where = { UserRatingsTable.userId eq legacy.id }) { it[calibrationStartedAt] = null } }
        setOverride(userId = legacy.id, override = "FORCED_ON").shouldBeRight().let {
            it.inCalibration.shouldBeTrue()
            it.matchesRated shouldBe 0
        }
    }

    @Test
    fun `changing N moves only automatic players`() {
        admin()
        val automatic = provision(uid = "automatic").also { designate(userId = it.id) }
        val forcedOn = provision(uid = "forced").also { designate(userId = it.id) }
        val opponent = provision(uid = "opponent").also { designate(userId = it.id) }
        playRatedMatch(one = automatic, two = opponent)
        playRatedMatch(one = forcedOn, two = opponent)
        setOverride(userId = forcedOn.id, override = "FORCED_ON").shouldBeRight()

        settings.setCalibrationMatches(token = token(uid = "admin"), matches = 1).shouldBeRight()

        calibration.isCalibrating(userId = automatic.id).shouldBeFalse()
        calibration.isCalibrating(userId = forcedOn.id).shouldBeTrue()
    }

    @Test
    fun `a manual re-rating resets the override to automatic, and the rating audit records the reset`() {
        admin()
        val player = provision(uid = "player").also { designate(userId = it.id) }
        setOverride(userId = player.id, override = "FORCED_OFF").shouldBeRight()

        designate(userId = player.id)

        calibration.statusFor(userId = player.id).let {
            it.override shouldBe CalibrationOverride.AUTOMATIC
            it.inCalibration.shouldBeTrue()
        }
        val reRating = audit.list(actions = listOf(element = AuditAction.RATING_OVERRIDDEN), limit = 10, offset = 0).first.single()
        reRating.details["calibrationOverrideReset"] shouldBe "FORCED_OFF"
        // An ordinary re-rating with nothing to reset leaves no such key: of the two re-ratings, only the
        // first carries it. (Counted rather than picking "the latest", which a shared timestamp could tie.)
        designate(userId = player.id)
        val reRatings = audit.list(actions = listOf(element = AuditAction.RATING_OVERRIDDEN), limit = 10, offset = 0).first
        reRatings.size shouldBe 2
        reRatings.count { it.details.containsKey(key = "calibrationOverrideReset") } shouldBe 1
    }

    @Test
    fun `a reason is required, the value must be known, and the player must have a rating`() {
        admin()
        val player = provision(uid = "player").also { designate(userId = it.id) }
        val unrated = provision(uid = "unrated")

        listOf("", "   ").forEach { blank ->
            withClue(clue = "reason '$blank'") {
                setOverride(
                    userId = player.id,
                    override = "FORCED_OFF",
                    reason = blank,
                ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
            }
        }
        setOverride(userId = player.id, override = "FORCED_OFF", reason = "x".repeat(n = 501))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        setOverride(userId = player.id, override = "OFF").shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        setOverride(userId = unrated.id, override = "FORCED_OFF").shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
        setOverride(userId = UUID.randomUUID(), override = "FORCED_OFF").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        // The longest allowed reason is accepted, and surrounding whitespace is trimmed off.
        val longest = "x".repeat(n = 500)
        setOverride(userId = player.id, override = "FORCED_OFF", reason = "  $longest  ").shouldBeRight().overrideReason shouldBe longest
    }

    @Test
    fun `everyone who may rate may override calibration, and nobody else`() {
        admin()
        val player = provision(uid = "player").also { designate(userId = it.id) }
        mapOf(
            "rater" to Capability.RATER,
            "host" to Capability.HOST,
            "owner" to Capability.CLUB_OWNER,
        ).forEach { (uid, role) ->
            provision(uid = uid, roles = setOf(Capability.PLAYER, role))
            withClue(clue = "$role may override") { setOverride(userId = player.id, override = "FORCED_OFF", byUid = uid).shouldBeRight() }
            withClue(clue = "$role may list") {
                service.listCalibrations(token = token(uid = uid), includeForcedOff = false, limit = 10, offset = 0).shouldBeRight()
            }
        }
        mapOf(
            "plain" to Capability.PLAYER,
            "manager" to Capability.ACCOUNT_MANAGER,
            "sweeper" to Capability.ACCOUNT_SWEEPER,
        ).forEach { (uid, role) ->
            provision(uid = uid, roles = setOf(Capability.PLAYER, role))
            withClue(clue = "$role may not override") {
                setOverride(
                    userId = player.id,
                    override = "FORCED_ON",
                    byUid = uid,
                ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
            }
            withClue(clue = "$role may not list") {
                service
                    .listCalibrations(token = token(uid = uid), includeForcedOff = false, limit = 10, offset = 0)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<ServiceError.Forbidden>()
            }
        }
    }

    @Test
    fun `the calibration list holds exactly who the evaluator says is calibrating, fewest matches first`() {
        admin()
        settings.setCalibrationMatches(token = token(uid = "admin"), matches = 2).shouldBeRight()
        val fresh = provision(uid = "fresh").also { designate(userId = it.id) }
        val oneIn = provision(uid = "one-in").also { designate(userId = it.id) }
        val finished = provision(uid = "finished").also { designate(userId = it.id) }
        val forcedOn = provision(uid = "forced-on").also { designate(userId = it.id) }
        val forcedOff = provision(uid = "forced-off").also { designate(userId = it.id) }
        val opponent = provision(uid = "opponent").also { designate(userId = it.id) }
        playRatedMatch(one = oneIn, two = opponent)
        repeat(times = 2) { playRatedMatch(one = finished, two = opponent) }
        repeat(times = 2) { playRatedMatch(one = forcedOn, two = opponent) }
        setOverride(userId = forcedOn.id, override = "FORCED_ON").shouldBeRight()
        setOverride(userId = forcedOff.id, override = "FORCED_OFF", reason = "Known quantity").shouldBeRight()
        // The opponent has played 4 rated matches against N = 2, so is settled.

        val page = service.listCalibrations(token = token(uid = "admin"), includeForcedOff = false, limit = 10, offset = 0).shouldBeRight()

        page.items.map { it.publicCode } shouldContainExactly listOf(fresh.publicCode, oneIn.publicCode, forcedOn.publicCode)
        page.total shouldBe 3
        // The SQL list and CalibrationService agree, player by player: the one rule, twice.
        val everyone = listOf(fresh, oneIn, finished, forcedOn, forcedOff, opponent)
        val evaluator = calibration.statusesFor(userIds = everyone.map { it.id })
        page.items.map {
            it.userId
        } shouldContainExactlyInAnyOrder everyone.filter { evaluator.getValue(key = it.id).inCalibration }.map { it.id.toString() }

        val withForcedOff =
            service.listCalibrations(
                token = token(uid = "admin"),
                includeForcedOff = true,
                limit = 10,
                offset = 0,
            ).shouldBeRight()
        withForcedOff.items.map { it.publicCode } shouldContainExactlyInAnyOrder
            listOf(fresh.publicCode, oneIn.publicCode, forcedOn.publicCode, forcedOff.publicCode)
        withForcedOff.items.single { it.publicCode == forcedOff.publicCode }.let {
            it.inCalibration.shouldBeFalse()
            it.override shouldBe "FORCED_OFF"
            it.overrideReason shouldBe "Known quantity"
            it.overrideByName shouldBe "admin"
        }

        // Paged.
        service.listCalibrations(token = token(uid = "admin"), includeForcedOff = false, limit = 1, offset = 1).shouldBeRight().let {
            it.items.map { item -> item.publicCode } shouldContainExactly listOf(element = oneIn.publicCode)
            it.total shouldBe 3
        }
    }

    @Test
    fun `a merge keeps the kept account's override and never inherits the retired account's`() {
        admin()
        val survivor = provision(uid = "survivor").also { designate(userId = it.id) }
        val retired = provision(uid = "retired").also { designate(userId = it.id) }
        setOverride(userId = retired.id, override = "FORCED_OFF", reason = "About the other rating").shouldBeRight()

        // The same two steps DuplicateService.mergeAccounts takes.
        users.mergeAccounts(retiredId = retired.id, survivorId = survivor.id, transferLogin = false)
        ratingRows.inheritEarlierCalibrationStart(survivorId = survivor.id, retiredId = retired.id)

        calibration.statusFor(userId = survivor.id).let {
            it.override shouldBe CalibrationOverride.AUTOMATIC
            it.inCalibration.shouldBeTrue()
        }

        // ...and a kept account's own override survives a merge.
        val other = provision(uid = "other").also { designate(userId = it.id) }
        setOverride(userId = survivor.id, override = "FORCED_ON", reason = "Still unsure").shouldBeRight()
        users.mergeAccounts(retiredId = other.id, survivorId = survivor.id, transferLogin = false)
        ratingRows.inheritEarlierCalibrationStart(survivorId = survivor.id, retiredId = other.id)
        calibration.statusFor(userId = survivor.id).override shouldBe CalibrationOverride.FORCED_ON
        service
            .listCalibrations(token = token(uid = "admin"), includeForcedOff = false, limit = 10, offset = 0)
            .shouldBeRight()
            .items
            .single { it.publicCode == survivor.publicCode }
            .overrideReason shouldContain "Still unsure"
    }
}
