// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.audit

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditCategory
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.UserRepository
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

        service.list(token = token(uid = "admin"), category = null, limit = 5, offset = 0).shouldBeRight().let {
            it.total shouldBe 1
            it.items shouldHaveSize 1
        }
        service
            .list(token = token(uid = "player"), category = null, limit = 5, offset = 0)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .list(token = token(uid = "ghost"), category = null, limit = 5, offset = 0)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `list scopes by category and resolves actor and target names`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val target = provision(uid = "target", roles = setOf(element = Capability.PLAYER))
        // A capability change (entityId is a user) and an invite (entityId is NOT a user).
        service.record(
            write =
                AuditWrite(
                    actorUserId = admin.id,
                    action = AuditAction.CAPABILITY_GRANTED,
                    entityType = AuditEntityType.CAPABILITY,
                    entityId = target.id,
                    summary = "Granted HOST role",
                ),
        )
        service.record(
            write =
                AuditWrite(
                    actorUserId = admin.id,
                    action = AuditAction.INVITE_CREATED,
                    entityType = AuditEntityType.INVITE,
                    entityId = UUID.randomUUID(),
                    summary = "Invited x@y.dev",
                ),
        )

        val caps =
            service.list(token = token(uid = "admin"), category = AuditCategory.CAPABILITY_CHANGE, limit = 10, offset = 0).shouldBeRight()
        caps.total shouldBe 1
        caps.items.single().let {
            it.entry.action shouldBe AuditAction.CAPABILITY_GRANTED
            it.actor.shouldNotBeNull()
            it.actor.displayName shouldBe "admin"
            it.target.shouldNotBeNull()
            it.target.userId shouldBe target.id // CAPABILITY entityId resolves to a user
            it.target.displayName shouldBe "target"
        }

        // An invite's entityId isn't a user → no resolved target.
        service.list(token = token(uid = "admin"), category = AuditCategory.INVITE, limit = 10, offset = 0)
            .shouldBeRight().items.single().target.shouldBeNull()

        // A category with no events wired yet returns nothing.
        service.list(token = token(uid = "admin"), category = AuditCategory.NAME_CHANGE, limit = 10, offset = 0)
            .shouldBeRight().total shouldBe 0
    }

    @Test
    fun `an admin sets a comment, blank clears it, and a missing entry is not found`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "player", roles = setOf(element = Capability.PLAYER))
        seedEntry(actor = admin.id)
        val id =
            service.list(token = token(uid = "admin"), category = null, limit = 1, offset = 0).shouldBeRight().items.single().entry.id

        service.setComment(token = token(uid = "admin"), id = id, comment = "Looks intentional").shouldBeRight()
        service.list(token = token(uid = "admin"), category = null, limit = 1, offset = 0)
            .shouldBeRight().items.single().entry.comment shouldBe "Looks intentional"

        // A blank comment clears the note; a null comment likewise leaves none.
        service.setComment(token = token(uid = "admin"), id = id, comment = "   ").shouldBeRight()
        service.list(token = token(uid = "admin"), category = null, limit = 1, offset = 0)
            .shouldBeRight().items.single().entry.comment.shouldBeNull()
        service.setComment(token = token(uid = "admin"), id = id, comment = null).shouldBeRight()
        service.list(token = token(uid = "admin"), category = null, limit = 1, offset = 0)
            .shouldBeRight().items.single().entry.comment.shouldBeNull()

        service
            .setComment(token = token(uid = "player"), id = id, comment = "x")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .setComment(token = token(uid = "admin"), id = UUID.randomUUID(), comment = "x")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `list tolerates a system actor and an entity without a user id`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        // A system action (null actor) and a RATING entry whose entityId is absent.
        service.record(
            write =
                AuditWrite(
                    actorUserId = null,
                    action = AuditAction.INVITE_REVOKED,
                    entityType = AuditEntityType.INVITE,
                    entityId = UUID.randomUUID(),
                    summary = "Revoked invite x@y.dev",
                ),
        )
        service.record(
            write =
                AuditWrite(
                    actorUserId = null,
                    action = AuditAction.RATING_SET,
                    entityType = AuditEntityType.RATING,
                    entityId = null,
                    summary = "Set rating to 4.0",
                ),
        )

        val views = service.list(token = token(uid = "admin"), category = null, limit = 10, offset = 0).shouldBeRight()
        views.items.all { it.actor == null }.shouldBeTrue() // null actor → no resolved actor
        // A RATING entry with no entityId resolves to no target.
        views.items.first { it.entry.action == AuditAction.RATING_SET }.target.shouldBeNull()
    }
}
