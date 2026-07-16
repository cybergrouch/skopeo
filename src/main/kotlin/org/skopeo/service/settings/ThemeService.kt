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
import org.skopeo.model.ThemeSetting
import org.skopeo.model.ThemeSettingValue
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

/** The app_settings key backing the single global UI theme (#378). */
private const val THEME_KEY = "ui_theme"

/**
 * The single global UI theme setting (#378): publicly readable, ADMINISTRATOR-writable, audit-logged.
 * [getTheme] is a public read that defaults to [ThemeSetting.AUTO] when unset or unrecognized; [setTheme]
 * is admin-only (enforced here via [requireAdmin]) and records a provenance row.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class ThemeService(
    private val settings: AppSettingsRepository = AppSettingsRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** The current global theme, or the [ThemeSetting.AUTO] default when unset/unrecognized. Public — no auth. */
    fun getTheme(): ThemeSettingValue {
        val row = settings.get(key = THEME_KEY)
        val theme = row?.value?.let { value -> ThemeSetting.entries.firstOrNull { it.name == value } } ?: ThemeSetting.AUTO
        return ThemeSettingValue(theme = theme, updatedBy = row?.updatedBy, updatedAt = row?.updatedAt)
    }

    /** Set the global theme (ADMINISTRATOR only). Rejects an unknown [theme] as a [ServiceError.Validation]. */
    fun setTheme(
        token: VerifiedFirebaseToken,
        theme: String,
    ): Either<ServiceError, ThemeSettingValue> =
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
            ThemeSettingValue(theme = parsed, updatedBy = row.updatedBy, updatedAt = row.updatedAt)
        }

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }
}
