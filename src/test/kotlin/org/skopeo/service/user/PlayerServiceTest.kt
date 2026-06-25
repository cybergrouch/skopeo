// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ResourceNotFoundException
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal

class PlayerServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val service = PlayerService(users = users, ratings = ratings)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(
        uid: String,
        names: List<UserName>,
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = names,
                ),
        )

    @Test
    fun `resolves a public profile by code (case-insensitive) with the active display name and rating`() {
        // Two names so the DISPLAY filter is exercised against a non-display name too.
        val user =
            newUser(
                uid = "p1",
                names =
                    listOf(
                        UserName(type = NameType.FIRST, value = "Ignore"),
                        UserName(type = NameType.DISPLAY, value = "Ana"),
                    ),
            )
        ratings.setRating(userId = user.id, rating = BigDecimal("4.0"), level = "4.0", confidence = BigDecimal("0.50"))

        val profile = service.publicProfile(code = user.publicCode.lowercase())

        profile.publicCode shouldBe user.publicCode
        profile.displayName shouldBe "Ana"
        profile.rating?.level shouldBe "4.0"
    }

    @Test
    fun `omits the rating when the player has none`() {
        val user = newUser(uid = "p2", names = listOf(element = UserName(type = NameType.DISPLAY, value = "Bea")))

        service.publicProfile(code = user.publicCode).rating.shouldBeNull()
    }

    @Test
    fun `an unknown code is a not-found`() {
        shouldThrow<ResourceNotFoundException> { service.publicProfile(code = "ZZZZZZ") }
    }

    @Test
    fun `an inactive player is a not-found`() {
        val user = newUser(uid = "p3", names = listOf(element = UserName(type = NameType.DISPLAY, value = "Cy")))
        users.deactivate(id = user.id)

        shouldThrow<ResourceNotFoundException> { service.publicProfile(code = user.publicCode) }
    }
}
