// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.common.dto.settings.AwardRankingPointsResponse
import org.skopeo.common.dto.settings.CalibrationMatchesResponse
import org.skopeo.common.dto.settings.FacebookLoginResponse
import org.skopeo.common.dto.settings.HideRankingPointsResponse
import org.skopeo.common.dto.settings.StandingsSourceResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.common.security.PLAYER_POINTS_VIEW_ROLES
import org.skopeo.domain.mapper.dto.settings.toResponse
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.AwardRankingPointsValue
import org.skopeo.domain.model.CalibrationMatchesValue
import org.skopeo.domain.model.FacebookLoginValue
import org.skopeo.domain.model.HideRankingPointsValue
import org.skopeo.domain.model.SnapshotSource
import org.skopeo.domain.model.StandingsSourceValue
import org.skopeo.domain.model.User
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

/** The app_settings key selecting which snapshot source the Standings tab serves (#146). */
private const val STANDINGS_SOURCE_KEY = "standings_source"

/** The app_settings key toggling the Facebook sign-in buttons (#647); absent ⇒ enabled. */
private const val FACEBOOK_LOGIN_KEY = "facebook_login_enabled"

/** The app_settings key gating ranking-point awarding app-wide (#641); absent ⇒ disabled. */
private const val AWARD_RANKING_POINTS_KEY = "award_ranking_points_enabled"

// #865. Named for what ticking it DOES, and deliberately unlike AWARD_RANKING_POINTS_KEY above: that one
// suppresses *awarding*, this one suppresses *display*. Two flags whose names differ by one word, with
// entirely different consequences, is a mistake waiting to happen.
private const val HIDE_RANKING_POINTS_KEY = "hide_ranking_points_from_players"

/**
 * The app_settings key holding N for the calibration window (#881) — the number of RATED matches a
 * manually-rated player stays in calibration for. Absent ⇒ [DEFAULT_CALIBRATION_MATCHES].
 */
private const val CALIBRATION_MATCHES_KEY = "calibration_match_count"

/** The agreed default: calibration runs from the 1st through the 10th rated match (#881). */
private const val DEFAULT_CALIBRATION_MATCHES = 10

/**
 * Bounds on N. Zero would mean "calibration is off", which the flag is not for — disabling it belongs in
 * a separate decision, not in a value that silently means nothing. The ceiling keeps a typo from putting
 * a player in calibration for a career.
 */
private const val MIN_CALIBRATION_MATCHES = 1
private const val MAX_CALIBRATION_MATCHES = 100

/**
 * Operational app_settings that steer serving behaviour without a redeploy (#146). Mirrors the
 * [ThemeService] pattern: a value is looked up in the generic app_settings store and coalesced to a safe
 * default when the row is absent or holds an unrecognized value — so no migration/seed is required — while
 * writes are ADMINISTRATOR-only and audit-logged. Expected failures return an [Either] left ([ServiceError]).
 */
