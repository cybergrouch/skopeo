// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.skopeo.common.dto.settings.LocalThemeResponse
import org.skopeo.common.dto.settings.ThemeResponse
import org.skopeo.common.dto.settings.ThemeScheduleResponse
import org.skopeo.common.dto.settings.ThemeScheduleRowRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.settings.toDraft
import org.skopeo.domain.mapper.dto.settings.toResponse
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.ThemeScheduleRow
import org.skopeo.domain.model.ThemeScheduleValue
import org.skopeo.domain.model.ThemeSetting
import org.skopeo.domain.model.ThemeSettingValue
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDateTime
import java.util.UUID

/** The app_settings key backing the single global UI theme (#378). */
private const val THEME_KEY = "ui_theme"

/** The app_settings key backing the AUTO season schedule (#1196), a JSON list of [ThemeScheduleRow]. */
private const val SCHEDULE_KEY = "ui_theme_schedule"

private val SCHEDULE_SERIALIZER = ListSerializer(elementSerializer = ThemeScheduleRow.serializer())

private val JSON = Json { ignoreUnknownKeys = true }

@Suppress("TopLevelPropertyNaming") // matches the `logger` convention used across the codebase
private val logger = KotlinLogging.logger {}

/**
 * The single global UI theme setting (#378): publicly readable, ADMINISTRATOR-writable, audit-logged.
 * [getTheme] is a public read that defaults to [ThemeSetting.AUTO] when unset or unrecognized; [setTheme]
 * is admin-only (enforced here via [requireAdmin]) and records a provenance row. The AUTO season schedule
 * (#1196) rides on the same read and has its own admin write, [setSchedule].
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class ThemeService(
    private val settings: AppSettingsRepository = AppSettingsRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** The current global theme, or the [ThemeSetting.AUTO] default when unset/unrecognized. Public — no auth. */
    fun getTheme(): ThemeResponse {
        val row = settings.get(key = THEME_KEY)
        // Coalesce to AUTO when unset (row null) or when the stored value no longer maps to a theme.
        // Compared directly against row?.value rather than via a nested ?.let, so there is no unreachable
        // safe-call arm (value is non-null once row is) for a test to chase.
        val theme = ThemeSetting.entries.firstOrNull { it.name == row?.value } ?: ThemeSetting.AUTO
        return ThemeSettingValue(theme = theme, updatedBy = row?.updatedBy, updatedAt = row?.updatedAt).toResponse(schedule = schedule())
    }

    /** Set the global theme (ADMINISTRATOR only). Rejects an unknown [theme] as a [ServiceError.Validation]. */
    fun setTheme(
        token: VerifiedFirebaseToken,
        theme: String,
    ): Either<ServiceError, ThemeResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val parsed =
                ensureNotNull(value = ThemeSetting.entries.firstOrNull { it.name.equals(other = theme, ignoreCase = true) }) {
                    ServiceError.Validation(message = "Unknown theme $theme")
                }
            val row = settings.upsert(key = THEME_KEY, value = parsed.name, updatedBy = adminId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.SETTINGS_THEME_CHANGED,
                        entityType = AuditEntityType.SETTING,
                        entityId = null,
                        summary = "Set UI theme to ${parsed.name}",
                        details = buildMap { put(key = "theme", value = parsed.name) },
                    ),
            )
            ThemeSettingValue(theme = parsed, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse(schedule = schedule())
        }

    /**
     * Replace the AUTO season schedule (#1196; ADMINISTRATOR only). [ThemeScheduleRules.validate] decides
     * what may be saved — every overlap, duplicate name and bad row comes back at once as one structured
     * [ServiceError.Validation]. Gaps are allowed and returned as [ThemeScheduleResponse.gaps]. The audit row
     * carries the schedule before and after, as JSON.
     */
    fun setSchedule(
        token: VerifiedFirebaseToken,
        rows: List<ThemeScheduleRowRequest>,
    ): Either<ServiceError, ThemeScheduleResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val schedule = ThemeScheduleRules.validate(drafts = rows.map { it.toDraft() }).bind()
            val before = schedule()
            val encoded = JSON.encodeToString(serializer = SCHEDULE_SERIALIZER, value = schedule)
            val row = settings.upsert(key = SCHEDULE_KEY, value = encoded, updatedBy = adminId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.SETTINGS_THEME_SCHEDULE_CHANGED,
                        entityType = AuditEntityType.SETTING,
                        entityId = null,
                        summary = "Updated the AUTO theme schedule (${schedule.size} rows)",
                        details =
                            buildMap {
                                put(key = "before", value = JSON.encodeToString(serializer = SCHEDULE_SERIALIZER, value = before.rows))
                                put(key = "after", value = encoded)
                            },
                    ),
            )
            ThemeScheduleValue(rows = schedule, updatedBy = row.updatedBy, updatedAt = row.updatedAt)
                .toResponse(gaps = ThemeScheduleRules.gaps(rows = schedule))
        }

    /** The stored AUTO schedule, or [ThemeScheduleRules.DEFAULT] when none is stored or it no longer parses. */
    private fun schedule(): ThemeScheduleValue {
        val row = settings.get(key = SCHEDULE_KEY)
        val rows = row?.let { decodeSchedule(raw = it.value) }
        return if (row == null || rows == null) {
            ThemeScheduleValue(rows = ThemeScheduleRules.DEFAULT, updatedBy = null, updatedAt = null)
        } else {
            ThemeScheduleValue(rows = rows, updatedBy = row.updatedBy, updatedAt = row.updatedAt)
        }
    }

    /** Null when [raw] isn't a readable schedule (e.g. a theme since renamed), so the default is served. */
    private fun decodeSchedule(raw: String): List<ThemeScheduleRow>? =
        try {
            JSON.decodeFromString(deserializer = SCHEDULE_SERIALIZER, string = raw)
        } catch (e: SerializationException) {
            logger.warn(throwable = e) { "Stored theme schedule is unreadable; serving the default" }
            null
        }

    /**
     * The caller's own per-profile local theme (#514). Self-service: resolves the caller from their
     * token; [ServiceError.Forbidden] when the token maps to no provisioned user.
     */
    fun getLocalTheme(token: VerifiedFirebaseToken): Either<ServiceError, LocalThemeResponse> =
        either {
            val caller = requireCaller(token = token).bind()
            users.getLocalTheme(id = caller).bind().toResponse()
        }

    /**
     * Set (or clear) the caller's own local theme (#514). Self-service: the profile owner sets their
     * OWN — the caller id comes from the token, never a path parameter, so there is no other-user path.
     * A non-null [theme] must parse to a [ThemeSetting] and stamps `local_theme_set_at = now`; a null
     * [theme] clears both, reverting to the global theme.
     */
    fun setLocalTheme(
        token: VerifiedFirebaseToken,
        theme: String?,
        now: LocalDateTime = LocalDateTime.now(),
    ): Either<ServiceError, LocalThemeResponse> =
        either {
            val caller = requireCaller(token = token).bind()
            val parsed =
                theme?.let { raw ->
                    ensureNotNull(value = ThemeSetting.entries.firstOrNull { it.name.equals(other = raw, ignoreCase = true) }) {
                        ServiceError.Validation(message = "Unknown theme $raw")
                    }
                }
            users.setLocalTheme(id = caller, theme = parsed, setAt = now).bind().toResponse()
        }

    /** Resolve the caller's provisioned user id, or [ServiceError.Forbidden] when the token maps to none. */
    private fun requireCaller(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller == null) ServiceError.Forbidden().left() else caller.id.right()
    }

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }
}
