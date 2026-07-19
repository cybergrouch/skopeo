// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.CreatePlaceholderCommand
import org.skopeo.model.GeneratedClaimCode
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.displayName
import org.skopeo.repository.PlaceholderClaimCodeRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// Claim-code TTL (#496) — mirrors the 7-day invite expiry cadence (see InviteService.INVITE_TTL_DAYS).
private const val CLAIM_CODE_TTL_DAYS = 7L

private val ALLOWED_SEXES = setOf("Male", "Female")

// Match-management roles that may create a placeholder (no invite gate). ADMINISTRATOR also qualifies.
private val MATCH_MANAGEMENT_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

/**
 * Placeholder ("dummy") player accounts + claim/adopt (#496). A HOST/CLUB_OWNER/ADMINISTRATOR creates a
 * login-less placeholder that matches can be logged against; later the real person adopts it with a
 * secret, backend-generated, expiring, one-time claim code, merging the placeholder's history into their
 * (empty) account via the existing canonical-merge routine. See docs/product/PLACEHOLDER_ACCOUNTS.md.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class PlaceholderService(
    private val users: UserRepository = UserRepository(),
    private val claimCodes: PlaceholderClaimCodeRepository = PlaceholderClaimCodeRepository(),
    private val audit: AuditService = AuditService(),
) {
    /**
     * Create a login-less placeholder player (#496) — HOST/CLUB_OWNER/ADMINISTRATOR, no invite gate.
     * [displayName] and [sex] are required; [dateOfBirth] is optional. The row gets firebase_uid = NULL,
     * placeholder = true, an auto public code, a DISPLAY name, and the PLAYER capability only.
     */
    fun createPlaceholder(
        token: VerifiedFirebaseToken,
        displayName: String,
        sex: String,
        dateOfBirth: LocalDate? = null,
    ): Either<ServiceError, User> =
        either {
            val actorId = requireMatchManager(token = token).bind()
            val name = displayName.trim()
            ensure(condition = name.isNotEmpty()) { ServiceError.Validation(message = "A display name is required") }
            ensure(condition = sex in ALLOWED_SEXES) { ServiceError.Validation(message = "sex must be one of $ALLOWED_SEXES") }
            val created =
                users.createPlaceholder(
                    command = CreatePlaceholderCommand(displayName = name, sex = sex, dateOfBirth = dateOfBirth),
                )
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actorId,
                        action = AuditAction.PLACEHOLDER_CREATED,
                        entityType = AuditEntityType.USER,
                        entityId = created.id,
                        summary = "Created placeholder player ${created.publicCode}",
                        details = mapOf("publicCode" to created.publicCode, "displayName" to name),
                    ),
            )
            created
        }

    /** Unclaimed placeholders (#496), for the admin/host management view. Match-management access. */
    fun listPlaceholders(token: VerifiedFirebaseToken): Either<ServiceError, List<User>> =
        either {
            requireMatchManager(token = token).bind()
            users.listPlaceholders()
        }

    /**
     * Generate a claim code for a placeholder (#496) — ADMINISTRATOR only. The backend generates the
     * random plaintext + a 7-day expiry, stores only the hash, supersedes any prior ACTIVE code, and
     * returns the plaintext once. Valid only for a placeholder = true, unclaimed (active) user.
     */
    fun generateClaimCode(
        token: VerifiedFirebaseToken,
        placeholderId: UUID,
    ): Either<ServiceError, GeneratedClaimCode> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val target = users.findById(id = placeholderId).bind()
            ensure(condition = target.placeholder) { ServiceError.Validation(message = "User ${target.publicCode} is not a placeholder") }
            ensure(condition = target.isActive && target.canonicalUserId == null) {
                ServiceError.Conflict(message = "Placeholder ${target.publicCode} has already been claimed")
            }
            val plaintext = ClaimCodeCrypto.generate()
            val stored =
                claimCodes.issue(
                    placeholderUserId = placeholderId,
                    codeHash = ClaimCodeCrypto.hash(plaintext = plaintext),
                    expiresAt = LocalDateTime.now().plusDays(CLAIM_CODE_TTL_DAYS),
                    createdBy = adminId,
                )
            GeneratedClaimCode(plaintext = plaintext, code = stored, placeholderPublicCode = target.publicCode)
        }

    /**
     * Claim/adopt a placeholder (#496): the authenticated caller pastes a secret code. The claim is
     * admitted only when the code hashes to an ACTIVE, non-expired record, the caller's account is
     * "empty" (no rating history and no match participation), and the caller is not itself a placeholder.
     * On success the placeholder's history merges into the caller's account (canonical-merge routine), the
     * placeholder is retired + linked, the code is consumed, and the claim is audited (target = caller).
     */
    fun claim(
        token: VerifiedFirebaseToken,
        code: String,
    ): Either<ServiceError, User> =
        either {
            val caller =
                ensureNotNull(value = users.findByFirebaseUid(firebaseUid = token.uid)) {
                    ServiceError.Forbidden(message = "You must be signed up to claim an account")
                }
            ensure(condition = !caller.placeholder) { ServiceError.Conflict(message = "A placeholder account cannot claim another") }

            val trimmed = code.trim()
            ensure(condition = trimmed.isNotEmpty()) { ServiceError.Validation(message = "A claim code is required") }
            val record =
                ensureNotNull(value = claimCodes.findActiveByHash(codeHash = ClaimCodeCrypto.hash(plaintext = trimmed))) {
                    ServiceError.NotFound(message = "Invalid or unknown claim code")
                }
            ensure(condition = record.isUsable(asOf = LocalDateTime.now())) {
                ServiceError.Validation(message = "This claim code has expired")
            }

            val placeholder = users.findById(id = record.placeholderUserId).bind()
            // A claimed placeholder carries a canonical link. (No self-claim check is needed: a signed-up
            // caller can never be a placeholder — the !caller.placeholder guard above already rejects that.)
            ensure(condition = placeholder.canonicalUserId == null) {
                ServiceError.Conflict(message = "This placeholder has already been claimed")
            }
            // v1 merge-into-empty only: reject when the caller already has a rating/match history (#496).
            ensure(condition = !users.hasRatingHistory(userId = caller.id) && !users.hasMatchParticipation(userId = caller.id)) {
                ServiceError.Conflict(
                    message = "Your account already has match/rating history; claiming into a non-empty account is not yet supported",
                )
            }

            val now = LocalDateTime.now()
            users.claimPlaceholder(placeholderId = placeholder.id, claimantId = caller.id, claimedAt = now)
            claimCodes.consume(id = record.id, consumedBy = caller.id, consumedAt = now)
            audit.record(
                write =
                    AuditWrite(
                        // The claiming user is the actor and the target (their account absorbs the history).
                        actorUserId = caller.id,
                        action = AuditAction.PLACEHOLDER_CLAIMED,
                        entityType = AuditEntityType.USER,
                        entityId = caller.id,
                        summary = "Claimed placeholder ${placeholder.publicCode} (${placeholder.displayName().orEmpty()})",
                        details =
                            mapOf(
                                "placeholderUserId" to placeholder.id.toString(),
                                "placeholderPublicCode" to placeholder.publicCode,
                            ),
                    ),
            )
            users.findById(id = caller.id).bind()
        }

    /** ADMINISTRATOR-only; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || Capability.ADMINISTRATOR !in caller.capabilities) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }

    /** HOST/CLUB_OWNER/ADMINISTRATOR (match-management); returns the caller's id (the audit actor). */
    private fun requireMatchManager(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || caller.capabilities.none { it in MATCH_MANAGEMENT_ROLES }) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}
