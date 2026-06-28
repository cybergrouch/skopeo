// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import mu.KotlinLogging
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NumericRange
import org.skopeo.model.ProfilePatch
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserRating
import org.skopeo.model.UserSearchQuery
import org.skopeo.model.ageRangeToDob
import org.skopeo.repository.CapabilityRepository
import org.skopeo.repository.InviteRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private val logger = KotlinLogging.logger {}

private val STAFF_ROLES = setOf(Capability.HOST, Capability.ADMINISTRATOR)

/**
 * User-search facets resolved at the route boundary (#116): [sex] is already validated and the
 * [age]/[rating] intervals are already parsed. The service trims the free-text terms and AND-combines.
 */
data class UserSearchFilters(
    val name: String? = null,
    val code: String? = null,
    val q: String? = null,
    val sex: String? = null,
    val age: NumericRange? = null,
    val rating: NumericRange? = null,
)

/**
 * Orchestrates user provisioning and CRUD, enforcing the self-or-ADMINISTRATOR
 * access policy. All mutations funnel through here (rather than the routes) so a
 * DB audit trail can be layered in later without touching the transport layer.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class UserService(
    private val repository: UserRepository = UserRepository(),
    private val capabilities: CapabilityRepository = CapabilityRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val invites: InviteRepository = InviteRepository(),
    private val audit: AuditService = AuditService(),
    // Verified-email allowlist for the ADMINISTRATOR bootstrap (from ADMIN_EMAILS); empty = none.
    private val adminEmails: Set<String> = emptySet(),
) {
    /** Current ratings for the given users, keyed by id — enriches search summaries (issue #64). */
    fun currentRatings(ids: List<UUID>): Map<UUID, UserRating> = ratings.findCurrentRatings(userIds = ids)

    /**
     * Player search backing the player-picker, role-grants, and the Research tab — RESEARCHER or staff
     * (#107). Any combination of facets is allowed and AND-combined; at least one is required.
     * [UserSearchFilters.name] is a fuzzy match; [UserSearchFilters.code] is a shareable player-code
     * prefix (case-insensitive, #86); [UserSearchFilters.q] is the unified picker term matching a
     * fuzzy name OR a code prefix (#86). Sex and the age/rating intervals are validated at the route;
     * age maps to a date-of-birth window, rating filters NTRP.
     */
    fun search(
        token: VerifiedFirebaseToken,
        filters: UserSearchFilters,
    ): Either<ServiceError, List<User>> =
        either {
            requireResearchAccess(repository = repository, token = token).bind()
            val nameTerm = blankToNull(raw = filters.name)
            val codeTerm = blankToNull(raw = filters.code)?.uppercase()
            val qTerm = blankToNull(raw = filters.q)
            ensure(
                condition =
                    nameTerm != null || codeTerm != null || qTerm != null ||
                        filters.sex != null || filters.age != null || filters.rating != null,
            ) {
                ServiceError.Validation(message = "at least one filter (name, code, q, sex, age, rating) is required")
            }
            val dob = filters.age?.let { ageRangeToDob(range = it, today = LocalDate.now()) }
            repository.search(
                query =
                    UserSearchQuery(
                        name = nameTerm,
                        code = codeTerm,
                        q = qTerm,
                        sex = filters.sex,
                        dobMin = dob?.min,
                        dobMax = dob?.max,
                        rating = filters.rating,
                    ),
            )
        }

    /**
     * Resolve known user ids to their profiles — HOST/ADMINISTRATOR only. Used to turn the bare
     * UUIDs the UI holds (match rosters, rating history, calculation previews) into display names.
     * Unknown ids are simply omitted from the result.
     */
    fun findByIds(
        token: VerifiedFirebaseToken,
        ids: List<UUID>,
    ): Either<ServiceError, List<User>> =
        either {
            requireStaff(repository = repository, token = token).bind()
            ensure(condition = ids.isNotEmpty()) { ServiceError.Validation(message = "ids must not be empty") }
            repository.findAllByIds(ids = ids)
        }

    /** Outcome of provisioning: [created] distinguishes a fresh user (201) from an idempotent hit (200). */
    data class Provisioned(
        val user: User,
        val created: Boolean,
    )

    /**
     * The single final step of every sign-up flow. Identity comes from the verified
     * token; re-posting with an already-known uid returns the existing user (idempotent).
     */
    fun provision(
        token: VerifiedFirebaseToken,
        request: CreateUserRequest,
    ): Either<ServiceError, Provisioned> =
        either {
            val existing = repository.findByFirebaseUid(firebaseUid = token.uid)
            if (existing != null) {
                provisionExisting(token = token, existing = existing).bind()
            } else {
                provisionNew(token = token, request = request).bind()
            }
        }

    /** Idempotent re-provision of an already-known uid: reject a merged duplicate, else promote-and-return. */
    private fun provisionExisting(
        token: VerifiedFirebaseToken,
        existing: User,
    ): Either<ServiceError, Provisioned> {
        // A disabled duplicate (#124) cannot sign back in; point them at the canonical account.
        val canonical = existing.canonicalUserId?.let { repository.findById(id = it).getOrNull() }
        if (canonical != null) {
            return ServiceError.AccountMerged(canonicalPublicCode = canonical.publicCode).left()
        }
        val promoted = promoteIfBootstrapAdmin(token = token, user = existing, adminEmails = adminEmails, capabilities = capabilities)
        return Provisioned(user = promoted, created = false).right()
    }

    /** First-time sign-up: enforce the invite gate, write the aggregate, and audit the creation. */
    private fun provisionNew(
        token: VerifiedFirebaseToken,
        request: CreateUserRequest,
    ): Either<ServiceError, Provisioned> =
        either {
            // Manual (password/email-link) sign-ups are invite-only; OAuth is exempt. Returns the gated
            // email so we can mark its invite accepted once the profile is created.
            val invitedEmail = requireInviteForManualSignup(invites = invites, token = token).bind()
            val command = buildProvisionCommand(token = token, request = request, adminEmails = adminEmails)
            val user = repository.provision(command = command)
            if (invitedEmail != null) invites.markAccepted(email = invitedEmail, acceptedAt = LocalDateTime.now())
            audit.record(
                // Self sign-up: the new user is the actor.
                write =
                    AuditWrite(
                        actorUserId = user.id,
                        action = AuditAction.USER_CREATED,
                        entityType = AuditEntityType.USER,
                        entityId = user.id,
                        summary = "Signed up",
                    ),
            )
            Provisioned(user = user, created = true)
        }

    /** The caller's own profile, or null if they have not been provisioned yet. */
    fun currentUser(token: VerifiedFirebaseToken): User? =
        repository.findByFirebaseUid(firebaseUid = token.uid)?.let {
            promoteIfBootstrapAdmin(token = token, user = it, adminEmails = adminEmails, capabilities = capabilities)
        }

    fun getById(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, User> =
        either {
            val target = repository.findById(id = id).bind()
            requireAccess(token = token, target = target).bind()
            target
        }

    fun patchProfile(
        token: VerifiedFirebaseToken,
        id: UUID,
        patch: ProfilePatch,
    ): Either<ServiceError, User> =
        either {
            val target = repository.findById(id = id).bind()
            requireAccess(token = token, target = target).bind()
            repository.updateProfile(id = id, patch = patch).bind()
        }

    fun replaceProfile(
        token: VerifiedFirebaseToken,
        id: UUID,
        patch: ProfilePatch,
    ): Either<ServiceError, User> =
        either {
            val target = repository.findById(id = id).bind()
            requireAccess(token = token, target = target).bind()
            repository.replaceProfile(id = id, patch = patch).bind()
        }

    fun deactivate(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val target = repository.findById(id = id).bind()
            requireAccess(token = token, target = target).bind()
            repository.deactivate(id = id).bind()
        }

    /** Allow only the target user themselves or an ADMINISTRATOR. */
    private fun requireAccess(
        token: VerifiedFirebaseToken,
        target: User,
    ): Either<ServiceError, Unit> {
        val caller = repository.findByFirebaseUid(firebaseUid = token.uid)
        val isSelf = caller?.id == target.id
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (!isSelf && !isAdmin) ServiceError.Forbidden().left() else Unit.right()
    }
}

