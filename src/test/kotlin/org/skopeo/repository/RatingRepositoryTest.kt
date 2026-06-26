// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
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
    fun `pending assessment lists active users without a rating, with a total`() {
        val unrated = newUser(uid = "unrated")
        val rated = newUser(uid = "rated")
        ratings.setRating(userId = rated, rating = BigDecimal("3.0"), level = "3.0", confidence = BigDecimal("0.50"))

        val (ids, total) = ratings.userIdsPendingAssessment(limit = 50, offset = 0)
        ids shouldContain unrated
        ids shouldNotContain rated
        total shouldBe 1L
    }

    @Test
    fun `pending assessment paginates by limit and offset into disjoint, complete pages`() {
        val all = (1..5).map { newUser(uid = "p$it") }.toSet()

        val (page1, total) = ratings.userIdsPendingAssessment(limit = 2, offset = 0)
        val (page2, _) = ratings.userIdsPendingAssessment(limit = 2, offset = 2)
        val (page3, _) = ratings.userIdsPendingAssessment(limit = 2, offset = 4)

        total shouldBe 5L
        page1 shouldHaveSize 2
        page2 shouldHaveSize 2
        page3 shouldHaveSize 1
        // Pages are disjoint and together cover every pending user (stable order, no gaps/dupes).
        (page1 + page2 + page3).toSet() shouldBe all
    }
}
