// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * The access line, end to end (#805): plugin → logstash marker → the shipped `logback.xml` → JSON.
 *
 * Asserting on the JSON rather than on the log message is the point. `status` and `durationMs` have to
 * arrive as JSON **numbers**, because a Cloud Logging distribution metric cannot compute latency
 * percentiles over a quoted string (#809) — and MDC, the obvious place to put them, stringifies
 * everything. A test that only checked the message text would pass with the fields absent entirely.
 */
class AccessLogTest {
    private lateinit var captured: ListAppender<ILoggingEvent>
    private lateinit var accessLogger: Logger

    @BeforeEach
    fun attach() {
        accessLogger = LoggerFactory.getLogger("org.skopeo.access") as Logger
        captured = ListAppender<ILoggingEvent>().apply { start() }
        accessLogger.addAppender(captured)
    }

    @AfterEach
    fun detach() {
        accessLogger.detachAppender(captured)
        captured.stop()
    }

    /** Encode a captured event through the REAL logback.xml, so the field contract is what ships. */
    private fun asJson(event: ILoggingEvent): JsonObject {
        val context = LoggerContext()
        context.setMDCAdapter(org.slf4j.MDC.getMDCAdapter())
        val resource =
            requireNotNull(value = this::class.java.classLoader.getResource("logback.xml")) {
                "logback.xml is not on the test classpath"
            }
        JoranConfigurator().apply { setContext(context) }.doConfigure(resource)
        val appender =
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE")
                as ConsoleAppender<ILoggingEvent>
        return try {
            Json.parseToJsonElement(string = appender.encoder.encode(event).decodeToString()).jsonObject
        } finally {
            context.stop()
        }
    }

    @Test
    fun `one line per request, carrying method, route, status and duration as typed fields`() =
        testApplication {
            application {
                install(plugin = CallId) { generate { "fixed-id" } }
                install(plugin = RequestLog)
                routing {
                    get(path = "/api/v1/matches/{id}") { call.respondText(text = "ok") }
                }
            }

            client.get(urlString = "/api/v1/matches/8f14e45f-ea1a-4b2c-9d3e-000000000001")

            // Exactly one — a second line would mean CallLogging is also logging (see configureMonitoring).
            captured.list shouldHaveSize 1
            val line = asJson(event = captured.list.single())

            line.getValue(key = "method").jsonPrimitive.content shouldBe "GET"
            line.getValue(key = "route").jsonPrimitive.content shouldBe "/api/v1/matches/{id}"
            // Numbers, not strings: `"200"` would break a distribution metric over these fields.
            line.getValue(key = "status").jsonPrimitive.longOrNull shouldBe 200L
            line.getValue(key = "durationMs").jsonPrimitive.longOrNull.shouldNotBeNull()
        }

    @Test
    fun `an unmatched request collapses into one bounded bucket rather than its raw path`() =
        testApplication {
            application {
                install(plugin = CallId) { generate { "fixed-id" } }
                install(plugin = RequestLog)
                routing {
                    get(path = "/probe") { call.respondText(text = "ok") }
                }
            }

            // Scanners, stale QR links and typos are unbounded and attacker-controlled. Logging the raw
            // path would create a metric series per bogus URL; one bucket answers the useful question.
            client.get(urlString = "/definitely/not/a/route/xyz")

            val line = asJson(event = captured.list.single())
            line.getValue(key = "route").jsonPrimitive.content shouldBe UNMATCHED_ROUTE
            line.getValue(key = "status").jsonPrimitive.longOrNull shouldBe 404L
        }
}
