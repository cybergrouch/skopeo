// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.skopeo.common.dto.user.StaleAccountSweepResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.ACCOUNT_SWEEP_ROLES
import org.skopeo.common.security.ClientPrincipal
import org.skopeo.domain.mapper.dto.user.toResponse
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.StaleAccount
import org.skopeo.domain.model.StaleAccountSweepOutcome
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.settings.SettingsService
import org.skopeo.repository.StaleAccountRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The stale-account sweep (#1122): soft-delete self-sign-ups that stayed unrated and untouched for longer
 * than the configured threshold. The rule itself lives in [StaleAccountRepository], so this service and the
 * pending list's countdown read the same definition.
 *
 * Follows the standings recompute (#389) in shape: one operation, reachable by a person from the dashboard
 * or by the Cloud Scheduler job holding an API key, with a dry run as the default. A commit uses the
 * ordinary soft delete — `is_active = false` — so every swept account can be restored from Deleted
 * Accounts, and re-running on the same day finds nothing new, because a swept account is no longer active.
 */
class StaleAccountService(
    private val stale: StaleAccountRepository = StaleAccountRepository(),
    private val users: UserRepository = UserRepository(),
    private val settings: SettingsService = SettingsService(),
    private val audit: AuditService = AuditService(),
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    /** Run the sweep as a signed-in caller holding [ACCOUNT_SWEEP_ROLES]. */
    fun sweep(
        token: VerifiedFirebaseToken,
        dryRun: Boolean,
    ): Either<ServiceError, StaleAccountSweepResponse> =
        either {
            val actorId = requireAnyOf(users = users, token = token, allowed = ACCOUNT_SWEEP_ROLES).bind()
            run(actor = Actor(userId = actorId, clientId = null), dryRun = dryRun).toResponse()
        }

    /**
     * Run the sweep as an **API client** — the scheduled run. The key needs an [ACCOUNT_SWEEP_ROLES] scope:
     * in practice `ACCOUNT_SWEEPER`, which exists so this key is not an administrator's (#1122). The audit
     * actor is the client, with no user, as for the standings recompute.
     */
    fun sweep(
        principal: ClientPrincipal,
        dryRun: Boolean,
    ): Either<ServiceError, StaleAccountSweepResponse> =
        either {
            ensure(condition = principal.scopes.any { it in ACCOUNT_SWEEP_ROLES }) { ServiceError.Forbidden() }
            run(actor = Actor(userId = null, clientId = principal.clientId), dryRun = dryRun).toResponse()
        }

    /**
     * When each of [userIds] will be swept, for those the rule matches — the pending list's countdown.
     * Absent from the map means the account is not eligible at all (rated, staff, has history, a merge
     * target, a placeholder). A date in the past means the next run removes it.
     */
    fun removalDates(userIds: Collection<UUID>): Map<UUID, LocalDate> {
        val days = settings.getStaleAccountDays().days.toLong()
        return stale
            .eligibleAmong(userIds = userIds)
            .associate { it.userId to it.createdAt.toLocalDate().plusDays(days) }
    }

    /** Who drove a run: a person or an API client. Exactly one is set; both feed the audit trail. */
    private data class Actor(
        val userId: UUID?,
        val clientId: UUID?,
    )

    private fun run(
        actor: Actor,
        dryRun: Boolean,
    ): StaleAccountSweepOutcome {
        val days = settings.getStaleAccountDays().days
        val cutoff = clock().minusDays(days.toLong())
        val accounts = stale.listStale(createdBefore = cutoff).map { it.toDomain() }

        if (!dryRun) {
            accounts.forEach { remove(account = it, actor = actor, days = days) }
        }
        audit.record(
            write =
                AuditWrite(
                    actorUserId = actor.userId,
                    actorClientId = actor.clientId,
                    action = if (dryRun) AuditAction.STALE_ACCOUNT_SWEEP_PREVIEWED else AuditAction.STALE_ACCOUNT_SWEEP_COMMITTED,
                    entityType = AuditEntityType.USER,
                    entityId = null,
                    summary =
                        if (dryRun) {
                            "Previewed the stale-account sweep: ${accounts.size} accounts would be deleted"
                        } else {
                            "Ran the stale-account sweep: deleted ${accounts.size} accounts"
                        },
                    details =
                        mapOf(
                            "thresholdDays" to days.toString(),
                            "cutoff" to cutoff.toString(),
                            "accounts" to accounts.joinToString(separator = ",") { it.publicCode },
                        ),
                ),
        )
        return StaleAccountSweepOutcome(dryRun = dryRun, thresholdDays = days, cutoff = cutoff, accounts = accounts)
    }

    // One audit entry per account, so each removal can be found — and undone — on its own.
    private fun remove(
        account: StaleAccount,
        actor: Actor,
        days: Int,
    ) {
        // Audited only once the row actually changed; the rule read it as active moments ago, so a miss here
        // means it vanished in between, and there is nothing to record.
        users.deactivate(id = account.userId).onRight {
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actor.userId,
                        actorClientId = actor.clientId,
                        action = AuditAction.ACCOUNT_AUTO_DELETED,
                        entityType = AuditEntityType.USER,
                        entityId = account.userId,
                        summary = "Deleted stale account ${account.publicCode}: unrated, with no history, over $days days after sign-up",
                        details = mapOf("createdAt" to account.createdAt.toString(), "thresholdDays" to days.toString()),
                    ),
            )
        }
    }
}
