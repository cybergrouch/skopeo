// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.skopeo.testsupport.PostgresTestDatabase

/**
 * Route-level tests for the Open Graph responder (#238). Uses the shared test DB so the services can
 * resolve (here: not-find) entities, and an injected stub shell so no network fetch of index.html
 * happens. Unknown codes fall back to the site-wide default card while still returning the SPA shell.
 */
class OpenGraphApiIntegrationTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }

        private const val STUB_SHELL =
            "<!doctype html><html lang=\"en\"><head><title>Skopeo</title></head>" +
                "<body><div id=\"root\"></div></body></html>"
    }

    private fun stubbedApp(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application {
                configureOpenGraphRoutes(
                    shellProvider = WebShellProvider { STUB_SHELL },
                    origin = "https://skopeo.co",
                )
            }
            val client = createClient {}
            block(client)
        }

    @Test
    fun `unknown match code serves the SPA shell with the default card`() =
        stubbedApp { client ->
            val response = client.get(urlString = "/matches/does-not-exist")

            response.status shouldBe HttpStatusCode.OK
            response.contentType() shouldBe ContentType.Text.Html.withCharset(charset = Charsets.UTF_8)
            val body = response.bodyAsText()
            body shouldContain "<div id=\"root\"></div>"
            body shouldContain "<meta property=\"og:type\" content=\"website\" />"
            body shouldContain "performance-based tennis ratings"
            body shouldContain "<meta property=\"og:url\" content=\"https://skopeo.co/matches/does-not-exist\" />"
        }

    @Test
    fun `unknown player code serves the default card`() =
        stubbedApp { client ->
            val body = client.get(urlString = "/players/nobody").bodyAsText()

            body shouldContain "<meta property=\"og:url\" content=\"https://skopeo.co/players/nobody\" />"
            body shouldContain "<meta name=\"twitter:card\" content=\"summary_large_image\" />"
        }

    @Test
    fun `unknown event code serves the default card`() =
        stubbedApp { client ->
            val body = client.get(urlString = "/events/ghost").bodyAsText()

            body shouldContain "<meta property=\"og:url\" content=\"https://skopeo.co/events/ghost\" />"
            body shouldContain "<meta property=\"og:image\" content=\"https://skopeo.co/og-cover.png\" />"
        }
}
