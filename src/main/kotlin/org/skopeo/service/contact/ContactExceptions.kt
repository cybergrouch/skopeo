// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.contact

import org.skopeo.service.ConflictException
import org.skopeo.service.ResourceNotFoundException
import java.util.UUID

/** No such contact (or it does not belong to the user in the path) — routes map this to 404. */
class ContactNotFoundException(
    id: UUID,
) : ResourceNotFoundException("Contact $id not found")

/** A uniqueness rule was violated (one active per type, or a globally-unique verified value) — 409. */
class ContactConflictException(
    message: String,
) : ConflictException(message)
