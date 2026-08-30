// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.user

import org.skopeo.common.dto.user.ClaimCodeResponse
import org.skopeo.domain.model.GeneratedClaimCode

fun GeneratedClaimCode.toResponse(): ClaimCodeResponse =
    ClaimCodeResponse(
        // Show-once delivery: the admin has to be able to pass the code to the player.
        code = plaintext.revealed,
        expiresAt = code.expiresAt.toString(),
        placeholderPublicCode = placeholderPublicCode,
    )
