// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.invite

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.ContactInfo
import org.skopeo.model.ContactSource
import org.skopeo.model.ContactType
import org.skopeo.model.InviteStatus
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.InviteRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class InviteServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val invites = InviteRepository()
    private val service = InviteService(invites = invites, users = users)

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

    /** Provision an account that owns a verified EMAIL contact — the existing-account guard input (#132). */
    private fun provisionWithEmail(
        uid: String,
        email: String,
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    email =
                        ContactInfo(
                            type = ContactType.EMAIL,
                            value = email,
                            source = ContactSource.GOOGLE,
                            status = VerificationStatus.VERIFIED,
                            method = VerificationMethod.OAUTH_PROVIDER,
                            isPrimary = true,
                        ),
                ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    @Test
    fun `an admin creates an invite (pending, attributed) and re-inviting rotates it`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        // The route normalizes the email before the service sees it (#116); here it arrives normalized.
        val invite = service.create(token = token(uid = "admin"), email = "new@example.com").shouldBeRight()
        invite.email shouldBe "new@example.com"
        invite.status shouldBe InviteStatus.PENDING
        invite.invitedBy shouldBe admin.id

        service.create(token = token(uid = "admin"), email = "new@example.com").shouldBeRight() // resend → rotate
        service.list(token = token(uid = "admin"), limit = 50, offset = 0).shouldBeRight().items shouldHaveSize 1
    }

    @Test
    fun `inviting an email that already belongs to an active account is rejected (#132)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provisionWithEmail(uid = "member", email = "taken@example.com")

        service
            .create(token = token(uid = "admin"), email = "taken@example.com")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()

        // Nothing was recorded for the blocked address.
        service.list(token = token(uid = "admin"), limit = 50, offset = 0).shouldBeRight().items shouldHaveSize 0
    }

    @Test
    fun `the existing-account guard matches case- and whitespace-insensitively (#132)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provisionWithEmail(uid = "member", email = "Mixed.Case@Example.com")

        // The route normalizes to lower-case/trimmed before the service; the stored value keeps its casing.
        service
            .create(token = token(uid = "admin"), email = "mixed.case@example.com")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `a disabled account does not block re-inviting its email (#132)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val member = provisionWithEmail(uid = "member", email = "gone@example.com")
        users.deactivate(id = member.id)

        service.create(token = token(uid = "admin"), email = "gone@example.com").shouldBeRight()
    }

    @Test
    fun `creating and revoking an invite write audit-log entries (#100)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val invite = service.create(token = token(uid = "admin"), email = "newbie@example.com").shouldBeRight()
        service.revoke(token = token(uid = "admin"), id = invite.id).shouldBeRight()

        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.INVITE_CREATED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe admin.id
            it.entityId shouldBe invite.id
            it.summary shouldBe "Invited newbie@example.com"
            it.details["email"] shouldBe "newbie@example.com"
        }
        audit.list(actions = listOf(element = AuditAction.INVITE_REVOKED), limit = 10, offset = 0).second shouldBe 1L
    }

    @Test
    fun `a non-admin cannot create, list, or revoke invites`() {
        provision(uid = "player", roles = setOf(element = Capability.PLAYER))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val invite = service.create(token = token(uid = "admin"), email = "x@example.com").shouldBeRight()

        service.create(token = token(uid = "player"), email = "y@example.com").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "player"), limit = 50, offset = 0).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.revoke(token = token(uid = "player"), id = invite.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a caller without a provisioned account cannot create, list, or revoke invites`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val invite = service.create(token = token(uid = "admin"), email = "x@example.com").shouldBeRight()

        // The token's uid maps to no user (caller == null) -> the admin gate denies before any work.
        service.create(token = token(uid = "ghost"), email = "y@example.com").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "ghost"), limit = 50, offset = 0).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.revoke(token = token(uid = "ghost"), id = invite.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `revoke removes the invite from the open set, and an unknown id is a not-found`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val invite = service.create(token = token(uid = "admin"), email = "z@example.com").shouldBeRight()

        service.revoke(token = token(uid = "admin"), id = invite.id).shouldBeRight()
        invites.findOpenByEmail(email = "z@example.com", asOf = java.time.LocalDateTime.now()) shouldBe null

        service
            .revoke(token = token(uid = "admin"), id = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `list scopes to a status filter when given`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.create(token = token(uid = "admin"), email = "pending@example.com").shouldBeRight()
        val accepted = service.create(token = token(uid = "admin"), email = "acc@example.com").shouldBeRight()
        invites.markAccepted(email = accepted.email, acceptedAt = java.time.LocalDateTime.now())

        val pending =
            service.list(token = token(uid = "admin"), limit = 50, offset = 0, status = InviteStatus.PENDING).shouldBeRight()
        pending.items.single().email shouldBe "pending@example.com"
        pending.total shouldBe 1

        val all = service.list(token = token(uid = "admin"), limit = 50, offset = 0).shouldBeRight()
        all.total shouldBe 2
    }
}
