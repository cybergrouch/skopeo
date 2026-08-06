// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.rating

import org.skopeo.common.dto.audit.AuditPersonResponse
import org.skopeo.common.dto.rating.RatingRequestPageResponse
import org.skopeo.common.dto.rating.RatingRequestResponse
import org.skopeo.domain.model.Level
import org.skopeo.domain.model.RatingRequest
import org.skopeo.domain.model.RatingRequestPage
import org.skopeo.domain.model.RatingRequestView
import java.time.ZoneOffset

private fun RatingRequest.toResponse(requester: AuditPersonResponse? = null): RatingRequestResponse =
    RatingRequestResponse(
        id = id.toString(),
        userId = userId.toString(),
        status = status.name,
        justification = justification,
        newRating = newRating?.let { Level.fromValue(value = it.toPlainString()).value },
        reason = reason,
        resolvedAt = resolvedAt?.toInstant(ZoneOffset.UTC)?.toString(),
        createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
        requester = requester,
    )

/** The player's own request (no requester resolution needed). */
fun RatingRequest.toResponse(): RatingRequestResponse = toResponse(requester = null)

fun RatingRequestView.toResponse(): RatingRequestResponse =
    request.toResponse(
        requester =
            requester?.let { AuditPersonResponse(userId = it.userId.toString(), displayName = it.displayName, publicCode = it.publicCode) },
    )

fun RatingRequestPage.toResponse(): RatingRequestPageResponse =
    RatingRequestPageResponse(items = items.map { it.toResponse() }, total = total)
