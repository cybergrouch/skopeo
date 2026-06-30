// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.capability

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.CapabilityRepository
import org.skopeo.repository.ContactRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime
import java.util.UUID

class CapabilityServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val capabilities = CapabilityRepository()
    private val service = CapabilityService(capabilities = capabilities, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provisionUser(
        uid: String,
        roles: Set<Capability> = setOf(Capability.PLAYER),
    ): User =
        users.provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                capabilities = roles,
            ),
        )

    private fun admin(uid: String = "root"): User = provisionUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    @Test
    fun `only an administrator may use the API`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        service.list(token = token(uid = "player"), userId = player.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .grant(token = token(uid = "player"), userId = player.id, capability = Capability.HOST)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .grant(token = token(uid = "ghost"), userId = player.id, capability = Capability.HOST)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `grant is idempotent and records the granting admin`() {
        val root = admin(uid = "root")
        val player = provisionUser(uid = "player")

        val first = service.grant(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()
        first.created.shouldBeTrue()
        first.grant.capability shouldBe Capability.HOST
        first.grant.grantedBy shouldBe root.id

        val again = service.grant(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()
        again.created.shouldBeFalse()
    }

    @Test
    fun `granting and revoking write audit-log entries (#100)`() {
        val root = admin(uid = "root")
        val player = provisionUser(uid = "player")
        service.grant(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()
        service.revoke(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()

        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.CAPABILITY_GRANTED), limit = 10, offset = 0).let { (items, total) ->
            total shouldBe 1L
            items.single().let {
                it.actorUserId shouldBe root.id
                it.entityId shouldBe player.id
                it.summary shouldBe "Granted HOST role"
                it.details["capability"] shouldBe "HOST"
            }
        }
        audit.list(actions = listOf(element = AuditAction.CAPABILITY_REVOKED), limit = 10, offset = 0).second shouldBe 1L
    }

    @Test
    fun `an administrator can revoke a non-baseline role`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")
        service.grant(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()

        service.revoke(token = token(uid = "root"), userId = player.id, capability = Capability.HOST).shouldBeRight()

        service
            .list(token = token(uid = "root"), userId = player.id)
            .shouldBeRight()
            .none { it.capability == Capability.HOST && it.isActive }
            .shouldBeTrue()
    }

    @Test
    fun `the PLAYER role cannot be revoked`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        service
            .revoke(token = token(uid = "root"), userId = player.id, capability = Capability.PLAYER)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `the last administrator cannot be revoked, but one of several can`() {
        val root = admin(uid = "root")
        val second = provisionUser(uid = "second")
        service.grant(token = token(uid = "root"), userId = second.id, capability = Capability.ADMINISTRATOR).shouldBeRight()

        // Two admins now: root may revoke second's admin.
        service.revoke(token = token(uid = "root"), userId = second.id, capability = Capability.ADMINISTRATOR).shouldBeRight()

        // root is the last admin: revoking it (their own) is blocked as the last-admin case.
        service
            .revoke(token = token(uid = "root"), userId = root.id, capability = Capability.ADMINISTRATOR)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `an administrator cannot revoke their own admin while others remain`() {
        val root = admin(uid = "root")
        provisionUser(uid = "second", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        service
            .revoke(token = token(uid = "root"), userId = root.id, capability = Capability.ADMINISTRATOR)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    /** Provision an ADMINISTRATOR carrying an email contact with the given verification status. */
    private fun adminWithEmail(
        uid: String,
        email: String,
        status: VerificationStatus,
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
                            status = status,
                            isPrimary = true,
                        ),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )

    @Test
    fun `a bootstrap administrator (verified email on the allowlist) cannot be revoked (#194)`() {
        val gated = CapabilityService(capabilities = capabilities, users = users, adminEmails = setOf(element = "boss@skopeo.co"))
        admin(uid = "root") // a separate admin who attempts the revoke (so it isn't the last-admin/self case)
        // Mixed case proves the match is case-insensitive, mirroring the sign-up gate.
        val boss = adminWithEmail(uid = "boss", email = "Boss@Skopeo.co", status = VerificationStatus.VERIFIED)

        gated
            .revoke(token = token(uid = "root"), userId = boss.id, capability = Capability.ADMINISTRATOR)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()

        // The bootstrap admin keeps ADMINISTRATOR.
        gated
            .list(token = token(uid = "root"), userId = boss.id)
            .shouldBeRight()
            .any { it.capability == Capability.ADMINISTRATOR && it.isActive }
            .shouldBeTrue()
    }

    @Test
    fun `an allowlisted but unverified email is not protected (#194)`() {
        val gated = CapabilityService(capabilities = capabilities, users = users, adminEmails = setOf(element = "boss@skopeo.co"))
        admin(uid = "root")
        // Email is on the allowlist but not verified — the verified-email gate means no protection.
        val pending = adminWithEmail(uid = "pending", email = "boss@skopeo.co", status = VerificationStatus.PENDING)

        gated.revoke(token = token(uid = "root"), userId = pending.id, capability = Capability.ADMINISTRATOR).shouldBeRight()
    }

    @Test
    fun `a verified email not on the allowlist is not protected (#194)`() {
        val gated = CapabilityService(capabilities = capabilities, users = users, adminEmails = setOf(element = "boss@skopeo.co"))
        admin(uid = "root")
        // Verified, but the address isn't on the allowlist → an ordinary admin, freely revocable.
        val other = adminWithEmail(uid = "other", email = "someone@elsewhere.com", status = VerificationStatus.VERIFIED)

        gated.revoke(token = token(uid = "root"), userId = other.id, capability = Capability.ADMINISTRATOR).shouldBeRight()
    }

    @Test
    fun `a non-email contact never confers bootstrap protection (#194)`() {
        val gated = CapabilityService(capabilities = capabilities, users = users, adminEmails = setOf(element = "boss@skopeo.co"))
        admin(uid = "root")
        // A verified PHONE (no email) must not match the email allowlist.
        val phoneAdmin =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "phone",
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "phone", isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = "phone")),
                        phone =
                            ContactInfo(
                                type = ContactType.PHONE,
                                value = "+639170000000",
                                source = ContactSource.MANUAL,
                                status = VerificationStatus.VERIFIED,
                                isPrimary = true,
                            ),
                        capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                    ),
            )

        gated.revoke(token = token(uid = "root"), userId = phoneAdmin.id, capability = Capability.ADMINISTRATOR).shouldBeRight()
    }

    @Test
    fun `a disabled allowlisted email no longer confers protection (#194)`() {
        val gated = CapabilityService(capabilities = capabilities, users = users, adminEmails = setOf(element = "boss@skopeo.co"))
        admin(uid = "root")
        val boss = adminWithEmail(uid = "boss", email = "boss@skopeo.co", status = VerificationStatus.VERIFIED)
        // Disable the email contact — an inactive contact must not protect the role.
        val emailContact = boss.contacts.single { it.type == ContactType.EMAIL }
        ContactRepository()
            .setActive(id = emailContact.id, active = false, disabledAt = LocalDateTime.now())
            .shouldBeRight()

        gated.revoke(token = token(uid = "root"), userId = boss.id, capability = Capability.ADMINISTRATOR).shouldBeRight()
    }

    @Test
    fun `revoking a capability not held is not found`() {
        admin(uid = "root")
        val player = provisionUser(uid = "player")

        service
            .revoke(token = token(uid = "root"), userId = player.id, capability = Capability.HOST)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `an unknown user is rejected`() {
        admin(uid = "root")

        service
            .list(token = token(uid = "root"), userId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }
}
