// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.rating

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import org.skopeo.common.dto.rating.PlayerCalibrationPageResponse
import org.skopeo.common.dto.rating.PlayerCalibrationResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.RATING_ROLES
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.CalibrationOverride
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.domain.service.user.requireAnyOf
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.repository.persistence.UserRatingEntity
import java.time.LocalDateTime
import java.util.UUID

/** Longest reason accepted; long enough for a sentence of context, short enough to read in a list. */
private const val MAX_REASON_LENGTH = 500

/** Page-size bounds for the Ratings tab's calibration list. */
private const val MAX_PAGE_SIZE = 100

/**
 * The per-player calibration override (#1126): who may change it, the reason it requires, the audit it
 * writes, and the Ratings tab's list of players in calibration.
 *
 * It stores a decision and nothing else. Whether a player is calibrating is still answered only by
 * [CalibrationService], which reads the override first; this service never computes a verdict of its own.
 *
 * Gated on `RATING_ROLES` (ADMINISTRATOR, RATER, HOST, CLUB_OWNER), the same people who may assign a
 * rating (#907): whoever is trusted to make the guess is trusted to say whether it is still a guess.
 */
class CalibrationOverrideService(
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
    private val calibration: CalibrationService = CalibrationService(),
    private val audit: AuditService = AuditService(),
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    /**
     * Set a player's override to [override] (AUTOMATIC, FORCED_OFF or FORCED_ON) for [reason].
     *
     * A player with no rating cannot have one — there is nothing to calibrate — and gets a
     * [ServiceError.Conflict]. Re-setting the same value is allowed and recorded: a new reason is new
     * information. Takes effect from the next rating run and the next points award; nothing already rated
     * or awarded changes.
     */
    fun setOverride(
        token: VerifiedFirebaseToken,
        userId: UUID,
        override: String,
        reason: String,
    ): Either<ServiceError, PlayerCalibrationResponse> =
        either {
            val actorId = requireAnyOf(users = users, token = token, allowed = RATING_ROLES).bind()
            val wanted =
                ensureNotNull(value = CalibrationOverride.entries.firstOrNull { it.name == override }) {
                    ServiceError.Validation(
                        message = "Unknown calibration override '$override'; expected one of ${CalibrationOverride.entries.joinToString()}",
                    )
                }
            val trimmed = reason.trim()
            ensure(condition = trimmed.isNotEmpty()) { ServiceError.Validation(message = "A reason is required") }
            ensure(condition = trimmed.length <= MAX_REASON_LENGTH) {
                ServiceError.Validation(message = "The reason must be at most $MAX_REASON_LENGTH characters")
            }
            val player = users.findById(id = userId).bind().toDomain()
            ensureNotNull(value = ratings.findCurrentRating(userId = userId)) {
                ServiceError.Conflict(message = "Player ${player.publicCode} has no rating, so there is no calibration to override")
            }
            val before = calibration.statusFor(userId = userId)

            ratings.setCalibrationOverride(userId = userId, override = wanted.name, reason = trimmed, setBy = actorId, setAt = clock())
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actorId,
                        action = AuditAction.CALIBRATION_OVERRIDE_CHANGED,
                        entityType = AuditEntityType.RATING,
                        entityId = userId,
                        summary =
                            "Set calibration for ${player.publicCode} to ${wanted.label()} " +
                                "(was ${before.override.label()}): $trimmed",
                        details =
                            mapOf(
                                "userId" to userId.toString(),
                                "previousOverride" to before.override.name,
                                "override" to wanted.name,
                                "reason" to trimmed,
                                // The count and N at the moment of the decision, so a later reader can tell
                                // what the switch overrode.
                                "matchesRated" to before.matchesRated.toString(),
                                "matchesRequired" to before.matchesRequired.toString(),
                            ),
                    ),
            )
            val row = checkNotNull(value = ratings.findCurrentRating(userId = userId))
            describe(rows = listOf(element = row), required = calibration.requiredMatches()).single()
        }

    /**
     * The Ratings tab's "Players in calibration" card: everyone calibrating, plus those forced out when
     * [includeForcedOff], fewest rated matches first. Same SQL as the Research tab's calibration filter.
     */
    fun listCalibrations(
        token: VerifiedFirebaseToken,
        includeForcedOff: Boolean,
        limit: Int,
        offset: Int,
    ): Either<ServiceError, PlayerCalibrationPageResponse> =
        either {
            requireAnyOf(users = users, token = token, allowed = RATING_ROLES).bind()
            val required = calibration.requiredMatches()
            val (rows, total) =
                ratings.listCalibrations(
                    required = required,
                    includeForcedOff = includeForcedOff,
                    limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE),
                    offset = offset.coerceAtLeast(minimumValue = 0),
                )
            PlayerCalibrationPageResponse(items = describe(rows = rows, required = required), total = total.toInt())
        }

    // Each row as staff see it, with the players and the people who set their overrides loaded in one go.
    // Private, so no service signature exposes a persistence entity (LayeredArchitectureTest, #1029).
    private fun describe(
        rows: List<UserRatingEntity>,
        required: Int,
    ): List<PlayerCalibrationResponse> {
        val ids = (rows.map { it.userId } + rows.mapNotNull { it.calibrationOverrideBy }).distinct()
        val people = users.findAllByIds(ids = ids).map { it.toDomain() }.associateBy { it.id }
        return rows.map { row ->
            val status =
                calibration.verdict(
                    override = CalibrationOverride.valueOf(value = row.calibrationOverride),
                    calibrationStartedAt = row.calibrationStartedAt,
                    matchesRated = row.calibrationMatchesRated,
                    required = required,
                )
            val player = people[row.userId]
            val setter = row.calibrationOverrideBy?.let { people[it] }
            PlayerCalibrationResponse(
                userId = row.userId.toString(),
                publicCode = player?.publicCode.orEmpty(),
                displayName = player?.displayName(),
                level = row.currentLevel,
                inCalibration = status.inCalibration,
                matchesRated = status.matchesRated,
                matchesRequired = status.matchesRequired,
                override = status.override.name,
                overrideReason = row.calibrationOverrideReason,
                overrideByName = setter?.displayName(),
                overrideByPublicCode = setter?.publicCode,
                overrideAt = row.calibrationOverrideAt?.toString(),
            )
        }
    }
}

/** How an override reads in an audit summary: "Forced off", not "FORCED_OFF". */
private fun CalibrationOverride.label(): String =
    when (this) {
        CalibrationOverride.AUTOMATIC -> "Automatic"
        CalibrationOverride.FORCED_OFF -> "Forced off"
        CalibrationOverride.FORCED_ON -> "Forced on"
    }
