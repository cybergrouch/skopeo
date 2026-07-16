// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.MatchRatingWrite
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.StandingsBand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRatingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class StandingsServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val service = StandingsService()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private val viewerToken = token(uid = "viewer")

    private fun provision(
        uid: String,
        sex: String? = null,
        capabilities: Set<Capability> = setOf(element = Capability.PLAYER),
        dateOfBirth: LocalDate? = LocalDate.of(2000, 1, 1),
        withName: Boolean = true,
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = if (withName) listOf(element = UserName(type = NameType.DISPLAY, value = uid)) else emptyList(),
                    dateOfBirth = dateOfBirth,
                    sex = sex,
                    capabilities = capabilities,
                ),
        )

    // Confidence is computed (#343): min(1, matches/5) when match-derived and fresh; 0 for a bare override.
    private fun rate(
        user: User,
        value: String,
        matches: Int = 0,
    ) {
        val level = value.toBigDecimal().let { "${it.toInt()}.${if (it.toDouble() % 1.0 >= 0.5) 5 else 0}" }
        ratings.setRating(userId = user.id, rating = BigDecimal(value), level = level)
        repeat(times = matches) {
            ratings.applyMatchRating(
                write =
                    MatchRatingWrite(
                        userId = user.id,
                        newRating = BigDecimal(value),
                        newLevel = level,
                        matchDate = LocalDate.now(),
                        ratedAt = LocalDateTime.now(),
                        bandJumped = false,
                    ),
            )
        }
    }

    private fun page(
        band: StandingsBand?,
        sex: String?,
        limit: Int? = null,
        offset: Int? = null,
        token: VerifiedFirebaseToken = viewerToken,
    ) = service.page(token = token, band = band, sex = sex, limit = limit, offset = offset)

    @Test
    fun `page on an empty snapshot returns an empty view with no groups (#220)`() {
        val view = page(band = null, sex = null)
        view.band.shouldBeNull()
        view.entries shouldHaveSize 0
        view.total shouldBe 0
        view.groups shouldHaveSize 0
    }

    @Test
    fun `rebuild ranks each (band, sex) group from 1 and page serves it (#220)`() {
        val m1 = provision(uid = "m1", sex = "Male").also { rate(user = it, value = "4.3") }
        val m2 = provision(uid = "m2", sex = "Male").also { rate(user = it, value = "4.1") }
        provision(uid = "f1", sex = "Female").also { rate(user = it, value = "4.4") }
        service.rebuild()

        val men = page(band = StandingsBand.FROM_4_0, sex = "Male")
        men.band shouldBe StandingsBand.FROM_4_0
        men.sex shouldBe "Male"
        men.total shouldBe 2
        men.entries.map { it.userId } shouldContainExactly listOf(m1.id, m2.id) // 4.3 ahead of 4.1
        men.entries.map { it.rank } shouldContainExactly listOf(1, 2)

        // The selectors list both the Men and Women groups of the 4.0 band.
        men.groups.map { it.band to it.sex } shouldContainExactly
            listOf(StandingsBand.FROM_4_0 to "Male", StandingsBand.FROM_4_0 to "Female")
    }

    @Test
    fun `page defaults to the strongest available group when no band is requested (#220)`() {
        provision(uid = "lo", sex = "Male").also { rate(user = it, value = "4.0") } // FROM_4_0
        val hi = provision(uid = "hi", sex = "Male").also { rate(user = it, value = "6.5") } // SIX_PLUS
        service.rebuild()

        val view = page(band = null, sex = null)
        view.band shouldBe StandingsBand.SIX_PLUS
        view.entries.single().userId shouldBe hi.id
    }

    @Test
    fun `page paginates within a group, honoring limit and offset (#220)`() {
        val ranked = (1..3).map { provision(uid = "p$it", sex = "Male").also { u -> rate(user = u, value = "4.${4 - it}") } }
        service.rebuild()

        val first = page(band = StandingsBand.FROM_4_0, sex = "Male", limit = 2, offset = 0)
        first.total shouldBe 3
        first.entries.map { it.userId } shouldContainExactly listOf(ranked[0].id, ranked[1].id)

        val second = page(band = StandingsBand.FROM_4_0, sex = "Male", limit = 2, offset = 2)
        second.entries.map { it.userId } shouldContainExactly listOf(element = ranked[2].id)
    }

    @Test
    fun `rebuild replaces the served snapshot after a rating changes (#220)`() {
        val player = provision(uid = "p", sex = "Male").also { rate(user = it, value = "4.2") }
        service.rebuild()
        page(band = StandingsBand.FROM_4_0, sex = "Male").entries.single().userId shouldBe player.id

        // A promotion into a higher band; rebuild moves the player there.
        rate(user = player, value = "4.7")
        service.rebuild()
        page(band = StandingsBand.FROM_4_0, sex = "Male").total shouldBe 0
        page(band = StandingsBand.FROM_4_5, sex = "Male").entries.single().userId shouldBe player.id
    }

    @Test
    fun `precise rating is revealed to a rater and hidden from a plain player (#186)`() {
        provision(uid = "rater", capabilities = setOf(Capability.PLAYER, Capability.RATER))
        val player = provision(uid = "p", sex = "Male").also { rate(user = it, value = "4.2") }
        service.rebuild()

        page(band = StandingsBand.FROM_4_0, sex = "Male", token = token(uid = "rater"))
            .entries.single { it.userId == player.id }.currentRating shouldBe "4.200000"
        page(band = StandingsBand.FROM_4_0, sex = "Male", token = token(uid = "p"))
            .entries.single { it.userId == player.id }.currentRating.shouldBeNull()
    }

    @Test
    fun `disabled players are excluded from the rebuilt snapshot (#220)`() {
        val active = provision(uid = "active", sex = "Male").also { rate(user = it, value = "4.0") }
        val disabled = provision(uid = "disabled", sex = "Male").also { rate(user = it, value = "4.0") }
        users.deactivate(id = disabled.id)
        service.rebuild()

        page(band = StandingsBand.FROM_4_0, sex = "Male").entries.let {
            it shouldHaveSize 1
            it.single().userId shouldBe active.id
        }
    }

    @Test
    fun `locateMe returns the caller's location and containing page offset (#220)`() {
        // 30 players all strictly above the caller (4.10–4.19, all in the 4.0 band), so the caller is
        // unambiguously last regardless of tie-break.
        (1..30).forEach { provision(uid = "p$it", sex = "Male").also { u -> rate(user = u, value = "4.1${it % 10}") } }
        // The caller is deliberately weakest so they land at the end (page 2 at 25/page).
        provision(uid = "me", sex = "Male").also { rate(user = it, value = "4.001") }
        service.rebuild()

        val located = service.locateMe(token = token(uid = "me"), limit = 25).shouldNotBeNull()
        located.location.band shouldBe StandingsBand.FROM_4_0
        located.location.sex shouldBe "Male"
        // rank is 1-based; with 31 players the weakest is rank 31 → offset 25 (page 2).
        located.location.rank shouldBe 31
        located.offset shouldBe 25
        located.limit shouldBe 25
    }

    @Test
    fun `locateMe is null when the caller is unrated or unknown (#220)`() {
        provision(uid = "rated", sex = "Male").also { rate(user = it, value = "4.0") }
        val unrated = provision(uid = "unrated", sex = "Male")
        service.rebuild()

        service.locateMe(token = token(uid = "unrated"), limit = 25).shouldBeNull()
        service.locateMe(token = token(uid = "ghost"), limit = 25).shouldBeNull()
        unrated.isActive shouldBe true // sanity: the user exists but simply isn't in the snapshot
    }

    @Test
    fun `locateMe is null when no snapshot has been published (#220)`() {
        // The caller has a profile but rebuild was never run, so there is no published snapshot to locate in.
        provision(uid = "me", sex = "Male").also { rate(user = it, value = "4.0") }
        service.locateMe(token = token(uid = "me"), limit = 25).shouldBeNull()
    }

    @Test
    fun `page for a band, sex group absent from the snapshot returns an empty view still carrying groups (#220)`() {
        // Only a Male 4.0 group exists; requesting Female for that band yields an empty page, not a fallback.
        provision(uid = "m", sex = "Male").also { rate(user = it, value = "4.0") }
        service.rebuild()

        val view = page(band = StandingsBand.FROM_4_0, sex = "Female")
        view.entries shouldHaveSize 0
        view.total shouldBe 0
        // The selectors still advertise the group that does exist.
        view.groups.map { it.band to it.sex } shouldContainExactly listOf(element = StandingsBand.FROM_4_0 to "Male")
    }

    @Test
    fun `page fills age only for entries whose user has a date of birth (#220)`() {
        val withDob =
            provision(uid = "withdob", sex = "Male", dateOfBirth = LocalDate.of(1990, 6, 1))
                .also { rate(user = it, value = "4.3") }
        val noDob =
            provision(uid = "nodob", sex = "Male", dateOfBirth = null)
                .also { rate(user = it, value = "4.1") }
        service.rebuild()

        val entries = page(band = StandingsBand.FROM_4_0, sex = "Male").entries.associateBy { it.userId }
        entries.getValue(key = withDob.id).age.shouldNotBeNull()
        entries.getValue(key = noDob.id).age.shouldBeNull()
    }

    @Test
    fun `precise rating is null for an entry whose current rating is absent (#186)`() {
        provision(uid = "rater", capabilities = setOf(Capability.PLAYER, Capability.RATER))
        val kept = provision(uid = "kept", sex = "Male").also { rate(user = it, value = "4.3") }
        val gone = provision(uid = "gone", sex = "Male").also { rate(user = it, value = "4.1") }
        service.rebuild()
        // The snapshot ranks both, but the current-rating row for one is removed before the read — the
        // privileged reveal then has no live rating to show for that entry.
        transaction { UserRatingsTable.deleteWhere { UserRatingsTable.userId eq gone.id } }

        val entries =
            page(band = StandingsBand.FROM_4_0, sex = "Male", token = token(uid = "rater")).entries.associateBy { it.userId }
        entries.getValue(key = kept.id).currentRating shouldBe "4.300000"
        entries.getValue(key = gone.id).currentRating.shouldBeNull()
    }

    @Test
    fun `tie-break falls back to public code when a tied player has no display name (#220)`() {
        // Two players tie on rating, confidence and matches, so the tie-break falls to the name comparison;
        // one has no display name and must sort by its public code rather than blow up.
        val named = provision(uid = "named", sex = "Male").also { rate(user = it, value = "4.2") }
        val unnamed = provision(uid = "unnamed", sex = "Male", withName = false).also { rate(user = it, value = "4.2") }
        service.rebuild()

        val entries = page(band = StandingsBand.FROM_4_0, sex = "Male").entries
        entries shouldHaveSize 2
        entries.map { it.userId }.toSet() shouldBe setOf(named.id, unnamed.id)
        entries.single { it.userId == unnamed.id }.displayName.shouldBeNull()
    }
}
