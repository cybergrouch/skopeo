// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test

class SkopeoApplicationTests {
    @Test
    fun testRoot() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val response = client.get(urlString = "/")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "Skopeo API"
        }

    @Test
    fun testHealth() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val response = client.get(urlString = "/health")
            val body = response.bodyAsText()

            response.status shouldBe HttpStatusCode.OK
            body shouldContain "\"status\":\"UP\""
            body shouldContain "\"service\":\"Skopeo API\""
            // Assert the version field is present and non-empty, not a specific value — the version
            // changes with every release / dev-version bump (release.yml), so pinning it here would
            // break each bump PR.
            body shouldContain Regex(pattern = "\"version\":\"[^\"]+\"")
        }

    @Test
    fun `the Prometheus metrics endpoint is gone, not merely unadvertised`() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            // Removed in #751/#804. It was registered in `routing { }` with no `authenticate` wrapper, so
            // anyone could scrape JVM internals and the full route list off production — and nothing
            // consumed it, since Cloud Run scales to zero and suits pull-based scraping badly. Asserting
            // 404 is what satisfies "no longer anonymously reachable": deletion, not a gate.
            client.get(urlString = "/metrics").status shouldBe HttpStatusCode.NotFound
        }
}
