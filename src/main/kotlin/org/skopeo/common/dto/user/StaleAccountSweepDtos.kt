// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.user

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/v1/users/stale-sweeps` (#1122). [dryRun] defaults to true: a preview that lists
 * who would be swept and changes nothing. Only an explicit `false` soft-deletes.
 */
@Serializable
data class StaleAccountSweepRequest(
    val dryRun: Boolean = true,
)

/**
 * What a stale-account sweep did, or would do (#1122). [accounts] is every account it soft-deleted — or,
 * when [dryRun], would have — oldest sign-up first. [cutoff] is the sign-up time an account had to be
 * older than, derived from [thresholdDays] at the moment of the run.
 */
@Serializable
data class StaleAccountSweepResponse(
    val dryRun: Boolean,
    val thresholdDays: Int,
    val cutoff: String,
    val accounts: List<StaleAccountResponse>,
)

/** One account the sweep matched. */
@Serializable
data class StaleAccountResponse(
    val userId: String,
    val publicCode: String,
    val createdAt: String,
)
