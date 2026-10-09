// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.user

import org.skopeo.common.dto.user.StaleAccountResponse
import org.skopeo.common.dto.user.StaleAccountSweepResponse
import org.skopeo.domain.model.StaleAccount
import org.skopeo.domain.model.StaleAccountSweepOutcome

/** [StaleAccountSweepOutcome] → its wire form (#1122). */
fun StaleAccountSweepOutcome.toResponse(): StaleAccountSweepResponse =
    StaleAccountSweepResponse(
        dryRun = dryRun,
        thresholdDays = thresholdDays,
        cutoff = cutoff.toString(),
        accounts = accounts.map { it.toResponse() },
    )

private fun StaleAccount.toResponse(): StaleAccountResponse =
    StaleAccountResponse(userId = userId.toString(), publicCode = publicCode, createdAt = createdAt.toString())
