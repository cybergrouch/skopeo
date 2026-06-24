// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.name

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.name.NameCreateRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.NameRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.UserNotFoundException
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class NameServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val nameRepo = NameRepository()
    private val service = NameService(names = nameRepo, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    /** Provision a user with one DISPLAY name (as the API would). */
    private fun provisionUser(
        uid: String,
        capabilities: Set<Capability> = setOf(Capability.PLAYER),
    ): User =
        users.provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = "Display $uid")),
                capabilities = capabilities,
            ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun nickname(value: String) = NameCreateRequest(type = "NICKNAME", value = value)

    private fun displayNameOf(
        uid: String,
        userId: UUID,
    ) = service.list(token = token(uid = uid), userId = userId).single { it.type == NameType.DISPLAY && it.isActive }

    @Test
    fun `multiple names of the same type are allowed`() {
        val owner = provisionUser(uid = "owner")
        service.create(token = token(uid = "owner"), userId = owner.id, request = nickname(value = "JB"))
        service.create(token = token(uid = "owner"), userId = owner.id, request = nickname(value = "Boy"))

        service.list(token = token(uid = "owner"), userId = owner.id).count { it.type == NameType.NICKNAME } shouldBe 2
    }

    @Test
    fun `posting a new display name replaces the previous one`() {
        val owner = provisionUser(uid = "owner")
        val original = displayNameOf(uid = "owner", userId = owner.id)

        val replacement =
            service.create(
                token = token(uid = "owner"),
                userId = owner.id,
                request = NameCreateRequest(type = "DISPLAY", value = "Johnny"),
            )

        replacement.value shouldBe "Johnny"
        // exactly one active display, and the old one is retained as disabled history
        displayNameOf(uid = "owner", userId = owner.id).id shouldBe replacement.id
        service.get(token = token(uid = "owner"), userId = owner.id, nameId = original.id).isActive.shouldBeFalse()
    }

    @Test
    fun `the display name cannot be disabled`() {
        val owner = provisionUser(uid = "owner")
        val display = displayNameOf(uid = "owner", userId = owner.id)

        shouldThrow<IllegalArgumentException> {
            service.setActive(token = token(uid = "owner"), userId = owner.id, nameId = display.id, active = false)
        }
    }

    @Test
    fun `re-enabling a former display name conflicts with the current one`() {
        val owner = provisionUser(uid = "owner")
        val original = displayNameOf(uid = "owner", userId = owner.id)
        service.create(token = token(uid = "owner"), userId = owner.id, request = NameCreateRequest(type = "DISPLAY", value = "Johnny"))

        // `original` is now disabled history; re-enabling it collides with the active display.
        shouldThrow<NameConflictException> {
            service.setActive(token = token(uid = "owner"), userId = owner.id, nameId = original.id, active = true)
        }
    }

    @Test
    fun `owner can disable a non-display name`() {
        val owner = provisionUser(uid = "owner")
        val name = service.create(token = token(uid = "owner"), userId = owner.id, request = nickname(value = "JB"))

        service.setActive(token = token(uid = "owner"), userId = owner.id, nameId = name.id, active = false).isActive.shouldBeFalse()
    }

    @Test
    fun `access is restricted to the owner or an administrator`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "intruder")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val name = service.create(token = token(uid = "owner"), userId = owner.id, request = nickname(value = "JB"))

        shouldThrow<ForbiddenException> { service.list(token = token(uid = "intruder"), userId = owner.id) }
        shouldThrow<ForbiddenException> { service.list(token = token(uid = "ghost"), userId = owner.id) }
        service.get(token = token(uid = "root"), userId = owner.id, nameId = name.id).id shouldBe name.id
    }

    @Test
    fun `unknown or mismatched names are not found`() {
        val owner = provisionUser(uid = "owner")
        val other = provisionUser(uid = "other")
        val name = service.create(token = token(uid = "other"), userId = other.id, request = nickname(value = "JB"))

        shouldThrow<NameNotFoundException> {
            service.get(token = token(uid = "owner"), userId = owner.id, nameId = UUID.randomUUID())
        }
        shouldThrow<NameNotFoundException> {
            service.get(token = token(uid = "owner"), userId = owner.id, nameId = name.id)
        }
    }

    @Test
    fun `listing names for a non-existent user is not found`() {
        provisionUser(uid = "owner")

        shouldThrow<UserNotFoundException> {
            service.list(token = token(uid = "owner"), userId = UUID.randomUUID())
        }
    }

    @Test
    fun `creating a name for a non-existent user is not found`() {
        provisionUser(uid = "owner")

        shouldThrow<UserNotFoundException> {
            service.create(token = token(uid = "owner"), userId = UUID.randomUUID(), request = nickname(value = "JB"))
        }
    }

    @Test
    fun `an administrator can list another user's names`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.create(token = token(uid = "owner"), userId = owner.id, request = nickname(value = "JB"))

        service.list(token = token(uid = "root"), userId = owner.id).count { it.type == NameType.NICKNAME } shouldBe 1
    }

    @Test
    fun `invalid name type is rejected`() {
        val owner = provisionUser(uid = "owner")

        shouldThrow<IllegalArgumentException> {
            service.create(token = token(uid = "owner"), userId = owner.id, request = NameCreateRequest(type = "ALIAS", value = "x"))
        }
    }
}
