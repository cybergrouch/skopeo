// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class PlayerProfile(
    val playerId: String,
    val name: String,
    val rating: Rating,
    // Opaque group label for the binary group-category factor (#719): the calculator only checks two
    // sides' groups for equality (same group → factor 1, different → 0), attaching no meaning to the
    // value. A dedicated GroupClassifier upstream derives it (singles → sex, doubles → shared label);
    // absent/null → treated as the same group → factor 1, so existing callers/tests are unchanged.
    // Omitted from serialized output when null so the exact-payload response contract stays null-free.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val group: String? = null,
) {
    init {
        require(value = playerId.isNotBlank()) { "Player ID must not be blank" }
        require(value = playerId.length <= 50) { "Player ID must be at most 50 characters" }
        require(value = name.isNotBlank()) { "Player name must not be blank" }
        require(value = name.length <= 100) { "Player name must be at most 100 characters" }
    }
}
