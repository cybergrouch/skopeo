// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import kotlinx.serialization.Serializable

@Serializable
enum class RatingSystem {
    NTRP, // National Tennis Rating Program (1.0 - 7.0)
    UTR, // Universal Tennis Rating (1.0 - 16.5+)
}
