// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.audit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ResourceNotFoundException
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class AuditServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val repo = AuditRepository()
    private val service = AuditService(audit = repo, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability>,
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun seedEntry(actor: UUID) =
        service.record(
            write =
                AuditWrite(
                    actorUserId = actor,
                    action = AuditAction.CAPABILITY_GRANTED,
                    entityType = AuditEntityType.CAPABILITY,
                    entityId = UUID.randomUUID(),
                    summary = "Granted HOST role",
                ),
        )

    @Test
    fun `only an administrator can read the audit log`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "player", roles = setOf(element = Capability.PLAYER))
        seedEntry(actor = admin.id)

        service.list(token = token(uid = "admin"), action = null, limit = 5, offset = 0).let {
            it.total shouldBe 1
            it.items shouldHaveSize 1
        }
        shouldThrow<ForbiddenException> { service.list(token = token(uid = "player"), action = null, limit = 5, offset = 0) }
        shouldThrow<ForbiddenException> { service.list(token = token(uid = "ghost"), action = null, limit = 5, offset = 0) }
    }

    @Test
    fun `an admin sets a comment, blank clears it, and a missing entry is not found`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "player", roles = setOf(element = Capability.PLAYER))
        seedEntry(actor = admin.id)
        val id = service.list(token = token(uid = "admin"), action = null, limit = 1, offset = 0).items.single().id

        service.setComment(token = token(uid = "admin"), id = id, comment = "Looks intentional")
        service.list(token = token(uid = "admin"), action = null, limit = 1, offset = 0).items.single().comment shouldBe "Looks intentional"

        // A blank comment clears the note.
        service.setComment(token = token(uid = "admin"), id = id, comment = "   ")
        service.list(token = token(uid = "admin"), action = null, limit = 1, offset = 0).items.single().comment.shouldBeNull()

        shouldThrow<ForbiddenException> { service.setComment(token = token(uid = "player"), id = id, comment = "x") }
        shouldThrow<ResourceNotFoundException> {
            service.setComment(token = token(uid = "admin"), id = UUID.randomUUID(), comment = "x")
        }
    }
}
