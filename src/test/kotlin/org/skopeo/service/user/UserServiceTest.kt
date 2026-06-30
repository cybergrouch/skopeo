// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.model.AuditAction
import org.skopeo.model.Capability
import org.skopeo.model.ProfilePatch
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
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
        picture: String? = null,
    ) = VerifiedFirebaseToken(
        uid = uid,
        email = email,
        emailVerified = emailVerified,
        name = name,
        picture = picture,
        signInProvider = signInProvider,
        providerUid = uid,
    )

    private val request = CreateUserRequest(proposedRating = "4.0", displayName = "Juan", dateOfBirth = "2000-01-01", sex = "Male")

    private val bootstrapService = UserService(repository = repository, adminEmails = setOf(element = "admin@example.com"))

    @Test
    fun `provisioning a new user writes a USER_CREATED audit entry (#100)`() {
        val provisioned = service.provision(token = token(uid = "newbie"), request = request).shouldBeRight()

        AuditRepository().list(actions = listOf(element = AuditAction.USER_CREATED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe provisioned.user.id
            it.entityId shouldBe provisioned.user.id
            it.summary shouldBe "Signed up"
        }
    }

    @Test
    fun `re-provisioning a disabled duplicate is rejected as merged (#124)`() {
        val canonical = service.provision(token = token(uid = "keep"), request = request).shouldBeRight().user
        val dup = service.provision(token = token(uid = "dup"), request = request).shouldBeRight().user
        repository.markDuplicates(canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))

        val merged =
            service
                .provision(token = token(uid = "dup"), request = request)
                .shouldBeLeft()
                .shouldBeInstanceOf<ServiceError.AccountMerged>()
        merged.canonicalPublicCode shouldBe canonical.publicCode
    }

    @Test
    fun `currentRatings returns the current rating per user, omitting the unrated`() {
        val rated = service.provision(token = token(uid = "r"), request = request).shouldBeRight().user
        val unrated = service.provision(token = token(uid = "u"), request = request).shouldBeRight().user
        RatingRepository().setRating(userId = rated.id, rating = BigDecimal("4.0"), level = "4.0", confidence = BigDecimal("0.50"))

        val map = service.currentRatings(ids = listOf(rated.id, unrated.id))

        map.keys shouldBe setOf(element = rated.id)
        map[rated.id]?.currentLevel shouldBe "4.0"
    }

    @Test
    fun `a manual sign-up requires an open invite, which is marked accepted on success`() {
        invite(email = "invitee@example.com")

        service.provision(token = token(uid = "inv", email = "invitee@example.com"), request = request).shouldBeRight()

        // The invite is consumed (no longer open) once the profile is provisioned.
        invites.findOpenByEmail(email = "invitee@example.com", asOf = LocalDateTime.now()).shouldBeNull()
    }

    @Test
    fun `a manual sign-up without an invite is forbidden`() {
        service
            .provision(token = token(uid = "x", email = "noinvite@example.com"), request = request)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an expired invite does not admit a manual sign-up`() {
        invites.createOrRotate(email = "exp@example.com", invitedBy = null, expiresAt = LocalDateTime.now().minusDays(1))
        service
            .provision(token = token(uid = "e", email = "exp@example.com"), request = request)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an OAuth sign-up does not require an invite`() {
        val user =
            service.provision(
                token = token(uid = "g", email = "g@example.com", emailVerified = true, signInProvider = "google.com"),
                request = request,
            ).shouldBeRight().user
        user.capabilities shouldBe setOf(Capability.PLAYER, Capability.RESEARCHER)
    }

    @Test
    fun `provision creates a player then is idempotent`() {
        val first =
            service.provision(
                token = token(uid = "u1", email = "u1@example.com", emailVerified = true, name = "U One", signInProvider = "google.com"),
                request = CreateUserRequest(proposedRating = "4.0", dateOfBirth = "2000-01-01", sex = "Male"),
            ).shouldBeRight()

        first.created.shouldBeTrue()
        first.user.capabilities shouldBe setOf(Capability.PLAYER, Capability.RESEARCHER)
        first.user.names.single().value shouldBe "U One" // token display name fallback
        first.user.contacts.single().status shouldBe VerificationStatus.VERIFIED

        val again =
            service
                .provision(
                    token = token(uid = "u1"),
                    request = CreateUserRequest(proposedRating = "4.0", dateOfBirth = "2000-01-01", sex = "Male"),
                )
                .shouldBeRight()
        again.created.shouldBeFalse()
        again.user.id shouldBe first.user.id
    }

    @Test
    fun `currentUser is null before provisioning and present after`() {
        service.currentUser(token = token(uid = "ghost")).shouldBeNull()

        val created = service.provision(token = token(uid = "u2"), request = request).shouldBeRight().user

        service.currentUser(token = token(uid = "u2"))!!.id shouldBe created.id
    }

    @Test
    fun `currentUser refreshes the stored photo from the provider when it changed (#219)`() {
        service.provision(token = token(uid = "u3", picture = "https://p/old.jpg"), request = request).shouldBeRight()

        // A later login carrying a new provider picture updates the stored value...
        service.currentUser(token = token(uid = "u3", picture = "https://p/new.jpg"))!!.photoUrl shouldBe "https://p/new.jpg"
        repository.findByFirebaseUid(firebaseUid = "u3")!!.photoUrl shouldBe "https://p/new.jpg" // persisted

        // ...but an absent picture on a later login never wipes the stored value.
        service.currentUser(token = token(uid = "u3", picture = null))!!.photoUrl shouldBe "https://p/new.jpg"
    }

    @Test
    fun `getById allows self, forbids others, 404s on unknown`() {
        val alice = service.provision(token = token(uid = "alice"), request = request).shouldBeRight().user
        service.provision(token = token(uid = "bob"), request = request).shouldBeRight()

        service.getById(token = token(uid = "alice"), id = alice.id).shouldBeRight().id shouldBe alice.id
        service.getById(token = token(uid = "bob"), id = alice.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.getById(token = token(uid = "alice"), id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
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
        val target = service.provision(token = token(uid = "member"), request = request).shouldBeRight().user

        service.getById(token = token(uid = "root"), id = target.id).shouldBeRight().id shouldBe target.id
    }

    @Test
    fun `patch updates provided fields, replace clears omitted ones`() {
        val user =
            service.provision(
                token = token(uid = "p1"),
                request =
                    CreateUserRequest(
                        proposedRating = "4.0",
                        displayName = "Juan",
                        sex = "Male",
                        city = "Manila",
                        dateOfBirth = "2000-01-01",
                    ),
            ).shouldBeRight().user

        val patched = service.patchProfile(token = token(uid = "p1"), id = user.id, patch = ProfilePatch(city = "Cebu")).shouldBeRight()
        patched.city shouldBe "Cebu"
        patched.sex shouldBe "Male" // untouched by PATCH

        val replaced =
            service.replaceProfile(token = token(uid = "p1"), id = user.id, patch = ProfilePatch(city = "Davao")).shouldBeRight()
        replaced.city shouldBe "Davao"
        replaced.sex.shouldBeNull() // cleared by PUT
    }

    @Test
    fun `mutations enforce access and existence`() {
        val user = service.provision(token = token(uid = "owner"), request = request).shouldBeRight().user

        service
            .patchProfile(token = token(uid = "intruder"), id = user.id, patch = ProfilePatch(city = "X"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .patchProfile(token = token(uid = "owner"), id = UUID.randomUUID(), patch = ProfilePatch(city = "X"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `deactivate soft-deletes the caller's own account`() {
        val user = service.provision(token = token(uid = "d1"), request = request).shouldBeRight().user

        service.deactivate(token = token(uid = "d1"), id = user.id).shouldBeRight()

        service.getById(token = token(uid = "d1"), id = user.id).shouldBeRight().isActive.shouldBeFalse()
    }

    @Test
    fun `deactivate forbids others and 404s on unknown`() {
        val user = service.provision(token = token(uid = "d2"), request = request).shouldBeRight().user
        service.provision(token = token(uid = "stranger"), request = request).shouldBeRight()

        service.deactivate(token = token(uid = "stranger"), id = user.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.deactivate(token = token(uid = "d2"), id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `replaceProfile forbids others and 404s on unknown`() {
        val user = service.provision(token = token(uid = "r1"), request = request).shouldBeRight().user
        service.provision(token = token(uid = "outsider"), request = request).shouldBeRight()

        service
            .replaceProfile(token = token(uid = "outsider"), id = user.id, patch = ProfilePatch(city = "X"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .replaceProfile(token = token(uid = "r1"), id = UUID.randomUUID(), patch = ProfilePatch(city = "X"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `an unprovisioned caller is forbidden from accessing a profile`() {
        val user = service.provision(token = token(uid = "victim"), request = request).shouldBeRight().user

        // The caller's token has no user row, so requireAccess sees caller == null (isAdmin == false).
        service.getById(token = token(uid = "no-such-user"), id = user.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `provision bootstraps a verified allowlisted email to ADMINISTRATOR`() {
        val result =
            bootstrapService.provision(
                token =
                    token(uid = "boss", email = "admin@example.com", emailVerified = true, name = "Boss", signInProvider = "google.com"),
                request = CreateUserRequest(proposedRating = "4.0", dateOfBirth = "2000-01-01", sex = "Male"),
            ).shouldBeRight()

        result.user.capabilities shouldBe setOf(Capability.PLAYER, Capability.RESEARCHER, Capability.ADMINISTRATOR)
    }

    @Test
    fun `currentUser promotes a user later added to the allowlist, idempotently`() {
        // Signed up before being allowlisted (unverified at provision -> plain PLAYER).
        invite(email = "admin@example.com")
        val created =
            bootstrapService.provision(
                token = token(uid = "later", email = "admin@example.com", emailVerified = false, name = "Later"),
                request = CreateUserRequest(proposedRating = "4.0", dateOfBirth = "2000-01-01", sex = "Male"),
            ).shouldBeRight()
        created.user.capabilities shouldBe setOf(Capability.PLAYER, Capability.RESEARCHER)

        // Logs in with a verified email -> promoted.
        bootstrapService.currentUser(token = token(uid = "later", email = "admin@example.com", emailVerified = true))!!
            .capabilities shouldBe setOf(Capability.PLAYER, Capability.RESEARCHER, Capability.ADMINISTRATOR)

        // A second login does not double-grant.
        bootstrapService.currentUser(token = token(uid = "later", email = "admin@example.com", emailVerified = true))!!
            .capabilities shouldBe setOf(Capability.PLAYER, Capability.RESEARCHER, Capability.ADMINISTRATOR)
        repository.findByFirebaseUid(firebaseUid = "later")!!
            .capabilities shouldBe setOf(Capability.PLAYER, Capability.RESEARCHER, Capability.ADMINISTRATOR)
    }

    @Test
    fun `currentUser does not promote an UNVERIFIED allowlisted email (verified-email gate)`() {
        invite(email = "admin@example.com")
        bootstrapService.provision(
            token = token(uid = "unv", email = "admin@example.com", emailVerified = false, name = "Unv"),
            request = CreateUserRequest(proposedRating = "4.0", dateOfBirth = "2000-01-01", sex = "Male"),
        ).shouldBeRight()

        bootstrapService.currentUser(token = token(uid = "unv", email = "admin@example.com", emailVerified = false))!!
            .capabilities shouldBe setOf(Capability.PLAYER, Capability.RESEARCHER)
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
        val member = service.provision(token = token(uid = "m1"), request = request).shouldBeRight().user

        member.publicCode.length shouldBe 6

        val found =
            service.search(token = token(uid = "staff"), filters = UserSearchFilters(code = member.publicCode.lowercase())).shouldBeRight()
        found.single().id shouldBe member.id
    }

    @Test
    fun `a RATER can search, a plain player cannot (#205)`() {
        repository.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "rater",
                    identity =
                        UserIdentity(provider = org.skopeo.model.AuthProvider.GOOGLE, providerUid = "rater", isPrimary = true),
                    names = listOf(element = UserName(type = org.skopeo.model.NameType.DISPLAY, value = "Rater")),
                    capabilities = setOf(Capability.PLAYER, Capability.RATER),
                ),
        )
        repository.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "plain",
                    identity =
                        UserIdentity(provider = org.skopeo.model.AuthProvider.GOOGLE, providerUid = "plain", isPrimary = true),
                    names = listOf(element = UserName(type = org.skopeo.model.NameType.DISPLAY, value = "Plain")),
                    capabilities = setOf(Capability.PLAYER),
                ),
        )
        val member = service.provision(token = token(uid = "m9"), request = request).shouldBeRight().user

        service.search(token = token(uid = "rater"), filters = UserSearchFilters(code = member.publicCode)).shouldBeRight()
        service
            .search(token = token(uid = "plain"), filters = UserSearchFilters(code = member.publicCode))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
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
        val member = service.provision(token = token(uid = "m2"), request = request).shouldBeRight().user

        // A short prefix (here 3 of 6 chars), lowercased, surfaces the member incrementally —
        // an exact match would return nothing for a partial code.
        val prefix = member.publicCode.take(n = 3).lowercase()
        val found = service.search(token = token(uid = "staff3"), filters = UserSearchFilters(code = prefix)).shouldBeRight()
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
        val member = service.provision(token = token(uid = "m3"), request = request).shouldBeRight().user // display name "Juan"

        val byName = service.search(token = token(uid = "staff4"), filters = UserSearchFilters(q = "jua")).shouldBeRight()
        byName.map { it.id } shouldContain member.id

        val byCodePrefix =
            service.search(
                token = token(uid = "staff4"),
                filters = UserSearchFilters(q = member.publicCode.take(n = 3).lowercase()),
            ).shouldBeRight()
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
        service
            .search(token = token(uid = "staff2"), filters = UserSearchFilters(code = "   "))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `search is allowed for a RESEARCHER but forbidden without RESEARCHER or staff (#107)`() {
        // A plain RESEARCHER (the default for every sign-up) may run player research.
        service.provision(token = token(uid = "res"), request = request).shouldBeRight()
        service
            .search(token = token(uid = "res"), filters = UserSearchFilters(name = "Juan"))
            .shouldBeRight()

        // A user with neither RESEARCHER nor a staff role, and an unprovisioned caller, are forbidden.
        repository.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "plain",
                    identity =
                        UserIdentity(provider = org.skopeo.model.AuthProvider.GOOGLE, providerUid = "plain", isPrimary = true),
                    names = listOf(element = UserName(type = org.skopeo.model.NameType.DISPLAY, value = "Plain")),
                    capabilities = setOf(element = Capability.PLAYER),
                ),
        )
        service
            .search(token = token(uid = "plain"), filters = UserSearchFilters(name = "Juan"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .search(token = token(uid = "ghost"), filters = UserSearchFilters(name = "Juan"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
