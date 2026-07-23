// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

/**
 * The typed-error taxonomy returned (as the Arrow `Either` left) by repositories and services for
 * **expected** failures (issue #115). Truly exceptional/programmer errors (DB/IO faults, bugs) are still
 * thrown and surface as a 500. The route layer is the single place that maps each variant to an HTTP
 * status (see `routes/RouteSupport.kt`); keeping [ServiceError] HTTP-free preserves the layering.
 *
 * It lives in the pure `model` layer (not `service`) so the foundational `repository` layer can return
 * it without depending on `service` — the dependency direction enforced by `LayeredArchitectureTest`.
 */
sealed interface ServiceError {
    /** Human-readable detail, surfaced in the error response body. */
    val message: String

    /** A requested resource does not exist — mapped to 404. */
    data class NotFound(
        override val message: String,
    ) : ServiceError

    /** A uniqueness or state rule was violated — mapped to 409. */
    data class Conflict(
        override val message: String,
    ) : ServiceError

    /** The caller is not allowed to perform the action — mapped to 403. */
    data class Forbidden(
        override val message: String = "Access is not permitted",
    ) : ServiceError

    /** A request value failed a business rule at the service boundary — mapped to 400. */
    data class Validation(
        override val message: String,
    ) : ServiceError

    /**
     * The account behind this sign-in was merged into a canonical profile as a duplicate (#124), so it
     * can no longer be used — mapped to 403, surfacing [canonicalPublicCode] so the client can link to
     * the true account.
     */
    data class AccountMerged(
        val canonicalPublicCode: String?,
    ) : ServiceError {
        override val message: String = "This account has been merged into another profile"
    }

    /**
     * The account behind this sign-in was soft-deleted by an administrator (#518) — `is_active = false`
     * with no canonical pointer (distinguishing it from a merged duplicate, [AccountMerged]). It can no
     * longer sign in until an admin re-allows login; mapped to 403.
     */
    data object AccountDeleted : ServiceError {
        override val message: String = "This account has been deleted. Contact an administrator to restore access."
    }
}
