// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.common.contract.TournamentPointsConfig
import org.skopeo.repository.PointsConfigRepository

private const val OPEN_PLAY_KEY = "open_play"
private const val TOURNAMENT_KEY = "tournament"

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * Reads the schedules of a **past** points-schedule version (#862), for explaining an award that was paid
 * under one.
 *
 * Separate from [PointsConfigService], which is about the *current* schedules — reading, editing and
 * auditing them. This is a read of history: no capabilities, no audit, no fallback to the Kotlin defaults.
 * The absence of a fallback is the point. Substituting today's rates for a version that is gone would
 * produce a confident derivation whose numbers do not add up to the amount beside it, which is the exact
 * failure versioning was introduced to prevent.
 */
class PointsScheduleHistory(
    private val configs: PointsConfigRepository = PointsConfigRepository(),
) {
/**
     * The schedules **as of [version]** (#862) — for explaining an award that was paid under an older one.
     *
     * Either field is null when that version has no such document, which the caller must report as
     * "not recorded" rather than substituting today's rates: current rates against a historical amount is
     * how a derivation ends up contradicting the number beside it.
     *
     * Both are returned together rather than as two reads, because a version is one document set — asking
     * for the open-play table of one version and the placement table of another is not a question worth
     * being able to phrase.
     */
    fun schedulesAtVersion(version: Int): VersionedSchedules =
        VersionedSchedules(
            openPlay =
                decodeOrNull(
                    raw = configs.get(key = OPEN_PLAY_KEY, version = version)?.value,
                    deserializer = OpenPlayPointsConfig.serializer(),
                ),
            tournament =
                decodeOrNull(
                    raw = configs.get(key = TOURNAMENT_KEY, version = version)?.value,
                    deserializer = TournamentPointsConfig.serializer(),
                ),
        )
}

/** The schedules belonging to one points-schedule version (#862); a field is null when that version has none. */
data class VersionedSchedules(
    val openPlay: OpenPlayPointsConfig?,
    val tournament: TournamentPointsConfig?,
)

/** Decodes a stored schedule document, or null when it is absent or no longer parseable. */
private fun <T> decodeOrNull(
    raw: String?,
    deserializer: DeserializationStrategy<T>,
): T? =
    raw?.let {
        runCatching { JSON.decodeFromString(deserializer = deserializer, string = it) }.getOrNull()
    }
