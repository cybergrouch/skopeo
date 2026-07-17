// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.settings

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.ServiceError
import org.skopeo.model.SnapshotSource
import org.skopeo.model.StandingsSourceValue
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

/** The app_settings key selecting which snapshot source the Standings tab serves (#146). */
private const val STANDINGS_SOURCE_KEY = "standings_source"

/**
 * Operational app_settings that steer serving behaviour without a redeploy (#146). Mirrors the
 * [ThemeService] pattern: a value is looked up in the generic app_settings store and coalesced to a safe
 * default when the row is absent or holds an unrecognized value — so no migration/seed is required — while
 * writes are ADMINISTRATOR-only and audit-logged. Expected failures return an [Either] left ([ServiceError]).
 */
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

    /**
     * Set the standings source (ADMINISTRATOR only). Rejects an unknown [source] as a [ServiceError.Validation].
     * Accepts RATING/POINTS case-insensitively, upserts the app-setting, and records a provenance row.
     */
    fun setStandingsSource(
        token: VerifiedFirebaseToken,
        source: String,
    ): Either<ServiceError, StandingsSourceValue> =
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
            StandingsSourceValue(source = parsed, updatedBy = row.updatedBy, updatedAt = row.updatedAt)
        }

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }
}
