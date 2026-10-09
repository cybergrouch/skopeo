// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import java.time.LocalDateTime
import java.util.UUID

/** An account the stale-account rule matches (#1122). */
data class StaleAccount(
    val userId: UUID,
    val publicCode: String,
    val createdAt: LocalDateTime,
)

/** The result of one stale-account sweep, committed or previewed (#1122). */
data class StaleAccountSweepOutcome(
    val dryRun: Boolean,
    val thresholdDays: Int,
    val cutoff: LocalDateTime,
    val accounts: List<StaleAccount>,
)
