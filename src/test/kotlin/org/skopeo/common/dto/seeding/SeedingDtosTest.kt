// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.seeding

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.domain.mapper.dto.seeding.toResponse
import org.skopeo.domain.model.SeedingEntry
import java.util.UUID

/** The raw rating on a seeding row is ADMINISTRATOR-only (#583): shown when [showRawRating], else null. */
class SeedingDtosTest {
    private fun entry() =
        SeedingEntry(
            seed = 1,
            position = 1,
            userId = UUID.randomUUID(),
            displayName = "Alex",
            publicCode = "ABC123",
            ntrpBand = "4.0",
            rating = "4.250000",
            sex = "Male",
            age = 30,
        )

    @Test
    fun `an admin viewer sees the raw rating`() {
        val response = entry().toResponse(showRawRating = true)
        response.rating shouldBe "4.250000"
        response.ntrpBand shouldBe "4.0"
    }

    @Test
    fun `a non-admin viewer gets the band but not the raw rating`() {
        val response = entry().toResponse(showRawRating = false)
        response.rating.shouldBeNull()
        response.ntrpBand shouldBe "4.0"
    }
}
