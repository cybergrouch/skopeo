// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.name

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.NameRepository
import org.skopeo.repository.UserRepository
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

    private fun displayNameOf(
        uid: String,
        userId: UUID,
    ) = service.list(token = token(uid = uid), userId = userId).shouldBeRight().single { it.type == NameType.DISPLAY && it.isActive }

    @Test
    fun `adding and disabling a name write audit-log entries (#100)`() {
        val owner = provisionUser(uid = "owner")
        val added =
            service.create(token = token(uid = "owner"), userId = owner.id, type = NameType.NICKNAME, value = "JB").shouldBeRight()
        service.setActive(token = token(uid = "owner"), userId = owner.id, nameId = added.id, active = false).shouldBeRight()
        service.setActive(token = token(uid = "owner"), userId = owner.id, nameId = added.id, active = true).shouldBeRight()

        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.NAME_ADDED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe owner.id
            it.entityId shouldBe owner.id
            it.summary shouldBe "Added NICKNAME name 'JB'"
        }
        // Both the disable and the re-enable are recorded.
        val updates =
            audit.list(actions = listOf(element = AuditAction.NAME_UPDATED), limit = 10, offset = 0).first.map { it.summary }
        updates.toSet() shouldBe setOf("Disabled NICKNAME name 'JB'", "Enabled NICKNAME name 'JB'")
    }

    @Test
    fun `multiple names of the same type are allowed`() {
        val owner = provisionUser(uid = "owner")
        service.create(token = token(uid = "owner"), userId = owner.id, type = NameType.NICKNAME, value = "JB").shouldBeRight()
        service.create(token = token(uid = "owner"), userId = owner.id, type = NameType.NICKNAME, value = "Boy").shouldBeRight()

        service.list(token = token(uid = "owner"), userId = owner.id).shouldBeRight().count { it.type == NameType.NICKNAME } shouldBe 2
    }

    @Test
    fun `posting a new display name replaces the previous one`() {
        val owner = provisionUser(uid = "owner")
        val original = displayNameOf(uid = "owner", userId = owner.id)

        val replacement =
            service
                .create(
                    token = token(uid = "owner"),
                    userId = owner.id,
                    type = NameType.DISPLAY,
                    value = "Johnny",
                ).shouldBeRight()

        replacement.value shouldBe "Johnny"
        // exactly one active display, and the old one is retained as disabled history
        displayNameOf(uid = "owner", userId = owner.id).id shouldBe replacement.id
        service.get(token = token(uid = "owner"), userId = owner.id, nameId = original.id).shouldBeRight().isActive.shouldBeFalse()
    }

    @Test
    fun `the display name cannot be disabled`() {
        val owner = provisionUser(uid = "owner")
        val display = displayNameOf(uid = "owner", userId = owner.id)

        service
            .setActive(token = token(uid = "owner"), userId = owner.id, nameId = display.id, active = false)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `re-enabling a former display name conflicts with the current one`() {
        val owner = provisionUser(uid = "owner")
        val original = displayNameOf(uid = "owner", userId = owner.id)
        service.create(token = token(uid = "owner"), userId = owner.id, type = NameType.DISPLAY, value = "Johnny").shouldBeRight()

        // `original` is now disabled history; re-enabling it collides with the active display.
        service
            .setActive(token = token(uid = "owner"), userId = owner.id, nameId = original.id, active = true)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `owner can disable a non-display name`() {
        val owner = provisionUser(uid = "owner")
        val name =
            service.create(token = token(uid = "owner"), userId = owner.id, type = NameType.NICKNAME, value = "JB").shouldBeRight()

        service
            .setActive(token = token(uid = "owner"), userId = owner.id, nameId = name.id, active = false)
            .shouldBeRight()
            .isActive
            .shouldBeFalse()
    }

    @Test
    fun `access is restricted to the owner or an administrator`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "intruder")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val name =
            service.create(token = token(uid = "owner"), userId = owner.id, type = NameType.NICKNAME, value = "JB").shouldBeRight()

        service.list(token = token(uid = "intruder"), userId = owner.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "ghost"), userId = owner.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.get(token = token(uid = "root"), userId = owner.id, nameId = name.id).shouldBeRight().id shouldBe name.id
    }

    @Test
    fun `unknown or mismatched names are not found`() {
        val owner = provisionUser(uid = "owner")
        val other = provisionUser(uid = "other")
        val name =
            service.create(token = token(uid = "other"), userId = other.id, type = NameType.NICKNAME, value = "JB").shouldBeRight()

        service
            .get(token = token(uid = "owner"), userId = owner.id, nameId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        service
            .get(token = token(uid = "owner"), userId = owner.id, nameId = name.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `listing names for a non-existent user is not found`() {
        provisionUser(uid = "owner")

        service
            .list(token = token(uid = "owner"), userId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `creating a name for a non-existent user is not found`() {
        provisionUser(uid = "owner")

        service
            .create(token = token(uid = "owner"), userId = UUID.randomUUID(), type = NameType.NICKNAME, value = "JB")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `an administrator can list another user's names`() {
        val owner = provisionUser(uid = "owner")
        provisionUser(uid = "root", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.create(token = token(uid = "owner"), userId = owner.id, type = NameType.NICKNAME, value = "JB").shouldBeRight()

        service.list(token = token(uid = "root"), userId = owner.id).shouldBeRight().count { it.type == NameType.NICKNAME } shouldBe 1
    }
}
