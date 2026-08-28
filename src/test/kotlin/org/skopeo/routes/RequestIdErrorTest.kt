// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.skopeo.common.logging.REQUEST_ID_HEADER
import org.skopeo.configureMonitoring
import org.skopeo.configurePlugins

/**
 * Request-id propagation and the two independent 500 paths (#805).
 *
 * The second is the part worth guarding. `respondMappingErrors` ends in `catch (e: Exception)`, and 28 of
 * 31 route files route through it — so it **swallows** the exception and `StatusPages` never sees it.
 * Treating StatusPages as "the place 500s are handled" would have left the large majority of 500s with
 * no request id at all, and nothing would have failed.
 */
class RequestIdErrorTest {
    private fun requestIdOf(body: String): String =
        Json.parseToJsonElement(string = body).jsonObject.getValue(key = "requestId").jsonPrimitive.content

    @Test
    fun `an inbound request id is echoed back, so a caller can correlate its own traffic`() =
        testApplication {
            application { configureMonitoring() }

            val response = client.get(urlString = "/nope") { header(key = REQUEST_ID_HEADER, value = "probe-1") }

            response.headers[REQUEST_ID_HEADER] shouldBe "probe-1"
        }

    @Test
    fun `a missing request id is generated, so every request is correlatable`() =
        testApplication {
            application { configureMonitoring() }

            val id = client.get(urlString = "/nope").headers[REQUEST_ID_HEADER]

            // A UUID, not an empty string or a counter — it has to be unique across instances.
            id.orEmpty() shouldMatch Regex(pattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        }

    @Test
    fun `a blank inbound request id is rejected and replaced rather than echoed`() =
        testApplication {
            application { configureMonitoring() }

            val response = client.get(urlString = "/nope") { header(key = REQUEST_ID_HEADER, value = "   ") }

            response.headers[REQUEST_ID_HEADER].orEmpty().isNotBlank() shouldBe true
        }

    @Test
    fun `a 500 from a route that handles its own errors still carries the request id`() =
        testApplication {
            application {
                configureMonitoring()
                configurePlugins()
                routing {
                    get(path = "/boom-wrapped") {
                        respondMappingErrors { throw ProbeBoom(message = "wrapped") }
                    }
                }
            }

            val response = client.get(urlString = "/boom-wrapped") { header(key = REQUEST_ID_HEADER, value = "probe-w") }

            response.status shouldBe HttpStatusCode.InternalServerError
            // Same id in the body and the header: the body is for the human, the header for machines.
            requestIdOf(body = response.bodyAsText()) shouldBe "probe-w"
            response.headers[REQUEST_ID_HEADER] shouldBe "probe-w"
        }

    @Test
    fun `a 500 from an exception that escapes the route is caught by the backstop with the request id`() =
        testApplication {
            application {
                configureMonitoring()
                configurePlugins()
                routing {
                    // No local handling at all — the shape of OpenGraphRoutes, which before #805 produced
                    // a bare Ktor 500 that reached no logger.
                    get(path = "/boom-raw") { throw ProbeBoom(message = "escaped") }
                }
            }

            val response = client.get(urlString = "/boom-raw") { header(key = REQUEST_ID_HEADER, value = "probe-r") }

            response.status shouldBe HttpStatusCode.InternalServerError
            requestIdOf(body = response.bodyAsText()) shouldBe "probe-r"
        }

    @Test
    fun `a 500 body never leaks the underlying failure to the caller`() =
        testApplication {
            application {
                configureMonitoring()
                configurePlugins()
                routing {
                    get(path = "/boom-secret") { throw ProbeBoom(message = "connection string admin@db") }
                }
            }

            val body = client.get(urlString = "/boom-secret").bodyAsText()

            // The trace goes to the log; the caller gets a reference and nothing else.
            body.contains(other = "admin@db") shouldBe false
            body.contains(other = "ProbeBoom") shouldBe false
        }
}

/**
 * A Kotlin-declared exception so the constructor argument can be named, which the repo's
 * `NamedArguments` rule requires and a Java constructor cannot accept.
 */
private class ProbeBoom(message: String) : IllegalStateException(message)
