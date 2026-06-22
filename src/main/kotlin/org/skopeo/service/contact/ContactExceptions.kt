package org.skopeo.service.contact

import java.util.UUID

/** No such contact (or it does not belong to the user in the path) — routes map this to 404. */
class ContactNotFoundException(
    id: UUID,
) : RuntimeException("Contact $id not found")

/** A uniqueness rule was violated (one-per-type, or a globally-unique verified value) — maps to 409. */
class ContactConflictException(
    message: String,
) : RuntimeException(message)
