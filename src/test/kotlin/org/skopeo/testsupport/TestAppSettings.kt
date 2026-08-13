// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.testsupport

import org.skopeo.repository.AppSettingsRepository
import java.util.UUID

/**
 * Test-side writes to the app_settings store (#378) for global flags whose default blocks the behaviour
 * under test. [PostgresTestDatabase.truncate] resets app_settings between tests, so a flag has to be set
 * per test.
 *
 * The global "award ranking points" flag (#641) defaults OFF and is enforced server-side at both event
 * create and finalize (#752) — any test that expects an event to opt into awarding, or to actually pay
 * out on finalize, must turn it on first.
 */
object TestAppSettings {
    fun setAwardRankingPoints(
        enabled: Boolean,
        updatedBy: UUID,
    ) {
        AppSettingsRepository().upsert(key = "award_ranking_points_enabled", value = enabled.toString(), updatedBy = updatedBy)
    }
}
