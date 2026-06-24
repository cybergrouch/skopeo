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
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                ),
        ).id

    @Test
    fun `setRating inserts then updates, per system`() {
        val userId = newUser(uid = "u1")

        ratings.setRating(userId = userId, rating = BigDecimal("3.5"), level = "3.5", confidence = BigDecimal("0.50"))
        ratings.findCurrentRating(userId = userId)!!.let {
            it.currentRating shouldBe BigDecimal("3.500000")
            it.currentLevel shouldBe "3.5"
            it.matchesPlayed shouldBe 0
        }

        ratings.setRating(userId = userId, rating = BigDecimal("4.0"), level = "4.0", confidence = BigDecimal("0.60"))
        ratings.findCurrentRating(userId = userId)!!.currentRating shouldBe BigDecimal("4.000000")
        ratings.findByUser(userId = userId).size shouldBe 1
    }

    @Test
    fun `findByUserAndSystem is null when absent`() {
        ratings.findCurrentRating(userId = UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `history is empty for a new user (both filters)`() {
        val userId = newUser(uid = "u2")
        ratings.historyByUser(userId = userId) shouldBe emptyList()
    }

    @Test
    fun `pending assessment lists active users without a rating`() {
        val unrated = newUser(uid = "unrated")
        val rated = newUser(uid = "rated")
        ratings.setRating(userId = rated, rating = BigDecimal("3.0"), level = "3.0", confidence = BigDecimal("0.50"))

        val pending = ratings.userIdsPendingAssessment()
        pending shouldContain unrated
        pending shouldNotContain rated
    }
}
