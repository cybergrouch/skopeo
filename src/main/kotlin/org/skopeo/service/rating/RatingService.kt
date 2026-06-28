// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.Level
import org.skopeo.model.PendingAssessment
import org.skopeo.model.PendingAssessmentPage
import org.skopeo.model.Rating
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserRating
import org.skopeo.model.ageInYears
import org.skopeo.model.displayName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private val DEFAULT_CONFIDENCE = BigDecimal("0.50")
private const val MAX_PAGE_SIZE = 100

/**
 * Reading ratings/history is self-or-ADMINISTRATOR; setting a rating (the initial
 * assessment, or a later adjustment) and listing users pending assessment are
 * RATER-or-ADMINISTRATOR (ADMINISTRATOR implicitly rates, #106). A user with no rating is
 * "pending assessment" and is ineligible to be entered into a match (enforced by the match flow).
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class RatingService(
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /**
     * A user's ratings plus whether the caller may see the exact value (#114). Players get the band +
     * a normalized band position only; rating managers (ADMINISTRATOR) also get the raw rating.
     */
    data class RatingsView(
        val ratings: List<UserRating>,
        val revealRawValue: Boolean,
    )

    fun getRatings(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): Either<ServiceError, RatingsView> =
        either {
            requireUserExists(userId = userId).bind()
            val caller = requireSelfOrAdmin(token = token, userId = userId).bind()
            val revealRawValue = caller.capabilities.contains(element = Capability.ADMINISTRATOR)
            RatingsView(ratings = ratings.findByUser(userId = userId), revealRawValue = revealRawValue)
        }

    fun getHistory(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): Either<ServiceError, List<RatingHistoryEntry>> =
        either {
            requireUserExists(userId = userId).bind()
            requireSelfOrAdmin(token = token, userId = userId).bind()
            ratings.historyByUser(userId = userId)
        }

    /**
     * Set (or adjust) a user's rating — RATER or ADMINISTRATOR (#106). Computes the published level. The route
     * has already validated the NTRP range and confidence bounds (#116); this trusts them.
     */
    fun setRating(
        token: VerifiedFirebaseToken,
        userId: UUID,
        value: BigDecimal,
        confidence: BigDecimal?,
    ): Either<ServiceError, UserRating> =
        either {
            val adminId = requireRater(token = token).bind()
            requireUserExists(userId = userId).bind()
            val level = Rating.fromValue(value = value.toPlainString()).publishedLevel.value
            val confidenceValue = confidence ?: DEFAULT_CONFIDENCE
            val previous = ratings.findCurrentRating(userId = userId)
            val updated =
                ratings.setRating(
                    userId = userId,
                    rating = value,
                    level = level,
                    confidence = confidenceValue,
                )
            // A manual override of an existing rating is recorded in history for traceability (#96);
            // the initial assessment (no prior rating) is the baseline and writes no history row.
            if (previous != null) {
                ratings.appendHistory(
                    write =
                        RatingHistoryWrite(
                            userId = userId,
                            matchId = null,
                            previousRating = previous.currentRating,
                            newRating = updated.currentRating,
                            ratingChange = updated.currentRating.subtract(previous.currentRating),
                            percentChange = null,
                            previousLevel = previous.currentLevel,
                            newLevel = updated.currentLevel,
                            levelChanged = previous.currentLevel != updated.currentLevel,
                            breakdown = null,
                            calculatedAt = LocalDateTime.now(),
                        ),
                )
            }
            audit.record(write = ratingAudit(actorId = adminId, userId = userId, previous = previous, updated = updated))
            updated
        }

    private fun ratingAudit(
        actorId: UUID,
        userId: UUID,
        previous: UserRating?,
        updated: UserRating,
    ): AuditWrite {
        // Bands derived from the (non-null) rating value — same labels, without a dead null fallback.
        val newBand = Level.fromValue(value = updated.currentRating.toPlainString()).value
        val previousBand = previous?.let { Level.fromValue(value = it.currentRating.toPlainString()).value }
        return AuditWrite(
            actorUserId = actorId,
            action = if (previous == null) AuditAction.RATING_SET else AuditAction.RATING_OVERRIDDEN,
            entityType = AuditEntityType.RATING,
            entityId = userId,
            summary =
                if (previous == null) {
                    "Set rating to $newBand"
                } else {
                    "Overrode rating $previousBand → $newBand"
                },
            details =
                mapOf(
                    "userId" to userId.toString(),
                    "previousRating" to previous?.let { it.currentRating.toPlainString() },
                    "newRating" to updated.currentRating.toPlainString(),
                ),
        )
    }

    fun pendingAssessment(
        token: VerifiedFirebaseToken,
        limit: Int,
        offset: Int,
    ): Either<ServiceError, PendingAssessmentPage> =
        either {
            requireRater(token = token).bind()
            val (ids, total) =
                ratings.userIdsPendingAssessment(
                    limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE),
                    offset = offset.coerceAtLeast(minimumValue = 0),
                )
            val today = LocalDate.now()
            // findAllByIds preserves the id order, so the page order is the repository's stable order.
            val items = users.findAllByIds(ids = ids).map { it.toPendingAssessment(today = today) }
            PendingAssessmentPage(items = items, total = total.toInt())
        }

    private fun User.toPendingAssessment(today: LocalDate): PendingAssessment =
        PendingAssessment(
            userId = id,
            publicCode = publicCode,
            displayName = displayName(),
            photoUrl = photoUrl,
            sex = sex,
            dateOfBirth = dateOfBirth,
            age = dateOfBirth?.let { ageInYears(dateOfBirth = it, asOf = today) },
            // Surface the self-reported value as its published NTRP band (e.g. "4.0"), not the raw decimal.
            proposedRating = proposedRating?.let { Level.fromValue(value = it.toPlainString()).value },
        )

    private fun requireUserExists(userId: UUID): Either<ServiceError, Unit> = users.findById(id = userId).map { }

    /** Self-or-ADMINISTRATOR access; returns the (non-null) caller so callers needn't re-fetch it. */
    private fun requireSelfOrAdmin(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): Either<ServiceError, User> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid) ?: return ServiceError.Forbidden().left()
        val isSelf = caller.id == userId
        val isAdmin = caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (!isSelf && !isAdmin) ServiceError.Forbidden().left() else caller.right()
    }

    /** RATER-or-ADMINISTRATOR access (ADMINISTRATOR implicitly rates); returns the caller's id (the audit actor). */
    private fun requireRater(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid) ?: return ServiceError.Forbidden().left()
        val canRate =
            caller.capabilities.contains(element = Capability.RATER) ||
                caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (canRate) caller.id.right() else ServiceError.Forbidden().left()
    }
}
