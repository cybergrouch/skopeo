// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service

/** A requested resource does not exist — routes map this to 404. */
open class ResourceNotFoundException(
    message: String,
) : RuntimeException(message)

/** A uniqueness or state rule was violated — routes map this to 409. */
open class ConflictException(
    message: String,
) : RuntimeException(message)
