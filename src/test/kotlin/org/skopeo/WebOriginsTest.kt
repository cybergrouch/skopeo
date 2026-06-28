// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WebOriginsTest {
    @Test
    fun `parses multiple comma-separated origins into host-scheme pairs, trimming whitespace`() {
        parseWebOrigins(raw = "https://skopeo.com, https://skopeo-prod.web.app") shouldBe
            listOf("skopeo.com" to "https", "skopeo-prod.web.app" to "https")
    }

    @Test
    fun `keeps an explicit port and supports the http scheme`() {
        parseWebOrigins(raw = "http://localhost:3000") shouldBe listOf(element = "localhost:3000" to "http")
    }

    @Test
    fun `null, blank, and malformed entries yield nothing`() {
        parseWebOrigins(raw = null) shouldBe emptyList()
        parseWebOrigins(raw = "   ") shouldBe emptyList()
        // No scheme, empty host, empty scheme — all dropped; the one valid entry survives.
        parseWebOrigins(raw = "skopeo.com, https://, ://x, https://ok.example") shouldBe
            listOf(element = "ok.example" to "https")
    }
}
