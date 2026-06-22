package org.skopeo.service.user

import org.skopeo.service.ResourceNotFoundException
import java.util.UUID

/** Target user does not exist — routes map this to 404. */
class UserNotFoundException(
    id: UUID,
) : ResourceNotFoundException("User $id not found")

/** Caller is neither the target user nor an ADMINISTRATOR — routes map this to 403. */
class ForbiddenException : RuntimeException("Access is not permitted")