@Suppress("TooManyFunctions") // Cohesive app_settings surface: standings source + a small set of feature flags.
class SettingsService(
    private val settings: AppSettingsRepository = AppSettingsRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /**
     * Which snapshot source the Standings tab serves (#146). Defaults to [SnapshotSource.RATING] when the
     * `standings_source` row is absent or holds a value that no longer maps to a source — so the tab stays
     * on ratings until an admin explicitly flips it to POINTS. Compared directly against `row?.value` so
     * there is no unreachable safe-call arm once `row` is non-null.
     */
    fun standingsSource(): SnapshotSource = getStandingsSource().source

    /** The current standings source plus its provenance (who set it, when). Public read — no auth. */
    fun getStandingsSource(): StandingsSourceValue {
        val row = settings.get(key = STANDINGS_SOURCE_KEY)
        val source = SnapshotSource.entries.firstOrNull { it.name == row?.value } ?: SnapshotSource.RATING
        return StandingsSourceValue(source = source, updatedBy = row?.updatedBy, updatedAt = row?.updatedAt)
    }

    /** The current standings source as its response DTO — the route-facing form of [getStandingsSource]. */
    fun getStandingsSourceResponse(): StandingsSourceResponse = getStandingsSource().toResponse()

    /**
     * Set the standings source (ADMINISTRATOR only). Rejects an unknown [source] as a [ServiceError.Validation].
     * Accepts RATING/POINTS case-insensitively, upserts the app-setting, and records a provenance row.
     */
    fun setStandingsSource(
        token: VerifiedFirebaseToken,
        source: String,
    ): Either<ServiceError, StandingsSourceResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val parsed =
                ensureNotNull(value = SnapshotSource.entries.firstOrNull { it.name.equals(other = source, ignoreCase = true) }) {
                    ServiceError.Validation(message = "Unknown standings source $source")
                }
            val row = settings.upsert(key = STANDINGS_SOURCE_KEY, value = parsed.name, updatedBy = adminId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.SETTINGS_STANDINGS_SOURCE_CHANGED,
                        entityType = AuditEntityType.SETTING,
                        entityId = null,
                        summary = "Set standings source to ${parsed.name}",
                        details = buildMap { put(key = "standingsSource", value = parsed.name) },
                    ),
            )
            StandingsSourceValue(source = parsed, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse()
        }

    /**
     * Whether the Facebook sign-in buttons are enabled (#647). Defaults to true (enabled) when the
     * `facebook_login_enabled` row is absent or holds a non-boolean value — so Facebook stays available
     * until an admin explicitly turns it off. Public read — no auth.
     */
    fun getFacebookLogin(): FacebookLoginValue {
        val row = settings.get(key = FACEBOOK_LOGIN_KEY)
        val enabled = row?.value?.toBooleanStrictOrNull() ?: true
        return FacebookLoginValue(enabled = enabled, updatedBy = row?.updatedBy, updatedAt = row?.updatedAt)
    }

    /** The Facebook-login flag as its response DTO — the route-facing form of [getFacebookLogin]. */
    fun getFacebookLoginResponse(): FacebookLoginResponse = getFacebookLogin().toResponse()

    /**
     * Enable or disable the Facebook sign-in buttons (ADMINISTRATOR only, #647). Upserts the app-setting
     * as the boolean's string form and records a provenance/audit row.
     */
    fun setFacebookLogin(
        token: VerifiedFirebaseToken,
        enabled: Boolean,
    ): Either<ServiceError, FacebookLoginResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val row = settings.upsert(key = FACEBOOK_LOGIN_KEY, value = enabled.toString(), updatedBy = adminId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.SETTINGS_FACEBOOK_LOGIN_CHANGED,
                        entityType = AuditEntityType.SETTING,
                        entityId = null,
                        summary = "${if (enabled) "Enabled" else "Disabled"} Facebook login",
                        details = buildMap { put(key = "facebookLoginEnabled", value = enabled.toString()) },
                    ),
            )
            FacebookLoginValue(enabled = enabled, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse()
        }

    /**
     * Whether ranking-point awarding is enabled app-wide (#641). Defaults to DISABLED when the
     * `award_ranking_points_enabled` row is absent or non-boolean — so nothing awards until an admin turns
     * it on. Public read — no auth.
     *
     * This is a KILL SWITCH, not merely a create-time gate or a UI affordance (#752). It is enforced
     * server-side in two places, and the browser (which hides the event-create checkbox) is only a
     * convenience: [org.skopeo.domain.service.event.EventService.create] coerces an award opt-in to false
     * while it is off, and [org.skopeo.domain.service.event.EventFinalizeAwarder] awards nothing while it
     * is off — so an event created while the flag was ON also stops paying out the moment it goes off.
     */
    fun getAwardRankingPoints(): AwardRankingPointsValue {
        val row = settings.get(key = AWARD_RANKING_POINTS_KEY)
        val enabled = row?.value?.toBooleanStrictOrNull() ?: false
        return AwardRankingPointsValue(enabled = enabled, updatedBy = row?.updatedBy, updatedAt = row?.updatedAt)
    }

    /** The award-points flag as its response DTO — the route-facing form of [getAwardRankingPoints]. */
    fun getAwardRankingPointsResponse(): AwardRankingPointsResponse = getAwardRankingPoints().toResponse()

    /**
     * Enable or disable ranking-point awarding app-wide (ADMINISTRATOR only, #641). Upserts the
     * app-setting as the boolean's string form and records a provenance/audit row. Turning it off takes
     * effect immediately for events that have not yet been finalized (#752): see [getAwardRankingPoints].
     */
    fun setAwardRankingPoints(
        token: VerifiedFirebaseToken,
        enabled: Boolean,
    ): Either<ServiceError, AwardRankingPointsResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val row = settings.upsert(key = AWARD_RANKING_POINTS_KEY, value = enabled.toString(), updatedBy = adminId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.SETTINGS_AWARD_RANKING_POINTS_CHANGED,
                        entityType = AuditEntityType.SETTING,
                        entityId = null,
                        summary = "${if (enabled) "Enabled" else "Disabled"} the event award-ranking-points option",
                        details = buildMap { put(key = "awardRankingPointsEnabled", value = enabled.toString()) },
                    ),
            )
            AwardRankingPointsValue(enabled = enabled, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse()
        }

    /**
     * Whether ranking-point figures are hidden from players and researchers (#865). Defaults to **not
     * hidden** when the row is absent or non-boolean — the opposite default from
     * [getAwardRankingPoints], and deliberately so: this flag is opt-in *suppression*, so an unseeded
     * database behaves exactly as it did before the flag existed. Public read — no auth.
     */
    fun getHideRankingPoints(): HideRankingPointsValue {
        val row = settings.get(key = HIDE_RANKING_POINTS_KEY)
        val hidden = row?.value?.toBooleanStrictOrNull() ?: false
        return HideRankingPointsValue(hidden = hidden, updatedBy = row?.updatedBy, updatedAt = row?.updatedAt)
    }

    /** The hide-points flag as its response DTO — the route-facing form of [getHideRankingPoints]. */
    fun getHideRankingPointsResponse(): HideRankingPointsResponse = getHideRankingPoints().toResponse()

    /**
     * Whether [viewer] may see ranking-point figures (#865).
     *
     * The **one** place the rule lives, so the five surfaces that show points cannot drift apart. Visible
     * when the flag is off, or when the viewer holds any of [PLAYER_POINTS_VIEW_ROLES].
     *
     * Note there is **no owner-self exemption**: a plain player's own points are suppressed on their own
     * profile too. That diverges from #186, where the owner does see their own precise rating — recorded
     * here so it is not "corrected" later. The rule is capability-only; the viewer's relationship to the
     * profile is irrelevant, which also makes it simpler than #186's shape would suggest.
     */
    fun pointsVisibleTo(viewer: User?): Boolean =
        !getHideRankingPoints().hidden || viewer?.capabilities?.any { it in PLAYER_POINTS_VIEW_ROLES } == true

    /**
     * N for the calibration window (#881) — how many **rated** matches a manually-rated player stays in
     * calibration for. Defaults to [DEFAULT_CALIBRATION_MATCHES] when unseeded or unparseable.
     *
     * Read at **evaluation time**, never copied onto a player, which is what lets a changed N take effect
     * across everyone at once: lowering it from 10 to 5 ends several in-flight calibrations immediately,
     * with no migration and no sweep. That was a deliberate decision on #881, and it is the reason
     * calibration is derived rather than stored.
     */
    fun getCalibrationMatches(): CalibrationMatchesValue {
        val row = settings.get(key = CALIBRATION_MATCHES_KEY)
        val matches =
            row?.value?.toIntOrNull()?.takeIf { it in MIN_CALIBRATION_MATCHES..MAX_CALIBRATION_MATCHES }
                ?: DEFAULT_CALIBRATION_MATCHES
        return CalibrationMatchesValue(matches = matches, updatedBy = row?.updatedBy, updatedAt = row?.updatedAt)
    }

    /** N as its response DTO — the route-facing form of [getCalibrationMatches]. */
    fun getCalibrationMatchesResponse(): CalibrationMatchesResponse = getCalibrationMatches().toResponse()

    /**
     * Set N for the calibration window (ADMINISTRATOR only, #881), audited.
     *
     * Validated here rather than at the route because the bounds are policy, not parsing: this is the
     * first numeric app setting, and a bad value would not fail loudly — it would quietly put every
     * manually-rated player into a calibration that never ends, or none at all.
     */
    fun setCalibrationMatches(
        token: VerifiedFirebaseToken,
        matches: Int,
    ): Either<ServiceError, CalibrationMatchesResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = matches in MIN_CALIBRATION_MATCHES..MAX_CALIBRATION_MATCHES) {
                ServiceError.Validation(
                    message = "Calibration matches must be between $MIN_CALIBRATION_MATCHES and $MAX_CALIBRATION_MATCHES",
                )
            }
            val previous = getCalibrationMatches().matches
            val row = settings.upsert(key = CALIBRATION_MATCHES_KEY, value = matches.toString(), updatedBy = adminId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.SETTINGS_CALIBRATION_MATCHES_CHANGED,
                        entityType = AuditEntityType.SETTING,
                        entityId = null,
                        // Both values, because the change applies globally and immediately: lowering it
                        // ends in-flight calibrations, so the delta is the operationally interesting part.
                        summary = "Changed the calibration window from $previous to $matches rated matches",
                        details = mapOf("previous" to previous.toString(), "matches" to matches.toString()),
                    ),
            )
            CalibrationMatchesValue(matches = matches, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse()
        }

    /**
     * Hide or show ranking-point figures for players and researchers (ADMINISTRATOR only, #865). Ticking
     * hides; the audit summary says which way it went.
     */
    fun setHideRankingPoints(
        token: VerifiedFirebaseToken,
        hidden: Boolean,
    ): Either<ServiceError, HideRankingPointsResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val row = settings.upsert(key = HIDE_RANKING_POINTS_KEY, value = hidden.toString(), updatedBy = adminId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.SETTINGS_HIDE_RANKING_POINTS_CHANGED,
                        entityType = AuditEntityType.SETTING,
                        entityId = null,
                        summary =
                            if (hidden) {
                                "Hid ranking points from players and researchers"
                            } else {
                                "Showed ranking points to players and researchers"
                            },
                        details = buildMap { put(key = "hideRankingPointsFromPlayers", value = hidden.toString()) },
                    ),
            )
            HideRankingPointsValue(hidden = hidden, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse()
        }

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }
}
