// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import kotlinx.serialization.json.Json
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.common.contract.TournamentPointsConfig
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
import org.skopeo.dto.settings.OpenPlayConfigResponse
import org.skopeo.dto.settings.TournamentConfigResponse
import org.skopeo.repository.PointsConfigRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

/** points_config keys backing the two global schedules (#552/#553). */
private const val OPEN_PLAY_KEY = "open_play"
private const val TOURNAMENT_KEY = "tournament"
private const val PLACEMENT_PLACES = 4

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * The global, admin-configurable points schedules (#552/#553): the open-play margin-bracket table and
 * the tournament placement table, plus their validity windows — the scoped successor to the points
 * policy removed in V27 (#540). Reads are public and fall back to behaviour-preserving code defaults
 * when unset; writes are ADMINISTRATOR-only (enforced here) and audited.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class PointsConfigService(
    private val configs: PointsConfigRepository = PointsConfigRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** The current open-play schedule, or the seeded default when unset/corrupt. Public — no auth. */
    fun getOpenPlay(): StoredConfig<OpenPlayPointsConfig> {
        val row = configs.get(key = OPEN_PLAY_KEY)
        val decoded =
            row?.value?.let {
                runCatching {
                    JSON.decodeFromString(
                        deserializer = OpenPlayPointsConfig.serializer(),
                        string = it,
                    )
                }.getOrNull()
            }
        return if (decoded != null) {
            StoredConfig(value = decoded, updatedBy = row.updatedBy, updatedAt = row.updatedAt)
        } else {
            StoredConfig(value = OpenPlayPointsConfig.DEFAULT, updatedBy = null, updatedAt = null)
        }
    }

    /** The open-play schedule as its response DTO — the route-facing form of [getOpenPlay]. */
    fun getOpenPlayResponse(): OpenPlayConfigResponse = getOpenPlay().toResponse()

    /** The current tournament placement schedule, or the seeded default when unset/corrupt. Public — no auth. */
    fun getTournament(): StoredConfig<TournamentPointsConfig> {
        val row = configs.get(key = TOURNAMENT_KEY)
        val decoded =
            row?.value?.let {
                runCatching {
                    JSON.decodeFromString(
                        deserializer = TournamentPointsConfig.serializer(),
                        string = it,
                    )
                }.getOrNull()
            }
        return if (decoded != null) {
            StoredConfig(value = decoded, updatedBy = row.updatedBy, updatedAt = row.updatedAt)
        } else {
            StoredConfig(value = TournamentPointsConfig.DEFAULT, updatedBy = null, updatedAt = null)
        }
    }

    /** The tournament schedule as its response DTO — the route-facing form of [getTournament]. */
    fun getTournamentResponse(): TournamentConfigResponse = getTournament().toResponse()

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
                configs.upsert(
                    key = OPEN_PLAY_KEY,
                    value = JSON.encodeToString(serializer = OpenPlayPointsConfig.serializer(), value = config),
                    updatedBy = adminId,
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
                configs.upsert(
                    key = TOURNAMENT_KEY,
                    value = JSON.encodeToString(serializer = TournamentPointsConfig.serializer(), value = config),
                    updatedBy = adminId,
                )
            recordChange(adminId = adminId, key = TOURNAMENT_KEY)
            StoredConfig(value = config, updatedBy = row.updatedBy, updatedAt = row.updatedAt).toResponse()
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
