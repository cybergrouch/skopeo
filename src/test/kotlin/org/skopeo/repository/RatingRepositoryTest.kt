// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.util.UUID

class RatingRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users.provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
            ),
        ).id

    @Test
    fun `setRating inserts then updates, per system`() {
        val userId = newUser("u1")

        ratings.setRating(userId, BigDecimal("3.5"), "3.5", BigDecimal("0.50"))
        ratings.findCurrentRating(userId)!!.let {
            it.currentRating shouldBe BigDecimal("3.500000")
            it.currentLevel shouldBe "3.5"
            it.matchesPlayed shouldBe 0
        }

        ratings.setRating(userId, BigDecimal("4.0"), "4.0", BigDecimal("0.60"))
        ratings.findCurrentRating(userId)!!.currentRating shouldBe BigDecimal("4.000000")
        ratings.findByUser(userId).size shouldBe 1
    }

    @Test
    fun `findByUserAndSystem is null when absent`() {
        ratings.findCurrentRating(UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `history is empty for a new user (both filters)`() {
        val userId = newUser("u2")
        ratings.historyByUser(userId) shouldBe emptyList()
    }

    @Test
    fun `pending assessment lists active users without a rating`() {
        val unrated = newUser("unrated")
        val rated = newUser("rated")
        ratings.setRating(rated, BigDecimal("3.0"), "3.0", BigDecimal("0.50"))

        val pending = ratings.userIdsPendingAssessment()
        pending shouldContain unrated
        pending shouldNotContain rated
    }
}
