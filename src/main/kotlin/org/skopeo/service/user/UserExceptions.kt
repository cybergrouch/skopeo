package org.skopeo.service.user

import java.util.UUID

/** Target user does not exist — routes map this to 404. */
class UserNotFoundException(
    id: UUID,
) : RuntimeException("User $id not found")

/** Caller is neither the target user nor an ADMINISTRATOR — routes map this to 403. */
class ForbiddenException : RuntimeException("Access to this user is not permitted")
