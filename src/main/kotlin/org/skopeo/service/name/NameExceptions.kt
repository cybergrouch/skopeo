package org.skopeo.service.name

import org.skopeo.service.ConflictException
import org.skopeo.service.ResourceNotFoundException
import java.util.UUID

/** No such name (or it does not belong to the user in the path) — routes map this to 404. */
class NameNotFoundException(
    id: UUID,
) : ResourceNotFoundException("Name $id not found")

/** A uniqueness rule was violated (more than one active primary name) — routes map this to 409. */
class NameConflictException(
    message: String,
) : ConflictException(message)
