package org.skopeo.service.user

import org.skopeo.service.ResourceNotFoundException
import java.util.UUID

/** Target user does not exist — routes map this to 404. */
class UserNotFoundException(
    id: UUID,
) : ResourceNotFoundException("User $id not found")

/** Caller is not allowed to perform the action (e.g. not the target user, not an ADMINISTRATOR) — 403. */
class ForbiddenException(
    message: String = "Access is not permitted",
) : RuntimeException(message)
