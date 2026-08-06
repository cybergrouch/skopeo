// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.settings.toResponse
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.AwardRankingPointsValue
import org.skopeo.domain.model.FacebookLoginValue
import org.skopeo.domain.model.SnapshotSource
import org.skopeo.domain.model.StandingsSourceValue
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.dto.settings.AwardRankingPointsResponse
import org.skopeo.dto.settings.FacebookLoginResponse
import org.skopeo.dto.settings.StandingsSourceResponse
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

/** The app_settings key selecting which snapshot source the Standings tab serves (#146). */
private const val STANDINGS_SOURCE_KEY = "standings_source"

/** The app_settings key toggling the Facebook sign-in buttons (#647); absent ⇒ enabled. */
private const val FACEBOOK_LOGIN_KEY = "facebook_login_enabled"

/** The app_settings key toggling the event "Award Ranking Points" checkbox (#641); absent ⇒ disabled. */
private const val AWARD_RANKING_POINTS_KEY = "award_ranking_points_enabled"

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
     * Whether the event-create form offers the "Award Ranking Points" checkbox (#641). Defaults to
     * DISABLED (checkbox hidden) when the `award_ranking_points_enabled` row is absent or non-boolean — so
     * hosts can't opt an event into awarding until an admin turns it on. Public read — no auth.
     */
    fun getAwardRankingPoints(): AwardRankingPointsValue {
        val row = settings.get(key = AWARD_RANKING_POINTS_KEY)
        val enabled = row?.value?.toBooleanStrictOrNull() ?: false
        return AwardRankingPointsValue(enabled = enabled, updatedBy = row?.updatedBy, updatedAt = row?.updatedAt)
    }

    /** The award-points flag as its response DTO — the route-facing form of [getAwardRankingPoints]. */
    fun getAwardRankingPointsResponse(): AwardRankingPointsResponse = getAwardRankingPoints().toResponse()

    /**
     * Show or hide the event "Award Ranking Points" checkbox (ADMINISTRATOR only, #641). Upserts the
     * app-setting as the boolean's string form and records a provenance/audit row.
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

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }
}
