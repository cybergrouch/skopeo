// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.model.AuditAction
import org.skopeo.model.Capability
import org.skopeo.model.ProfilePatch
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.InviteRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDateTime
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
    private val invites = InviteRepository()
    private val service = UserService(repository = repository)

    /** Seed an open invite so a manual (password/email-link) sign-up for [email] is admitted (#74). */
    private fun invite(email: String) = invites.createOrRotate(email = email, invitedBy = null, expiresAt = LocalDateTime.now().plusDays(7))

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

    private val bootstrapService = UserService(repository = repository, adminEmails = setOf(element = "admin@example.com"))

    @Test
    fun `provisioning a new user writes a USER_CREATED audit entry (#100)`() {
        val provisioned = service.provision(token = token(uid = "newbie"), request = request)

        AuditRepository().list(actions = listOf(element = AuditAction.USER_CREATED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe provisioned.user.id
            it.entityId shouldBe provisioned.user.id
            it.summary shouldBe "Signed up"
        }
    }

    @Test
    fun `re-provisioning a disabled duplicate is rejected as merged (#124)`() {
        val canonical = service.provision(token = token(uid = "keep"), request = request).user
        val dup = service.provision(token = token(uid = "dup"), request = request).user
        repository.markDuplicates(canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))

        val merged =
            shouldThrow<AccountMergedException> {
                service.provision(token = token(uid = "dup"), request = request)
            }
        merged.canonicalPublicCode shouldBe canonical.publicCode
    }

    @Test
    fun `currentRatings returns the current rating per user, omitting the unrated`() {
        val rated = service.provision(token = token(uid = "r"), request = request).user
        val unrated = service.provision(token = token(uid = "u"), request = request).user
        RatingRepository().setRating(userId = rated.id, rating = BigDecimal("4.0"), level = "4.0", confidence = BigDecimal("0.50"))

        val map = service.currentRatings(ids = listOf(rated.id, unrated.id))

        map.keys shouldBe setOf(element = rated.id)
        map[rated.id]?.currentLevel shouldBe "4.0"
    }

    @Test
    fun `a manual sign-up requires an open invite, which is marked accepted on success`() {
        invite(email = "invitee@example.com")

        service.provision(token = token(uid = "inv", email = "invitee@example.com"), request = request)

        // The invite is consumed (no longer open) once the profile is provisioned.
        invites.findOpenByEmail(email = "invitee@example.com", asOf = LocalDateTime.now()).shouldBeNull()
    }

    @Test
    fun `a manual sign-up without an invite is forbidden`() {
        shouldThrow<ForbiddenException> {
            service.provision(token = token(uid = "x", email = "noinvite@example.com"), request = request)
        }
    }

    @Test
    fun `an expired invite does not admit a manual sign-up`() {
        invites.createOrRotate(email = "exp@example.com", invitedBy = null, expiresAt = LocalDateTime.now().minusDays(1))
        shouldThrow<ForbiddenException> {
            service.provision(token = token(uid = "e", email = "exp@example.com"), request = request)
        }
    }

    @Test
    fun `an OAuth sign-up does not require an invite`() {
        val user =
            service.provision(
                token = token(uid = "g", email = "g@example.com", emailVerified = true, signInProvider = "google.com"),
                request = request,
            ).user
        user.capabilities shouldBe setOf(element = Capability.PLAYER)
    }

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

    @Test
    fun `provision bootstraps a verified allowlisted email to ADMINISTRATOR`() {
        val result =
            bootstrapService.provision(
                token =
                    token(uid = "boss", email = "admin@example.com", emailVerified = true, name = "Boss", signInProvider = "google.com"),
                request = CreateUserRequest(dateOfBirth = "2000-01-01", sex = "Male"),
            )

        result.user.capabilities shouldBe setOf(Capability.PLAYER, Capability.ADMINISTRATOR)
    }

    @Test
    fun `currentUser promotes a user later added to the allowlist, idempotently`() {
        // Signed up before being allowlisted (unverified at provision -> plain PLAYER).
        invite(email = "admin@example.com")
        val created =
            bootstrapService.provision(
                token = token(uid = "later", email = "admin@example.com", emailVerified = false, name = "Later"),
                request = CreateUserRequest(dateOfBirth = "2000-01-01", sex = "Male"),
            )
        created.user.capabilities shouldBe setOf(Capability.PLAYER)

        // Logs in with a verified email -> promoted.
        bootstrapService.currentUser(token = token(uid = "later", email = "admin@example.com", emailVerified = true))!!
            .capabilities shouldBe setOf(Capability.PLAYER, Capability.ADMINISTRATOR)

        // A second login does not double-grant.
        bootstrapService.currentUser(token = token(uid = "later", email = "admin@example.com", emailVerified = true))!!
            .capabilities shouldBe setOf(Capability.PLAYER, Capability.ADMINISTRATOR)
        repository.findByFirebaseUid(firebaseUid = "later")!!
            .capabilities shouldBe setOf(Capability.PLAYER, Capability.ADMINISTRATOR)
    }

    @Test
    fun `currentUser does not promote an UNVERIFIED allowlisted email (verified-email gate)`() {
        invite(email = "admin@example.com")
        bootstrapService.provision(
            token = token(uid = "unv", email = "admin@example.com", emailVerified = false, name = "Unv"),
            request = CreateUserRequest(dateOfBirth = "2000-01-01", sex = "Male"),
        )

        bootstrapService.currentUser(token = token(uid = "unv", email = "admin@example.com", emailVerified = false))!!
            .capabilities shouldBe setOf(Capability.PLAYER)
    }

    @Test
    fun `provision assigns a short public code, and search finds a user by it (case-insensitive)`() {
        // A staff caller is required for search.
        repository.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "staff",
                    identity =
                        UserIdentity(provider = org.skopeo.model.AuthProvider.GOOGLE, providerUid = "staff", isPrimary = true),
                    names = listOf(element = UserName(type = org.skopeo.model.NameType.DISPLAY, value = "Staff")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        val member = service.provision(token = token(uid = "m1"), request = request).user

        member.publicCode.length shouldBe 6

        val found =
            service.search(token = token(uid = "staff"), filters = UserSearchFilters(code = member.publicCode.lowercase()))
        found.single().id shouldBe member.id
    }

    @Test
    fun `search matches a partial code as a case-insensitive prefix`() {
        repository.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "staff3",
                    identity =
                        UserIdentity(provider = org.skopeo.model.AuthProvider.GOOGLE, providerUid = "staff3", isPrimary = true),
                    names = listOf(element = UserName(type = org.skopeo.model.NameType.DISPLAY, value = "Staff3")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        val member = service.provision(token = token(uid = "m2"), request = request).user

        // A short prefix (here 3 of 6 chars), lowercased, surfaces the member incrementally —
        // an exact match would return nothing for a partial code.
        val prefix = member.publicCode.take(n = 3).lowercase()
        val found = service.search(token = token(uid = "staff3"), filters = UserSearchFilters(code = prefix))
        found.map { it.id } shouldContain member.id
    }

    @Test
    fun `the unified q term matches a fuzzy name or a code prefix`() {
        repository.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "staff4",
                    identity =
                        UserIdentity(provider = org.skopeo.model.AuthProvider.GOOGLE, providerUid = "staff4", isPrimary = true),
                    names = listOf(element = UserName(type = org.skopeo.model.NameType.DISPLAY, value = "Staff4")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        val member = service.provision(token = token(uid = "m3"), request = request).user // display name "Juan"

        val byName = service.search(token = token(uid = "staff4"), filters = UserSearchFilters(q = "jua"))
        byName.map { it.id } shouldContain member.id

        val byCodePrefix =
            service.search(
                token = token(uid = "staff4"),
                filters = UserSearchFilters(q = member.publicCode.take(n = 3).lowercase()),
            )
        byCodePrefix.map { it.id } shouldContain member.id
    }

    @Test
    fun `search treats a blank code as no filter and rejects an all-empty query`() {
        repository.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "staff2",
                    identity =
                        UserIdentity(provider = org.skopeo.model.AuthProvider.GOOGLE, providerUid = "staff2", isPrimary = true),
                    names = listOf(element = UserName(type = org.skopeo.model.NameType.DISPLAY, value = "Staff2")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )

        // "   " trims to empty -> treated as no code, so with no other facet the search is rejected.
        shouldThrow<IllegalArgumentException> {
            service.search(token = token(uid = "staff2"), filters = UserSearchFilters(code = "   "))
        }
    }
}
