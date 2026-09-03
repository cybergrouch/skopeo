// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import org.skopeo.common.contract.FullMatchPointsConfig
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.common.contract.TournamentPointsConfig
import org.skopeo.common.dto.settings.FullMatchConfigResponse
import org.skopeo.common.dto.settings.OpenPlayConfigResponse
import org.skopeo.common.dto.settings.TournamentConfigResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.settings.toResponse
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.StoredConfig
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.PointsConfigRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

/** points_config keys backing the two global schedules (#552/#553). */
private const val OPEN_PLAY_KEY = "open_play"
private const val TOURNAMENT_KEY = "tournament"
private const val FULL_MATCH_KEY = "full_match"
private const val PLACEMENT_PLACES = 4

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * The global, admin-configurable points schedules (#552/#553): the open-play margin-bracket table and
 * the tournament placement table, plus their validity windows — the scoped successor to the points
 * policy removed in V27 (#540). Reads are public and come from the **current schedule version** (#862),
 * which V47 seeds from the Kotlin defaults — so there is no "unset" state; writes are ADMINISTRATOR-only
 * (enforced here), audited, and append a NEW version rather than overwriting the current one.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class PointsConfigService(
    private val configs: PointsConfigRepository = PointsConfigRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /**
     * The schedule version new awards are computed under (#862). Read once per finalize so a schedule
     * edited mid-run cannot split one event's awards across two versions.
     */
    fun currentScheduleVersion(): Int = configs.currentVersion()

    /** The current open-play schedule, or the seeded default when unset/corrupt. Public — no auth. */
    fun getOpenPlay(): StoredConfig<OpenPlayPointsConfig> =
        stored(key = OPEN_PLAY_KEY, deserializer = OpenPlayPointsConfig.serializer(), fallback = OpenPlayPointsConfig.DEFAULT)

    /** The current tournament placement schedule, or the seeded default when unset/corrupt. Public — no auth. */
    fun getTournament(): StoredConfig<TournamentPointsConfig> =
        stored(key = TOURNAMENT_KEY, deserializer = TournamentPointsConfig.serializer(), fallback = TournamentPointsConfig.DEFAULT)

    /** The current Full Match window, or the seeded default when unset/corrupt. Public — no auth. */
    fun getFullMatch(): StoredConfig<FullMatchPointsConfig> =
        stored(key = FULL_MATCH_KEY, deserializer = FullMatchPointsConfig.serializer(), fallback = FullMatchPointsConfig.DEFAULT)

    /** Set the Full Match window (ADMINISTRATOR only). Rejects a non-positive validity as [ServiceError.Validation]. */
    fun setFullMatch(
        token: VerifiedFirebaseToken,
        config: FullMatchPointsConfig,
    ): Either<ServiceError, FullMatchConfigResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = config.validityDays > 0) {
                ServiceError.Validation(message = "Full Match schedule needs a positive validity")
            }
            val row =
                configs.appendVersion(
                    key = FULL_MATCH_KEY,
                    value = JSON.encodeToString(serializer = FullMatchPointsConfig.serializer(), value = config),
                    actorId = adminId,
                )
            recordChange(adminId = adminId, key = FULL_MATCH_KEY)
            StoredConfig(value = config, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse()
        }

    /** Set the open-play schedule (ADMINISTRATOR only). Rejects an invalid schedule as a [ServiceError.Validation]. */
    fun setOpenPlay(
        token: VerifiedFirebaseToken,
        config: OpenPlayPointsConfig,
    ): Either<ServiceError, OpenPlayConfigResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = config.maxMargin >= 1 && config.rows.isNotEmpty() && config.validityDays > 0) {
                ServiceError.Validation(message = "Open-play schedule needs maxMargin ≥ 1, non-empty rows, and positive validity")
            }
            val row =
                configs.appendVersion(
                    key = OPEN_PLAY_KEY,
                    value = JSON.encodeToString(serializer = OpenPlayPointsConfig.serializer(), value = config),
                    actorId = adminId,
                )
            recordChange(adminId = adminId, key = OPEN_PLAY_KEY)
            StoredConfig(value = config, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse()
        }

    /** Set the tournament placement schedule (ADMINISTRATOR only). Rejects an invalid schedule as [ServiceError.Validation]. */
    fun setTournament(
        token: VerifiedFirebaseToken,
        config: TournamentPointsConfig,
    ): Either<ServiceError, TournamentConfigResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(
                condition =
                    config.sanctioned.size == PLACEMENT_PLACES &&
                        config.unsanctioned.size == PLACEMENT_PLACES &&
                        config.validityDays > 0,
            ) {
                ServiceError.Validation(message = "Tournament schedule needs 4 sanctioned + 4 unsanctioned places and positive validity")
            }
            val row =
                configs.appendVersion(
                    key = TOURNAMENT_KEY,
                    value = JSON.encodeToString(serializer = TournamentPointsConfig.serializer(), value = config),
                    actorId = adminId,
                )
            recordChange(adminId = adminId, key = TOURNAMENT_KEY)
            StoredConfig(value = config, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse()
        }

    /**
     * One schedule as of the current version (#862), or [fallback] when the stored document is
     * **undecodable**. Falling back on a decode failure is deliberate: a schedule row corrupted by hand
     * should degrade to the shipped defaults rather than break every read of the points API.
     *
     * **The database is authoritative.** V47 seeds v1 from the Kotlin defaults, so a row always exists —
     * and dropping the old "fall back to `X.DEFAULT`" behaviour is what makes a forgotten version bump
     * *inert* instead of *corrupting*: changing the Kotlin defaults without seeding a new version now has
     * no runtime effect at all, rather than quietly computing new rates and recording them under the old
     * version number. `PointsScheduleSeedTest` pins the seed against the defaults so the two cannot drift.
     *
     * [fallback] is therefore a **corruption** guard only — an unparseable stored document, which should
     * not happen — not a "config unset" path. It is reported as `updatedBy`/`updatedAt` = null so a caller
     * can tell it apart from a real row.
     */
    private fun <T> stored(
        key: String,
        deserializer: DeserializationStrategy<T>,
        fallback: T,
    ): StoredConfig<T> {
        val row = configs.get(key = key)
        val decoded =
            row?.value?.let {
                runCatching { JSON.decodeFromString(deserializer = deserializer, string = it) }.getOrNull()
            }
        return if (decoded != null) {
            StoredConfig(value = decoded, updatedBy = row.updatedBy, updatedAt = row.updatedAt)
        } else {
            StoredConfig(value = fallback, updatedBy = null, updatedAt = null)
        }
    }

    private fun recordChange(
        adminId: UUID,
        key: String,
    ) {
        audit.record(
            write =
                AuditWrite(
                    actorUserId = adminId,
                    action = AuditAction.SETTINGS_POINTS_CONFIG_CHANGED,
                    entityType = AuditEntityType.SETTING,
                    entityId = null,
                    summary = "Updated the $key points schedule",
                    details = buildMap { put(key = "schedule", value = key) },
                ),
        )
    }

    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}

// Route-facing adaptations (#840): zero-argument one-liners over the public reads, kept at file scope
// because mapping a domain value to its DTO is not this service's behaviour — and because the
// `mapper.dto` package is service-only by architecture, so a route cannot call `.toResponse()` itself.
fun PointsConfigService.getOpenPlayResponse(): OpenPlayConfigResponse = getOpenPlay().toResponse()

fun PointsConfigService.getTournamentResponse(): TournamentConfigResponse = getTournament().toResponse()

fun PointsConfigService.getFullMatchResponse(): FullMatchConfigResponse = getFullMatch().toResponse()
