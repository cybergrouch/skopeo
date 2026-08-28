// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

/**
 * The route pattern is the field everything downstream depends on (#805/#809): it is the log-based
 * metric's grouping label, and it must equal the `transaction` name a future error tracker uses or a
 * metric cannot link to a trace. Getting a raw URI in here silently ruins both — the metric explodes
 * into one series per id, and nothing errors.
 */
class RoutePatternTest {
    @Test
    fun `a path parameter is reported as its pattern, never the concrete value`() =
        testApplication {
            application {
                install(plugin = CallId) { generate { "fixed" } }
                routing {
                    get(path = "/api/v1/matches/{id}/score-correction") {
                        call.respondText(text = routePatternOf(call = call))
                    }
                }
            }

            val body = client.get(urlString = "/api/v1/matches/8f14e45f-ea1a-4b2c-9d3e-000000000001/score-correction").bodyAsText()

            body shouldBe "/api/v1/matches/{id}/score-correction"
            // The whole point: the id must not survive into the label.
            body shouldNotContain "8f14e45f"
        }

    @Test
    fun `routing selectors are stripped so the label is the path alone`() =
        testApplication {
            application {
                install(plugin = CallId) { generate { "fixed" } }
                routing {
                    get(path = "/events/{code}") {
                        call.respondText(text = routePatternOf(call = call))
                    }
                }
            }

            // Ktor's node toString carries a trailing `/(method:GET)`; it must not reach the field.
            val body = client.get(urlString = "/events/ABC123").bodyAsText()
            body shouldBe "/events/{code}"
            body shouldNotContain "method"
        }
}
