// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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

    /** A non-privileged viewer's token — used where the precise-rating reveal is irrelevant. */
    private val viewerToken = token(uid = "viewer")

    private fun provision(
        uid: String,
        sex: String? = null,
        capabilities: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    dateOfBirth = LocalDate.of(2000, 1, 1),
                    sex = sex,
                    capabilities = capabilities,
                ),
        )

    /** A player with no display name and no date of birth → standings fall back to the code and omit age. */
    private fun provisionPlain(uid: String): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = emptyList(),
                ),
        )

    // Confidence is computed (#343): min(1, matches/5) when the rating is match-derived and fresh
    // (matchRatedAt = now ⇒ decay ≈ 1.0). `matches` = 0 leaves it a bare override (confidence 0).
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

    private fun band(label: StandingsBand) = service.standings(token = viewerToken).single { it.band == label }

    @Test
    fun `of() buckets ratings into the lumped and 0_5-wide bands`() {
        StandingsBand.of(rating = BigDecimal("1.0")) shouldBe StandingsBand.UNDER_3_0
        StandingsBand.of(rating = BigDecimal("2.99")) shouldBe StandingsBand.UNDER_3_0
        StandingsBand.of(rating = BigDecimal("3.0")) shouldBe StandingsBand.FROM_3_0
        StandingsBand.of(rating = BigDecimal("3.49")) shouldBe StandingsBand.FROM_3_0
        StandingsBand.of(rating = BigDecimal("3.5")) shouldBe StandingsBand.FROM_3_5
        StandingsBand.of(rating = BigDecimal("5.99")) shouldBe StandingsBand.FROM_5_5
        StandingsBand.of(rating = BigDecimal("6.0")) shouldBe StandingsBand.SIX_PLUS
        StandingsBand.of(rating = BigDecimal("7.0")) shouldBe StandingsBand.SIX_PLUS
    }

    @Test
    fun `standings list non-empty bands strongest-first, empty bands omitted`() {
        provision(uid = "lo").also { rate(user = it, value = "4.0") } // FROM_4_0
        provision(uid = "hi").also { rate(user = it, value = "6.5") } // SIX_PLUS
        service.standings(token = viewerToken).map { it.band } shouldBe listOf(StandingsBand.SIX_PLUS, StandingsBand.FROM_4_0)
    }

    @Test
    fun `standings split a band by sex, ranking each group from 1 (#212)`() {
        val m1 = provision(uid = "m1", sex = "Male").also { rate(user = it, value = "4.3") }
        val m2 = provision(uid = "m2", sex = "Male").also { rate(user = it, value = "4.1") }
        val f1 = provision(uid = "f1", sex = "Female").also { rate(user = it, value = "4.4") }
        val u1 = provision(uid = "u1", sex = null).also { rate(user = it, value = "4.2") }

        val rows = service.standings(token = viewerToken).filter { it.band == StandingsBand.FROM_4_0 }
        // One row per sex present, in Men → Women → Unspecified order.
        rows.map { it.sex } shouldBe listOf("Male", "Female", null)

        val men = rows.single { it.sex == "Male" }
        men.entries.map { it.userId } shouldBe listOf(m1.id, m2.id) // 4.3 ahead of 4.1
        men.entries.map { it.rank } shouldBe listOf(1, 2) // rank restarts within the group
        rows.single { it.sex == "Female" }.entries.single().userId shouldBe f1.id
        rows.single { it.sex == null }.entries.single().userId shouldBe u1.id
    }

    @Test
    fun `players are bucketed by band and ranked by rating within a band`() {
        val alice = provision(uid = "alice").also { rate(user = it, value = "4.6") } // 4.5–5.0
        val bob = provision(uid = "bob").also { rate(user = it, value = "4.2") } // 4.0–4.5
        val carol = provision(uid = "carol").also { rate(user = it, value = "4.1") } // 4.0–4.5

        band(label = StandingsBand.FROM_4_0).entries.let {
            it.map { e -> e.userId } shouldBe listOf(bob.id, carol.id)
            it.map { e -> e.rank } shouldBe listOf(1, 2)
        }
        band(label = StandingsBand.FROM_4_5).entries.single().userId shouldBe alice.id
    }

    @Test
    fun `rating ties within a band break by confidence then name`() {
        val low = provision(uid = "zoe").also { rate(user = it, value = "4.0", matches = 1) }
        val high = provision(uid = "amy").also { rate(user = it, value = "4.0", matches = 5) }

        band(label = StandingsBand.FROM_4_0).entries.map { it.userId } shouldBe listOf(high.id, low.id)
    }

    @Test
    fun `ties on rating and confidence break by name, fall back to code, and omit age when no DOB`() {
        val bravo = provision(uid = "Bravo").also { rate(user = it, value = "4.0", matches = 5) }
        val nameless = provisionPlain(uid = "nameless").also { rate(user = it, value = "4.0", matches = 5) }

        val entries = band(label = StandingsBand.FROM_4_0).entries
        entries shouldHaveSize 2
        // The named player carries an age; the nameless one shows no display name and no age.
        entries.single { it.userId == bravo.id }.age shouldBe 26
        entries.single { it.userId == nameless.id }.let {
            it.displayName shouldBe null
            it.age shouldBe null
        }
    }

    @Test
    fun `precise rating is revealed to a rater and an admin, hidden from a plain player (#186)`() {
        provision(uid = "rater", capabilities = setOf(Capability.PLAYER, Capability.RATER))
        provision(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val player = provision(uid = "p").also { rate(user = it, value = "4.2") }

        // Both a RATER and an ADMINISTRATOR see the precise 6-dp rating.
        service.standings(token = token(uid = "rater")).flatMap { it.entries }
            .single { it.userId == player.id }.currentRating shouldBe "4.200000"
        service.standings(token = token(uid = "root")).flatMap { it.entries }
            .single { it.userId == player.id }.currentRating shouldBe "4.200000"

        // A plain player never sees an exact rating.
        service.standings(token = token(uid = "p")).flatMap { it.entries }
            .single { it.userId == player.id }.currentRating.shouldBeNull()
    }

    @Test
    fun `disabled players are excluded from the standings`() {
        val active = provision(uid = "active").also { rate(user = it, value = "4.0") }
        val disabled = provision(uid = "disabled").also { rate(user = it, value = "4.0") }
        users.deactivate(id = disabled.id)

        band(label = StandingsBand.FROM_4_0).entries.let {
            it shouldHaveSize 1
            it.single().userId shouldBe active.id
        }
    }
}
