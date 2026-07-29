// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.duplicate

import org.skopeo.dto.duplicate.DuplicateCandidatePageResponse
import org.skopeo.dto.duplicate.DuplicateCandidateResponse
import org.skopeo.mapper.user.toSummary
import org.skopeo.model.DuplicateCandidateView
import org.skopeo.model.DuplicateCandidateViewPage

fun DuplicateCandidateView.toResponse(): DuplicateCandidateResponse =
    DuplicateCandidateResponse(
        id = candidate.id.toString(),
        status = candidate.status.name,
        signal = candidate.signal.name,
        detail = candidate.detail,
        flaggedAt = candidate.flaggedAt.toString(),
        userA = userA.toSummary(),
        userB = userB.toSummary(),
    )

fun DuplicateCandidateViewPage.toResponse(): DuplicateCandidatePageResponse =
    DuplicateCandidatePageResponse(items = items.map { it.toResponse() }, total = total)
