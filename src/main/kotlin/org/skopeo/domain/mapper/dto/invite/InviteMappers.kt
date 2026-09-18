// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.invite

import org.skopeo.common.dto.invite.InvitePageResponse
import org.skopeo.common.dto.invite.InviteResponse
import org.skopeo.domain.model.Invite
import org.skopeo.domain.model.InvitePage
import org.skopeo.domain.model.InviteStatus
import java.time.LocalDateTime

fun Invite.toResponse(): InviteResponse =
    InviteResponse(
        id = id.toString(),
        email = email,
        status = if (status == InviteStatus.PENDING && !isOpen(asOf = LocalDateTime.now())) "EXPIRED" else status.name,
        invitedBy = invitedBy?.toString(),
        expiresAt = expiresAt.toString(),
        acceptedAt = acceptedAt?.toString(),
        createdAt = createdAt.toString(),
    )

fun InvitePage.toResponse(): InvitePageResponse = InvitePageResponse(items = items.map { it.toResponse() }, total = total)
