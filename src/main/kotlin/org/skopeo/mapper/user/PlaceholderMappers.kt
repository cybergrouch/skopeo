// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.user

import org.skopeo.dto.user.ClaimCodeResponse
import org.skopeo.model.GeneratedClaimCode

fun GeneratedClaimCode.toResponse(): ClaimCodeResponse =
    ClaimCodeResponse(
        code = plaintext,
        expiresAt = code.expiresAt.toString(),
        placeholderPublicCode = placeholderPublicCode,
    )
