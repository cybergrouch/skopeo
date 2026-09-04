// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.common.dto.user.UserSummaryResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.user.toSummary
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.User
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

/**
 * Duplicate-profile rectification (issue #124) — ADMINISTRATOR-only. One person must have one profile,
 * so an admin designates a canonical ("true") account and marks the others as duplicates: each duplicate
 * is disabled and pointed at the canonical (it drops out of search, and its public profile links to the
 * canonical). Marking retains records, does NOT consolidate ratings, and is audit-logged and reversible.
 * A separate, irreversible "Replace Account" ([replaceAccount]) goes further: it imports a marked
 * duplicate's matches + rating (+ history) into an empty canonical and deletes the old account.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class DuplicateService(
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
    // Only to carry a calibration window across a merge (#881).
    private val ratings: RatingRepository = RatingRepository(),
) {
    /** Mark [duplicateIds] as disabled duplicates of [canonicalId]. */
    fun markDuplicates(
        token: VerifiedFirebaseToken,
        canonicalId: UUID,
        duplicateIds: List<UUID>,
    ): Either<ServiceError, List<UserSummaryResponse>> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val canonical = users.findById(id = canonicalId).bind().toDomain()
            ensure(condition = duplicateIds.isNotEmpty()) { ServiceError.Validation(message = "At least one duplicate is required") }
            ensure(
                condition = duplicateIds.toSet().size == duplicateIds.size,
            ) { ServiceError.Validation(message = "Duplicate ids must be distinct") }
            ensure(
                condition = canonicalId !in duplicateIds,
            ) { ServiceError.Validation(message = "A profile cannot be a duplicate of itself") }
            ensure(condition = canonical.canonicalUserId == null) {
                ServiceError.Conflict(message = "The canonical account is itself a duplicate")
            }
            val targets = duplicateIds.map { resolveMarkable(id = it).bind() }

            users.markDuplicates(canonicalId = canonicalId, duplicateIds = duplicateIds)
            targets.forEach { target ->
                audit.record(
                    write =
                        AuditWrite(
                            actorUserId = adminId,
                            action = AuditAction.USER_MARKED_DUPLICATE,
                            entityType = AuditEntityType.USER,
                            entityId = target.id,
                            summary = "Marked ${target.publicCode} as a duplicate of ${canonical.publicCode}",
                            details =
                                mapOf(
                                    "canonicalUserId" to canonicalId.toString(),
                                    "canonicalPublicCode" to canonical.publicCode,
                                ),
                        ),
                )
            }
            users.findDuplicatesOf(canonicalId = canonicalId).map { it.toDomain() }.map { it.toSummary(isDeleted = it.isDeleted()) }
        }

    /**
     * Replace an old account with its canonical account (#124) — the "Replace Account" operation. Unlike
     * [markDuplicates] — a reversible display-link that keeps both accounts — this **permanently re-points**
     * the old account's matches, current rating, rating history, points, and memberships onto the canonical,
     * then **deletes the old account** (admin soft-delete, #518). The canonical then reads exactly as if it
     * had replaced the old account, with match history and rating history consistent. [duplicateId] must
     * already be marked as a duplicate of [canonicalId] (via [markDuplicates]). Gated to an **empty**
     * canonical (no rating/matches of its own) so the inherited rating is unambiguous. Irreversible; audited.
     * Standings reflect the change on the next standings calculation.
     */
    fun replaceAccount(
        token: VerifiedFirebaseToken,
        canonicalId: UUID,
        duplicateId: UUID,
    ): Either<ServiceError, UserSummaryResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val canonical = users.findById(id = canonicalId).bind().toDomain()
            val duplicate = users.findById(id = duplicateId).bind().toDomain()
            ensure(condition = canonical.canonicalUserId == null) {
                ServiceError.Conflict(message = "The canonical account is itself a duplicate")
            }
            ensure(condition = duplicate.canonicalUserId == canonicalId) {
                ServiceError.Conflict(
                    message = "User ${duplicate.publicCode} is not a duplicate of ${canonical.publicCode}",
                )
            }
            ensure(
                condition = !users.hasRatingHistory(userId = canonicalId) && !users.hasMatchParticipation(userId = canonicalId),
            ) {
                ServiceError.Conflict(
                    message =
                        "${canonical.publicCode} already has its own rating/match history; " +
                            "replace is only supported into an empty account",
                )
            }
            users.replaceAccount(duplicateId = duplicateId, canonicalId = canonicalId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.USER_ACCOUNT_REPLACED,
                        entityType = AuditEntityType.USER,
                        entityId = canonicalId,
                        summary =
                            "Replaced ${duplicate.publicCode} with ${canonical.publicCode} " +
                                "(imported history + rating, deleted the old account)",
                        details =
                            mapOf(
                                "duplicateUserId" to duplicateId.toString(),
                                "duplicatePublicCode" to duplicate.publicCode,
                                "canonicalUserId" to canonicalId.toString(),
                                "canonicalPublicCode" to canonical.publicCode,
                            ),
                    ),
            )
            val updated = users.findById(id = canonicalId).bind().toDomain()
            updated.toSummary(isDeleted = updated.isDeleted())
        }

    /**
     * Generalized admin account-merge (#643) — the "Merge accounts" operation. Consolidate the [retiredAccountId]
     * into the admin-chosen [survivorId], moving **all participation/membership records** (matches, event
     * participants, player-list members, club owners, seeding entries) onto the survivor while the survivor **keeps
     * its own rating + ranking points, with no recompute**. The survivor keeps the best available login: when the
     * retired account has one it is transferred to the survivor (freeing the retired account's login first so the
     * UNIQUE anchors don't collide). The retired account is then retired as a "merged → survivor" card. A required,
     * non-blank [verificationNote] records how the admin confirmed the two accounts are the same person and is kept
     * in the audit trail. Irreversible; audited. Guards: both accounts exist and survivor ≠ retired.
     */
    fun mergeAccounts(
        token: VerifiedFirebaseToken,
        survivorId: UUID,
        retiredAccountId: UUID,
        verificationNote: String,
    ): Either<ServiceError, UserSummaryResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = verificationNote.isNotBlank()) {
                ServiceError.Validation(message = "A verification note is required")
            }
            ensure(condition = survivorId != retiredAccountId) {
                ServiceError.Validation(message = "An account cannot be merged into itself")
            }
            val survivor = users.findById(id = survivorId).bind().toDomain()
            val retired = users.findById(id = retiredAccountId).bind().toDomain()
            // The retired account's login (if any) transfers to the survivor — the survivor keeps the accessible login.
            val transferLogin = retired.firebaseUid != null

            val moved =
                users.mergeAccounts(
                    retiredId = retiredAccountId,
                    survivorId = survivorId,
                    transferLogin = transferLogin,
                )
            // Calibration is inherited, taking the EARLIER designation (#881) — the safer side.
            //
            // The survivor keeps its own rating and points, but a merge moves the retired account's
            // matches onto it, so the survivor's rating is now answerable for play it did not previously
            // own. Inheriting the longer remaining window is the conservative outcome: suppression can
            // only ever withhold a change, never damage a settled rating, whereas dropping a window would
            // start moving opponents' ratings off a rating that is still a guess.
            //
            // Because calibration is derived from the designation timestamp rather than stored (#881),
            // "inherit the longer window" is exactly "keep the earlier timestamp" — no state machine to
            // reconcile, and the rated-match count follows automatically since the matches have moved.
            ratings.inheritEarlierCalibrationStart(survivorId = survivorId, retiredId = retiredAccountId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.USER_ACCOUNTS_MERGED,
                        entityType = AuditEntityType.USER,
                        entityId = survivorId,
                        summary =
                            "Merged ${retired.publicCode} into ${survivor.publicCode} " +
                                "(moved participation/memberships; kept the survivor's rating + points)",
                        details =
                            mapOf(
                                "survivorUserId" to survivorId.toString(),
                                "survivorPublicCode" to survivor.publicCode,
                                "retiredUserId" to retiredAccountId.toString(),
                                "retiredPublicCode" to retired.publicCode,
                                "loginTransferred" to moved.loginTransferred.toString(),
                                "movedTeamMemberships" to moved.teamMemberships.toString(),
                                "movedEventParticipations" to moved.eventParticipations.toString(),
                                "movedPlayerListMemberships" to moved.playerListMemberships.toString(),
                                "movedClubOwnerships" to moved.clubOwnerships.toString(),
                                "movedSeedingEntries" to moved.seedingEntries.toString(),
                                "verificationNote" to verificationNote,
                            ),
                    ),
            )
            val updated = users.findById(id = survivorId).bind().toDomain()
            updated.toSummary(isDeleted = updated.isDeleted())
        }

    /** Reverse a duplicate marking on [id]: reactivate and clear its canonical pointer. */
    fun restore(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val target = users.findById(id = id).bind().toDomain()
            ensure(condition = target.canonicalUserId != null) {
                ServiceError.Conflict(message = "User ${target.publicCode} is not marked as a duplicate")
            }
            users.restoreDuplicate(id = id)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.USER_UNMARKED_DUPLICATE,
                        entityType = AuditEntityType.USER,
                        entityId = target.id,
                        summary = "Restored ${target.publicCode} from duplicate status",
                        details =
                            mapOf(
                                "publicCode" to target.publicCode,
                                "previousCanonicalUserId" to target.canonicalUserId.toString(),
                            ),
                    ),
            )
        }

    /** The disabled duplicates currently pointing at [canonicalId] — for the admin un-mark view. */
    fun duplicatesOf(
        token: VerifiedFirebaseToken,
        canonicalId: UUID,
    ): Either<ServiceError, List<UserSummaryResponse>> =
        either {
            requireAdmin(token = token).bind()
            users.findById(id = canonicalId).bind()
            users.findDuplicatesOf(canonicalId = canonicalId).map { it.toDomain() }.map { it.toSummary(isDeleted = it.isDeleted()) }
        }

    /** A target must exist and not itself already be a canonical for other duplicates. */
    private fun resolveMarkable(id: UUID): Either<ServiceError, User> =
        either {
            val target = users.findById(id = id).bind().toDomain()
            ensure(condition = users.findDuplicatesOf(canonicalId = id).isEmpty()) {
                ServiceError.Conflict(message = "User ${target.publicCode} is itself a canonical account for other duplicates")
            }
            target
        }

    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}
