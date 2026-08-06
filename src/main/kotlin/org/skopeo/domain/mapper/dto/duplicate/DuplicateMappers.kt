// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.duplicate

import org.skopeo.domain.mapper.dto.user.toSummary
import org.skopeo.domain.model.DuplicateCandidateView
import org.skopeo.domain.model.DuplicateCandidateViewPage
import org.skopeo.dto.duplicate.DuplicateCandidatePageResponse
import org.skopeo.dto.duplicate.DuplicateCandidateResponse

fun DuplicateCandidateView.toResponse(): DuplicateCandidateResponse =
    DuplicateCandidateResponse(
        id = candidate.id.toString(),
        status = candidate.status.name,
        signal = candidate.signal.name,
        detail = candidate.detail,
        flaggedAt = candidate.flaggedAt.toString(),
        userA = userA.toSummary(isDeleted = userADeleted),
        userB = userB.toSummary(isDeleted = userBDeleted),
    )

fun DuplicateCandidateViewPage.toResponse(): DuplicateCandidatePageResponse =
    DuplicateCandidatePageResponse(items = items.map { it.toResponse() }, total = total)
