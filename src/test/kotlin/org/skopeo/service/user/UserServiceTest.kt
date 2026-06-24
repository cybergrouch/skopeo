// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.model.Capability
import org.skopeo.model.ProfilePatch
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class UserServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val repository = UserRepository()
    private val service = UserService(repository = repository)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun token(
        uid: String,
        email: String? = null,
        emailVerified: Boolean = false,
        name: String? = null,
        signInProvider: String = "password",
    ) = VerifiedFirebaseToken(
        uid = uid,
        email = email,
        emailVerified = emailVerified,
        name = name,
        signInProvider = signInProvider,
        providerUid = uid,
    )

    private val request = CreateUserRequest(displayName = "Juan", dateOfBirth = "2000-01-01", sex = "Male")

    @Test
    fun `provision creates a player then is idempotent`() {
        val first =
            service.provision(
                token = token(uid = "u1", email = "u1@example.com", emailVerified = true, name = "U One", signInProvider = "google.com"),
                request = CreateUserRequest(dateOfBirth = "2000-01-01", sex = "Male"),
            )

        first.created.shouldBeTrue()
        first.user.capabilities shouldBe setOf(Capability.PLAYER)
        first.user.names.single().value shouldBe "U One" // token display name fallback
        first.user.contacts.single().status shouldBe VerificationStatus.VERIFIED

        val again = service.provision(token = token(uid = "u1"), request = CreateUserRequest(dateOfBirth = "2000-01-01", sex = "Male"))
        again.created.shouldBeFalse()
        again.user.id shouldBe first.user.id
    }

    @Test
    fun `currentUser is null before provisioning and present after`() {
        service.currentUser(token = token(uid = "ghost")).shouldBeNull()

        val created = service.provision(token = token(uid = "u2"), request = request).user

        service.currentUser(token = token(uid = "u2"))!!.id shouldBe created.id
    }

    @Test
    fun `getById allows self, forbids others, 404s on unknown`() {
        val alice = service.provision(token = token(uid = "alice"), request = request).user
        service.provision(token = token(uid = "bob"), request = request)

        service.getById(token = token(uid = "alice"), id = alice.id).id shouldBe alice.id
        shouldThrow<ForbiddenException> { service.getById(token = token(uid = "bob"), id = alice.id) }
        shouldThrow<UserNotFoundException> { service.getById(token = token(uid = "alice"), id = UUID.randomUUID()) }
    }

    @Test
    fun `an ADMINISTRATOR may access another user`() {
        repository.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "root",
                    identity = UserIdentity(provider = org.skopeo.model.AuthProvider.GOOGLE, providerUid = "root", isPrimary = true),
                    names = listOf(UserName(type = org.skopeo.model.NameType.FIRST, value = "Root")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        val target = service.provision(token = token(uid = "member"), request = request).user

        service.getById(token = token(uid = "root"), id = target.id).id shouldBe target.id
    }

    @Test
    fun `patch updates provided fields, replace clears omitted ones`() {
        val user =
            service.provision(
                token = token(uid = "p1"),
                request = CreateUserRequest(displayName = "Juan", sex = "Male", city = "Manila", dateOfBirth = "2000-01-01"),
            ).user

        val patched = service.patchProfile(token = token(uid = "p1"), id = user.id, patch = ProfilePatch(city = "Cebu"))
        patched.city shouldBe "Cebu"
        patched.sex shouldBe "Male" // untouched by PATCH

        val replaced = service.replaceProfile(token = token(uid = "p1"), id = user.id, patch = ProfilePatch(city = "Davao"))
        replaced.city shouldBe "Davao"
        replaced.sex.shouldBeNull() // cleared by PUT
    }

    @Test
    fun `mutations enforce access and existence`() {
        val user = service.provision(token = token(uid = "owner"), request = request).user

        shouldThrow<ForbiddenException> {
            service.patchProfile(token = token(uid = "intruder"), id = user.id, patch = ProfilePatch(city = "X"))
        }
        shouldThrow<UserNotFoundException> {
            service.patchProfile(token = token(uid = "owner"), id = UUID.randomUUID(), patch = ProfilePatch(city = "X"))
        }
    }

    @Test
    fun `deactivate soft-deletes the caller's own account`() {
        val user = service.provision(token = token(uid = "d1"), request = request).user

        service.deactivate(token = token(uid = "d1"), id = user.id)

        service.getById(token = token(uid = "d1"), id = user.id).isActive.shouldBeFalse()
    }

    @Test
    fun `deactivate forbids others and 404s on unknown`() {
        val user = service.provision(token = token(uid = "d2"), request = request).user
        service.provision(token = token(uid = "stranger"), request = request)

        shouldThrow<ForbiddenException> { service.deactivate(token = token(uid = "stranger"), id = user.id) }
        shouldThrow<UserNotFoundException> { service.deactivate(token = token(uid = "d2"), id = UUID.randomUUID()) }
    }

    @Test
    fun `replaceProfile forbids others and 404s on unknown`() {
        val user = service.provision(token = token(uid = "r1"), request = request).user
        service.provision(token = token(uid = "outsider"), request = request)

        shouldThrow<ForbiddenException> {
            service.replaceProfile(token = token(uid = "outsider"), id = user.id, patch = ProfilePatch(city = "X"))
        }
        shouldThrow<UserNotFoundException> {
            service.replaceProfile(token = token(uid = "r1"), id = UUID.randomUUID(), patch = ProfilePatch(city = "X"))
        }
    }

    @Test
    fun `an unprovisioned caller is forbidden from accessing a profile`() {
        val user = service.provision(token = token(uid = "victim"), request = request).user

        // The caller's token has no user row, so requireAccess sees caller == null (isAdmin == false).
        shouldThrow<ForbiddenException> { service.getById(token = token(uid = "no-such-user"), id = user.id) }
    }
}
