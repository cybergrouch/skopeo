// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.model.Capability
import org.skopeo.model.NumericRange
import org.skopeo.model.ProfilePatch
import org.skopeo.model.User
import org.skopeo.model.UserSearchQuery
import org.skopeo.model.ageRangeToDob
import org.skopeo.repository.UserRepository
import java.time.LocalDate
import java.util.UUID

private val STAFF_ROLES = setOf(Capability.HOST, Capability.ADMINISTRATOR)

/**
 * Orchestrates user provisioning and CRUD, enforcing the self-or-ADMINISTRATOR
 * access policy. All mutations funnel through here (rather than the routes) so a
 * DB audit trail can be layered in later without touching the transport layer.
 */
class UserService(
    private val repository: UserRepository = UserRepository(),
) {
    /**
     * Staff search (HOST/ADMINISTRATOR) backing the player-picker, role-grants, and player
     * research. Any combination of facets is allowed and AND-combined; at least one is required.
     * [name] is a fuzzy match; [sex] is Male/Female; [age]/[rating] are interval strings
     * ("[3.0,4.0)", "(20,30]") — age maps to a date-of-birth window, rating filters NTRP.
     */
    fun search(
        token: VerifiedFirebaseToken,
        name: String?,
        sex: String?,
        age: String?,
        rating: String?,
    ): List<User> {
        requireStaff(token)
        val nameTerm = name?.trim()?.takeIf { it.isNotEmpty() }
        val sexValue = validatedSex(sex)
        val ageRange = age?.let { NumericRange.parse(it) }
        val ratingRange = rating?.let { NumericRange.parse(it) }
        require(nameTerm != null || sexValue != null || ageRange != null || ratingRange != null) {
            "at least one filter (name, sex, age, rating) is required"
        }
        val dob = ageRange?.let { ageRangeToDob(it, LocalDate.now()) }
        return repository.search(
            UserSearchQuery(
                name = nameTerm,
                sex = sexValue,
                dobMin = dob?.min,
                dobMax = dob?.max,
                rating = ratingRange,
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
    ): List<User> {
        requireStaff(token)
        require(ids.isNotEmpty()) { "ids must not be empty" }
        return repository.findAllByIds(ids)
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
    ): Provisioned {
        repository.findByFirebaseUid(token.uid)?.let { return Provisioned(user = it, created = false) }
        val command = buildProvisionCommand(token = token, request = request)
        return Provisioned(user = repository.provision(command), created = true)
    }

    /** The caller's own profile, or null if they have not been provisioned yet. */
    fun currentUser(token: VerifiedFirebaseToken): User? = repository.findByFirebaseUid(token.uid)

    fun getById(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): User {
        val target = repository.findById(id) ?: throw UserNotFoundException(id)
        requireAccess(token = token, target = target)
        return target
    }

    fun patchProfile(
        token: VerifiedFirebaseToken,
        id: UUID,
        patch: ProfilePatch,
    ): User {
        val target = repository.findById(id) ?: throw UserNotFoundException(id)
        requireAccess(token = token, target = target)
        return repository.updateProfile(id = id, patch = patch) ?: throw UserNotFoundException(id)
    }

    fun replaceProfile(
        token: VerifiedFirebaseToken,
        id: UUID,
        patch: ProfilePatch,
    ): User {
        val target = repository.findById(id) ?: throw UserNotFoundException(id)
        requireAccess(token = token, target = target)
        return repository.replaceProfile(id = id, patch = patch) ?: throw UserNotFoundException(id)
    }

    fun deactivate(
        token: VerifiedFirebaseToken,
        id: UUID,
    ) {
        val target = repository.findById(id) ?: throw UserNotFoundException(id)
        requireAccess(token = token, target = target)
        repository.deactivate(id)
    }

    /** Allow only HOST or ADMINISTRATOR callers. */
    private fun requireStaff(token: VerifiedFirebaseToken) {
        val caller = repository.findByFirebaseUid(token.uid)
        if (caller == null || caller.capabilities.none { it in STAFF_ROLES }) throw ForbiddenException()
    }

    /** Allow only the target user themselves or an ADMINISTRATOR. */
    private fun requireAccess(
        token: VerifiedFirebaseToken,
        target: User,
    ) {
        val caller = repository.findByFirebaseUid(token.uid)
        val isSelf = caller?.id == target.id
        val isAdmin = caller?.capabilities?.contains(Capability.ADMINISTRATOR) == true
        if (!isSelf && !isAdmin) throw ForbiddenException()
    }
}
