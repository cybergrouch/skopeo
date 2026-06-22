package org.skopeo.service.user

import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.model.Capability
import org.skopeo.model.ProfilePatch
import org.skopeo.model.User
import org.skopeo.repository.UserRepository
import java.util.UUID

/**
 * Orchestrates user provisioning and CRUD, enforcing the self-or-ADMINISTRATOR
 * access policy. All mutations funnel through here (rather than the routes) so a
 * DB audit trail can be layered in later without touching the transport layer.
 */
class UserService(
    private val repository: UserRepository = UserRepository(),
) {
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
