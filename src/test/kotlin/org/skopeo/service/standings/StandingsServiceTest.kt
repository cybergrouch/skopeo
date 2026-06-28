// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.StandingsBand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate

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

    private fun provision(uid: String): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    dateOfBirth = LocalDate.of(2000, 1, 1),
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

    private fun rate(
        user: User,
        value: String,
        confidence: String = "0.5",
    ) = ratings.setRating(
        userId = user.id,
        rating = BigDecimal(value),
        level = value.toBigDecimal().let { "${it.toInt()}.${if (it.toDouble() % 1.0 >= 0.5) 5 else 0}" },
        confidence = BigDecimal(confidence),
    )

    private fun band(label: StandingsBand) = service.standings().single { it.band == label }

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
    fun `standings return all bands strongest-first`() {
        service.standings().map { it.band } shouldBe StandingsBand.entries.reversed()
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
        val low = provision(uid = "zoe").also { rate(user = it, value = "4.0", confidence = "0.3") }
        val high = provision(uid = "amy").also { rate(user = it, value = "4.0", confidence = "0.9") }

        band(label = StandingsBand.FROM_4_0).entries.map { it.userId } shouldBe listOf(high.id, low.id)
    }

    @Test
    fun `ties on rating and confidence break by name, fall back to code, and omit age when no DOB`() {
        val bravo = provision(uid = "Bravo").also { rate(user = it, value = "4.0", confidence = "0.5") }
        val nameless = provisionPlain(uid = "nameless").also { rate(user = it, value = "4.0", confidence = "0.5") }

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
