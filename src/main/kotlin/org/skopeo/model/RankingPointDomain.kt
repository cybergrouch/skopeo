// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.Period
import java.util.UUID

// Validity horizons (in months) per point class (#146, §4.2). Named to avoid magic numbers.
private const val ONE_MONTH = 1
private const val THREE_MONTHS = 3
private const val SIX_MONTHS = 6
private const val TWELVE_MONTHS = 12

/**
 * The class of a ranking-point award (#146). Each class carries a default validity horizon (§4.2 of
 * the phase-2 design): the window over which the points count toward a player's standing before they
 * expire. The default is a starting point — a specific award may override [valid_from]/[valid_until]
 * (e.g. a tournament that sets its own window). Kept in code (enum + policy map) rather than a table
 * until admins need to tune it without a deploy.
 */
enum class PointClass(
    /** The default validity horizon for an award of this class, added to `valid_from`. */
    val defaultValidity: Period,
) {
    /** Annual tournament — bridges to next year's edition. */
    ANNUAL_TOURNAMENT(defaultValidity = Period.ofMonths(TWELVE_MONTHS)),

    /** Short seasonal tournament (e.g. a one-month event). */
    SEASONAL_TOURNAMENT_1M(defaultValidity = Period.ofMonths(ONE_MONTH)),

    /** Seasonal tournament with a three-month horizon. */
    SEASONAL_TOURNAMENT_3M(defaultValidity = Period.ofMonths(THREE_MONTHS)),

    /** Seasonal tournament with a six-month horizon. */
    SEASONAL_TOURNAMENT_6M(defaultValidity = Period.ofMonths(SIX_MONTHS)),

    /** Open-play points — short-lived, pegged to dominance/match-up when auto-awarded later. */
    OPEN_PLAY(defaultValidity = Period.ofMonths(ONE_MONTH)),

    /** External / ad-hoc grant — validity is set per grant; this is the fallback default. */
    EXTERNAL(defaultValidity = Period.ofMonths(TWELVE_MONTHS)),
    ;

    /** The default `valid_until` for an award of this class starting at [from]. */
    fun defaultValidUntil(from: LocalDateTime): LocalDateTime = from.plus(defaultValidity)
}

/** Whether an award is currently counted ([ACTIVE]) or has been revoked ([REVOKED]). */
enum class AwardStatus { ACTIVE, REVOKED }

/** Where an award originated: a system-tracked source ([INTERNAL]) or outside the ecosystem ([EXTERNAL]). */
enum class PointSourceType { INTERNAL, EXTERNAL }

/**
 * One row of the append-only ranking-points ledger (#146, §4.1). [points] is a [BigDecimal] to allow
 * fractional open-play awards later. [band] and [sex] are recorded at award time (decision B) so each
 * (band, sex) runs its own race even after a player's rating moves them. A revocation is itself a
 * ledger row: it carries [revokesAwardId] pointing at the original and (by convention) zero points.
 */
data class RankingPointAward(
    val id: UUID,
    val userId: UUID,
    val points: BigDecimal,
    val pointClass: PointClass,
    val sourceType: PointSourceType,
    val sourceId: String?,
    val band: String,
    val sex: String,
    val reason: String?,
    val validFrom: LocalDateTime,
    val validUntil: LocalDateTime,
    val status: AwardStatus,
    val revokesAwardId: UUID?,
    val grantedBy: UUID?,
    val awardedAt: LocalDateTime,
    // The event that produced this award on finalize (#403 Phase D); null for manual / external grants.
    val eventId: UUID? = null,
    // The specific match (fixture) that granted this award on finalize (#448); null for manual grants
    // and pre-V19 awards — the profile points audit then falls back to linking the [eventId].
    val matchId: UUID? = null,
) {
    /** Whether this award is active and its validity window contains [asOf] — i.e. it counts then. */
    fun countsAsOf(asOf: LocalDateTime): Boolean = status == AwardStatus.ACTIVE && !asOf.isBefore(validFrom) && asOf.isBefore(validUntil)
}

/**
 * A request to grant a ranking-point award (#146). [band] and [validUntil] are optional overrides:
 * when null the service fills [band] from the target's current rating and [validUntil] from the
 * [pointClass] policy. [sex] is always taken from the target user (not client-supplied).
 */
data class GrantRankingPointCommand(
    val userId: UUID,
    val points: BigDecimal,
    val pointClass: PointClass,
    val sourceType: PointSourceType,
    val sourceId: String?,
    val band: String?,
    val reason: String?,
    val validFrom: LocalDateTime?,
    val validUntil: LocalDateTime?,
)

/**
 * A manual, signed ranking-point adjustment (#469): an admin awards (+) or deducts (−) points for a
 * player from the Manage Player section. Unlike [GrantRankingPointCommand] the [points] are **signed**
 * (positive awards, negative deducts; non-zero, whole), the [reason] is **always required**, and the
 * validity window [validFrom]/[validUntil] is **explicit** (no point-class default). It is persisted as
 * a non-budgeted EXTERNAL ledger entry tagged with the player's current band + sex.
 */
data class AdjustRankingPointCommand(
    val userId: UUID,
    val points: BigDecimal,
    val reason: String,
    val validFrom: LocalDateTime,
    val validUntil: LocalDateTime,
)

/** The fully-resolved insert an award repository persists (all defaults already applied). */
data class RankingPointAwardWrite(
    val userId: UUID,
    val points: BigDecimal,
    val pointClass: PointClass,
    val sourceType: PointSourceType,
    val sourceId: String?,
    val band: String,
    val sex: String,
    val reason: String?,
    val validFrom: LocalDateTime,
    val validUntil: LocalDateTime,
    val status: AwardStatus,
    val revokesAwardId: UUID?,
    val grantedBy: UUID?,
    val awardedAt: LocalDateTime,
    // The event that produced this award on finalize (#403 Phase D); null for manual / external grants.
    val eventId: UUID? = null,
    // The specific match (fixture) that granted this award on finalize (#448); null for manual grants.
    val matchId: UUID? = null,
)
