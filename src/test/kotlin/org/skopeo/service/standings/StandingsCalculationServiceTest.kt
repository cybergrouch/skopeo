// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.AwardStatus
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.RankingPointAwardWrite
import org.skopeo.model.ServiceError
import org.skopeo.model.StandingsBand
import org.skopeo.model.User
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.StandingsSnapshotRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class StandingsCalculationServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val awards = RankingPointRepository()
    private val snapshots = StandingsSnapshotRepository()
    private val service =
        StandingsCalculationService(awards = awards, snapshots = snapshots, users = users, ratings = ratings)
    private val standings = StandingsService(users = users, ratings = ratings, snapshots = snapshots)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
        sex: String? = "Male",
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = org.skopeo.model.UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = org.skopeo.model.UserName(type = NameType.DISPLAY, value = uid)),
                    sex = sex,
                    capabilities = roles,
                ),
        )

    /** A user with only a NICKNAME name (no active DISPLAY name) so [User.displayName] is null. */
    private fun provisionNoDisplayName(uid: String): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = org.skopeo.model.UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = org.skopeo.model.UserName(type = NameType.NICKNAME, value = uid)),
                    sex = "Male",
                    capabilities = setOf(element = Capability.PLAYER),
                ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    /**
     * Grant an award. Phase D (#403 #2) only counts an award while its band matches the player's
     * CURRENT band, so by default this also parks the player in [band] (unless they already carry a
     * rating a test set explicitly) — keeping the pre-D "band-tagged sum" cases meaningful. Tests that
     * exercise the band-scoped filter pass [setBand] = false (or a mismatching rating) deliberately.
     */
    private fun grant(
        userId: UUID,
        points: String,
        band: String = "4.0",
        sex: String = "Male",
        status: AwardStatus = AwardStatus.ACTIVE,
        validFrom: LocalDateTime = LocalDateTime.now().minusDays(1),
        validUntil: LocalDateTime = LocalDateTime.now().plusMonths(6),
        setBand: Boolean = true,
    ): org.skopeo.model.RankingPointAward {
        if (setBand && ratings.findCurrentRating(userId = userId) == null) {
            ratings.setRating(userId = userId, rating = BigDecimal(band), level = band)
        }
        return awards.award(
            write =
                RankingPointAwardWrite(
                    userId = userId,
                    points = BigDecimal(points),
                    pointClass = PointClass.ANNUAL_TOURNAMENT,
                    sourceType = PointSourceType.INTERNAL,
                    sourceId = null,
                    band = band,
                    sex = sex,
                    reason = null,
                    validFrom = validFrom,
                    validUntil = validUntil,
                    status = status,
                    revokesAwardId = null,
                    grantedBy = null,
                    awardedAt = LocalDateTime.now(),
                ),
        )
    }

    @Test
    fun `a dry run returns a preview and persists no snapshot`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        grant(userId = player.id, points = "100")

        val outcome = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight()
        outcome.dryRun shouldBe true
        outcome.groups shouldHaveSize 1
        outcome.groups.single().entries.single().points shouldBe BigDecimal("100.0000")
        // Nothing was published — the preferring-points read has no snapshot.
        snapshots.latestPublishedPreferringPoints().shouldBeNull()
    }

    @Test
    fun `a commit persists a POINTS PUBLISHED snapshot`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        grant(userId = player.id, points = "100")

        val outcome = service.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()
        outcome.dryRun shouldBe false
        val snapshotId = snapshots.latestPublishedPreferringPoints().shouldNotBeNull()
        val page = snapshots.page(snapshotId = snapshotId, band = StandingsBand.FROM_4_0, sex = "Male", limit = 25, offset = 0)
        page.total shouldBe 1
        page.entries.single().userId shouldBe player.id
        page.entries.single().orderingValue shouldBe BigDecimal("100.0000")
    }

    @Test
    fun `aggregation sums band-tagged points per (band, sex) and ranks descending`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val leader = provision(uid = "leader")
        val runnerUp = provision(uid = "runner")
        // A distinct 5.0-band player (Phase D counts an award only in the player's current band, so a
        // single player can't hold both a counted 4.0 and a counted 5.0 award).
        val fiver = provision(uid = "fiver")
        // Leader: two 4.0 awards summing to 150; runner-up: one 4.0 award of 120 — all parked in 4.0.
        grant(userId = leader.id, points = "100", band = "4.0")
        grant(userId = leader.id, points = "50", band = "4.0")
        grant(userId = runnerUp.id, points = "120", band = "4.0")
        // The 5.0 award is its own race — it must not spill into the 4.0 group (band-tagged).
        grant(userId = fiver.id, points = "999", band = "5.0")

        val outcome = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight()
        val fourOh = outcome.groups.single { it.band == StandingsBand.FROM_4_0 && it.sex == "Male" }
        fourOh.entries.map { it.userId } shouldContainExactly listOf(leader.id, runnerUp.id)
        fourOh.entries.first().points shouldBe BigDecimal("150.0000")
        // The 5.0 award formed a separate group counting only its own band's points.
        val fiveOh = outcome.groups.single { it.band == StandingsBand.FROM_5_0 }
        fiveOh.entries.single().points shouldBe BigDecimal("999.0000")
    }

    @Test
    fun `two groups in the same band are ordered Men before Women`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val man = provision(uid = "man", sex = "Male")
        val woman = provision(uid = "woman", sex = "Female")
        // Same band, different sex → the group order's band tie-break (Men → Women) decides the order.
        grant(userId = man.id, points = "100", band = "4.0", sex = "Male")
        grant(userId = woman.id, points = "100", band = "4.0", sex = "Female")

        val outcome = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight()
        outcome.groups.filter { it.band == StandingsBand.FROM_4_0 }.map { it.sex } shouldContainExactly listOf("Male", "Female")
    }

    @Test
    fun `an expired or revoked award is excluded`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        // Expired: valid window ended yesterday.
        grant(
            userId = player.id,
            points = "500",
            validFrom = LocalDateTime.now().minusMonths(2),
            validUntil = LocalDateTime.now().minusDays(1),
        )
        // Revoked: not ACTIVE.
        grant(userId = player.id, points = "500", status = AwardStatus.REVOKED)
        // Only this counts.
        grant(userId = player.id, points = "30")

        val outcome = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight()
        outcome.groups.single().entries.single().points shouldBe BigDecimal("30.0000")
    }

    @Test
    fun `a net-negative counted total is floored at 0 (#469)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player")
        // A manual deduction outweighs the award → net −50, but the counted total must not go below 0.
        grant(userId = player.id, points = "50")
        grant(userId = player.id, points = "-100")

        val entry = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight().groups.single().entries.single()
        entry.points shouldBe BigDecimal.ZERO
    }

    @Test
    fun `ties on points are broken by rating then name`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val higherRated = provision(uid = "b-higher")
        val lowerRated = provision(uid = "a-lower")
        val unratedAlpha = provision(uid = "a-unrated")
        val unratedBeta = provision(uid = "b-unrated")
        ratings.setRating(userId = higherRated.id, rating = BigDecimal("4.4"), level = "4.0")
        ratings.setRating(userId = lowerRated.id, rating = BigDecimal("4.1"), level = "4.0")
        // Everyone has the same points → the tie-break decides the order.
        listOf(higherRated, lowerRated, unratedAlpha, unratedBeta).forEach { grant(userId = it.id, points = "100") }

        val entries = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight().groups.single().entries
        // Higher rating first, then lower rating, then the two unrated by display name (a-, b-).
        entries.map { it.userId } shouldContainExactly listOf(higherRated.id, lowerRated.id, unratedAlpha.id, unratedBeta.id)
    }

    @Test
    fun `a name tie-break falls back to the public code when a player has no display name`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        // Two display-name-less players with identical points and no rating → the tie-break falls all the
        // way to public code (the displayName ?: publicCode fallback in standingName).
        val one = provisionNoDisplayName(uid = "one")
        val two = provisionNoDisplayName(uid = "two")
        grant(userId = one.id, points = "100")
        grant(userId = two.id, points = "100")

        val entries = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight().groups.single().entries
        entries shouldHaveSize 2
        // Ranked by public code ascending — both codes are non-null, so the order is deterministic.
        entries.map { it.publicCode } shouldContainExactly entries.map { it.publicCode }.sorted()
    }

    @Test
    fun `only active users are included`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val active = provision(uid = "active")
        val gone = provision(uid = "gone")
        grant(userId = active.id, points = "100")
        grant(userId = gone.id, points = "200")
        users.deactivate(id = gone.id).shouldBeRight()

        val entries = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight().groups.single().entries
        entries.map { it.userId } shouldContainExactly listOf(element = active.id)
    }

    @Test
    fun `a sexless award forms the Unspecified group`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "player", sex = null)
        grant(userId = player.id, points = "100", sex = "Unspecified")

        val group = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight().groups.single()
        group.sex.shouldBeNull()
        group.entries.single().userId shouldBe player.id
    }

    @Test
    fun `a non-admin is forbidden`() {
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        service.calculate(token = token(uid = "rater"), dryRun = true)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // An unprovisioned caller is also forbidden (null-caller arm).
        service.calculate(token = token(uid = "ghost"), dryRun = false)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `the read path serves rating by default and flips to points only when standings_source says so (#146)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val ratingLeader = provision(uid = "rating-leader")
        val pointsLeader = provision(uid = "points-leader")
        ratings.setRating(userId = ratingLeader.id, rating = BigDecimal("4.4"), level = "4.0")
        ratings.setRating(userId = pointsLeader.id, rating = BigDecimal("4.1"), level = "4.0")
        // Points invert the rating order: the lower-rated player has more points.
        grant(userId = ratingLeader.id, points = "10")
        grant(userId = pointsLeader.id, points = "500")

        // Rating-derived source serves live off the current ratings — ordered by rating.
        val ratingView = standings.page(token = token(uid = "admin"), band = StandingsBand.FROM_4_0, sex = "Male", limit = 25, offset = 0)
        ratingView.entries.first().userId shouldBe ratingLeader.id

        // Committing the points calculation NO LONGER auto-flips reads (#146): the source is config-gated,
        // so with standings_source unset (default RATING) reads still serve the rating-derived snapshot.
        service.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()
        val stillRating =
            standings.page(token = token(uid = "admin"), band = StandingsBand.FROM_4_0, sex = "Male", limit = 25, offset = 0)
        stillRating.entries.first().userId shouldBe ratingLeader.id

        // Flip the app-setting to POINTS → reads now serve the committed points snapshot, inverting the top spot.
        AppSettingsRepository().upsert(key = "standings_source", value = "POINTS", updatedBy = admin.id)
        val pointsView =
            standings.page(token = token(uid = "admin"), band = StandingsBand.FROM_4_0, sex = "Male", limit = 25, offset = 0)
        pointsView.entries.first().userId shouldBe pointsLeader.id
    }

    @Test
    fun `an empty ledger commits an empty snapshot`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val outcome = service.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()
        outcome.groups shouldHaveSize 0
        // A snapshot header was still published (with no entries).
        snapshots.latestPublishedPreferringPoints().shouldNotBeNull()
    }

    @Test
    fun `an empty ledger dry run records a preview and persists nothing`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight().groups shouldHaveSize 0
        snapshots.latestPublishedPreferringPoints().shouldBeNull()
    }

    // --- Band-scoped counting (#403 Phase D, decision #2 / §4). ---

    @Test
    fun `an award whose band differs from the player's current band is excluded (#403)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val mover = provision(uid = "mover")
        // The award is tagged 4.0 but the player has since moved to 4.5 — under band-scoped counting the
        // award stays owned in the ledger yet is NOT summed into any group.
        grant(userId = mover.id, points = "100", band = "4.0", setBand = false)
        ratings.setRating(userId = mover.id, rating = BigDecimal("4.5"), level = "4.5")

        val outcome = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight()
        outcome.groups shouldHaveSize 0
        // The award is still on the ledger (owned, just uncounted).
        awards.listByUser(userId = mover.id) shouldHaveSize 1
    }

    @Test
    fun `an award counts once the player is back in its band, and a player with no rating counts none (#403)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val returner = provision(uid = "returner")
        val unrated = provision(uid = "unrated")
        // returner is currently in 4.0 (matching the award band) → counts.
        grant(userId = returner.id, points = "100", band = "4.0")
        // unrated has an award but no rating → currentLevel is null, so nothing matches → counts none.
        grant(userId = unrated.id, points = "500", band = "4.0", setBand = false)

        val outcome = service.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight()
        val group = outcome.groups.single()
        group.entries.map { it.userId } shouldContainExactly listOf(element = returner.id)
        group.entries.single().points shouldBe BigDecimal("100.0000")
    }
}