/** Trim a free-text search term, collapsing a null/blank value to null. */
private fun blankToNull(raw: String?): String? = raw?.trim()?.ifEmpty { null }

/**
 * Idempotently grant ADMINISTRATOR to an already-provisioned user whose verified email is on the
 * bootstrap allowlist — covering emails added to the list after the user first signed up. Grant-only
 * (removing an email never revokes); the grant is recorded with a null grantedBy (a system source).
 * See docs/engineering/architecture/ADMIN_BOOTSTRAP.md.
 */
private fun promoteIfBootstrapAdmin(
    token: VerifiedFirebaseToken,
    user: User,
    adminEmails: Set<String>,
    capabilities: CapabilityRepository,
): User {
    if (Capability.ADMINISTRATOR in user.capabilities || !isBootstrapAdmin(token = token, adminEmails = adminEmails)) {
        return user
    }
    capabilities.grant(userId = user.id, capability = Capability.ADMINISTRATOR)
    logger.info { "Bootstrap allowlist: granted ADMINISTRATOR to user ${user.id}" }
    return user.copy(capabilities = user.capabilities + Capability.ADMINISTRATOR)
}

/** Allow only HOST or ADMINISTRATOR callers. */
private fun requireStaff(
    repository: UserRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, Unit> {
    val caller = repository.findByFirebaseUid(firebaseUid = token.uid)
    return if (caller == null || caller.capabilities.none { it in STAFF_ROLES }) {
        ServiceError.Forbidden().left()
    } else {
        Unit.right()
    }
}

/**
 * Allow player research (#107): a RESEARCHER, or staff (HOST/ADMINISTRATOR) who reach the same search
 * via the player-picker/role-grants. ADMINISTRATOR is staff, so it qualifies even without RESEARCHER.
 */
private fun requireResearchAccess(
    repository: UserRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, Unit> {
    val caller = repository.findByFirebaseUid(firebaseUid = token.uid)
    val allowed =
        caller != null &&
            (caller.capabilities.contains(element = Capability.RESEARCHER) || caller.capabilities.any { it in STAFF_ROLES })
    return if (allowed) Unit.right() else ServiceError.Forbidden().left()
}

/**
 * Enforce invite-only manual onboarding (issue #74): a password/email-link token must have an open
 * invite for its email; OAuth (and the unrealistic no-email password token) is exempt. Returns the
 * gated, normalized email (null when the gate doesn't apply) so the caller can mark it accepted.
 */
private fun requireInviteForManualSignup(
    invites: InviteRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, String?> {
    val isManual = authProviderOf(signInProvider = token.signInProvider) == AuthProvider.PASSWORD
    val email = token.email?.trim()?.lowercase()
    if (!isManual || email == null) return null.right()
    return if (invites.findOpenByEmail(email = email, asOf = LocalDateTime.now()) == null) {
        ServiceError.Forbidden(message = "An invitation is required to register $email").left()
    } else {
        email.right()
    }
}
