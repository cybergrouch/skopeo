// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

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

/**
 * The account behind this sign-in was merged into a canonical profile as a duplicate (#124), so it can
 * no longer be used. Routes map this to 403 and surface [canonicalPublicCode] so the client can link to
 * the true account.
 */
class AccountMergedException(
    val canonicalPublicCode: String?,
) : RuntimeException("This account has been merged into another profile")
