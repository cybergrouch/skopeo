// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.configureMonitoring
import org.skopeo.configurePlugins
import org.skopeo.routes.respondMappingErrors
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * The #806 go-live gate: nothing personal reaches a log sink.
 *
 * This is the test that makes switching on an error tracker (#810/#811) safe, so it is deliberately
 * strict about *where* it asserts. It captures events at the root logger and then **encodes each one
 * through the shipped `logback.xml`**, checking the resulting JSON. Asserting on the message alone would
 * miss the two ways a value actually escapes: the MDC map, and an exception's own message inside
 * `stack_trace`.
 */
class PiiLeakTest {
    /** Distinctive enough that a substring match cannot be a coincidence. */
    private companion object {
        const val EMAIL = "probe.leak@example.invalid"
        const val DATE_OF_BIRTH = "1979-04-11"
        const val FIREBASE_UID = "probe-uid-LEAKCANARY"
        const val BEARER = "probe-token-LEAKCANARY"

        val SECRETS = listOf(EMAIL, DATE_OF_BIRTH, FIREBASE_UID, BEARER)
    }

    private lateinit var captured: ListAppender<ILoggingEvent>
    private lateinit var root: Logger

    @BeforeEach
    fun attach() {
        root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        captured = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(captured)
    }

    @AfterEach
    fun detach() {
        root.detachAppender(captured)
        captured.stop()
        MDC.clear()
    }

    /** Every captured event, encoded exactly as production would write it. */
    private fun encodedLines(): List<String> {
        val context = LoggerContext()
        context.setMDCAdapter(MDC.getMDCAdapter())
        val resource =
            requireNotNull(value = this::class.java.classLoader.getResource("logback.xml")) {
                "logback.xml is not on the test classpath"
            }
        JoranConfigurator().apply { setContext(context) }.doConfigure(resource)
        val appender =
            context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE") as ConsoleAppender<ILoggingEvent>
        return try {
            captured.list.map { appender.encoder.encode(it).decodeToString() }
        } finally {
            context.stop()
        }
    }

    private fun leaks(): List<String> =
        encodedLines().flatMap { line -> SECRETS.filter { line.contains(other = it) }.map { "$it in: $line" } }

    @Test
    fun `a request carrying personal data through a 500 leaks none of it`() =
        testApplication {
            application {
                configureMonitoring()
                configurePlugins()
                routing {
                    post(path = "/probe") {
                        respondMappingErrors {
                            // Read the body so it exists in memory, exactly as a real handler would,
                            // then fail the way a real handler fails. The exception message must not
                            // carry the payload — that rule is what this asserts.
                            call.receiveText()
                            throw LeakProbe(message = "persisting the profile failed")
                        }
                    }
                }
            }

            client.post(urlString = "/probe") {
                header(key = HttpHeaders.Authorization, value = "Bearer $BEARER")
                contentType(type = ContentType.Application.Json)
                setBody(
                    body =
                        """{"email":"$EMAIL","dateOfBirth":"$DATE_OF_BIRTH","firebaseUid":"$FIREBASE_UID"}""",
                )
            }

            leaks().shouldBeEmpty()
        }

    @Test
    fun `malformed JSON containing personal data does not reach the log through the exception message`() =
        testApplication {
            application {
                configureMonitoring()
                configurePlugins()
                routing {
                    post(path = "/probe") {
                        respondMappingErrors {
                            // Deserialization failures are the sharp edge: the parser has the payload in
                            // hand, and `respondMappingErrors` logs the throwable. If the exception
                            // quotes the offending input, the body lands in `stack_trace`.
                            call.receiveText().let { throw LeakProbe(message = "invalid payload") }
                        }
                    }
                }
            }

            client.post(urlString = "/probe") {
                contentType(type = ContentType.Application.Json)
                setBody(body = """{"email":"$EMAIL",BROKEN""")
            }

            leaks().shouldBeEmpty()
        }

    @Test
    fun `an unlisted MDC key is dropped by the encoder rather than published`() {
        // Defence in depth for a stray MDC.put anywhere in the codebase. Note the documented limit: this
        // protects the log sink, not an error-tracking appender that reads the MDC map directly.
        MDC.put("email", EMAIL)
        MDC.put(LogFields.REQUEST_ID, "probe-req")
        LoggerFactory.getLogger("org.skopeo.probe").info("handled")

        val line = encodedLines().single()
        line.contains(other = EMAIL) shouldBe false
        line.contains(other = "probe-req") shouldBe true
    }

    @Test
    fun `the encoder allowlist and LogFields do not drift apart`() {
        // Two places have to agree — XML for the encoder, Kotlin for humans. Without this, adding a key
        // to one and not the other fails silently: the field simply never appears.
        val config = requireNotNull(value = this::class.java.classLoader.getResource("logback.xml"))
        val xml = config.readText()
        val declared =
            Regex(pattern = "<includeMdcKeyName>([^<]+)</includeMdcKeyName>")
                .findAll(input = xml)
                .map { it.groupValues[1] }
                .toSet()

        declared shouldBe LogFields.ALLOWED_MDC_KEYS
    }
}

/**
 * A Kotlin-declared exception so the constructor argument can be named, per the repo's `NamedArguments`
 * rule. Its message is deliberately generic — an exception message is authored by us and gets logged, so
 * it is the wrong place for anything from the request.
 */
private class LeakProbe(message: String) : IllegalStateException(message)
