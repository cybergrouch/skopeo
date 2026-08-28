// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/**
 * Exercises the REAL `src/main/resources/logback.xml` (#751), not a hand-built encoder.
 *
 * The field names in that file are a contract with Cloud Logging: `severity` drives filtering and
 * alerting, and `stack_trace` is the whole of the Cloud Error Reporting integration — rename it and
 * exception grouping silently stops without anything failing. A test that constructed its own encoder
 * would keep passing through exactly the edit that breaks production, so this one loads the shipped
 * config and encodes through it.
 */
class StructuredLogFormatTest {
    private fun encode(
        level: Level,
        message: String,
        throwable: Throwable? = null,
        mdc: Map<String, String> = emptyMap(),
    ): JsonObject {
        val context = LoggerContext()
        // A bare LoggerContext has no MDC adapter — SLF4J's binding normally installs it — and
        // logstash's MDC provider dereferences it unconditionally. Share the global adapter so the
        // MDC.put calls below are the ones the encoder reads.
        context.setMDCAdapter(MDC.getMDCAdapter())
        val resource =
            requireNotNull(value = this::class.java.classLoader.getResource("logback.xml")) {
                "logback.xml is not on the test classpath"
            }
        JoranConfigurator().apply { setContext(context) }.doConfigure(resource)

        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val appender = root.getAppender("CONSOLE") as ConsoleAppender<ILoggingEvent>

        mdc.forEach { (key, value) -> MDC.put(key, value) }
        try {
            val event =
                LoggingEvent(
                    "org.skopeo.Probe",
                    context.getLogger("org.skopeo.Probe"),
                    level,
                    message,
                    throwable,
                    emptyArray(),
                )
            return Json.parseToJsonElement(string = appender.encoder.encode(event).decodeToString()).jsonObject
        } finally {
            MDC.clear()
            context.stop()
        }
    }

    @Test
    fun `every line is a JSON object carrying severity, message and logger`() {
        val line = encode(level = Level.INFO, message = "provisioned")

        line["severity"].shouldNotBeNull().jsonPrimitive.content shouldBe "INFO"
        line["message"].shouldNotBeNull().jsonPrimitive.content shouldBe "provisioned"
        line["logger"].shouldNotBeNull().jsonPrimitive.content shouldBe "org.skopeo.Probe"
    }

    @Test
    fun `a WARN line is emitted as severity WARNING, so severity filtering finds it`() {
        // The regression this whole file exists for: `%level` would emit "WARN", which is not a
        // LogSeverity, so Cloud Logging would file every warning as DEFAULT.
        encode(level = Level.WARN, message = "slow").getValue(key = "severity").jsonPrimitive.content shouldBe "WARNING"
    }

    @Test
    fun `an exception lands in stack_trace, the field Cloud Error Reporting keys on`() {
        val line = encode(level = Level.ERROR, message = "boom", throwable = ProbeFailure(message = "kaboom"))

        line.getValue(key = "severity").jsonPrimitive.content shouldBe "ERROR"
        val trace = line["stack_trace"].shouldNotBeNull().jsonPrimitive.content
        // The class name and the message both have to survive, or the trace is not actionable.
        (trace.contains(other = "ProbeFailure") && trace.contains(other = "kaboom")) shouldBe true
    }

    @Test
    fun `allowlisted MDC entries are emitted flat as top-level fields, not nested under mdc`() {
        // Flatness is what makes them queryable in the Logs Explorer and usable as log-based metric
        // labels (#809) — nested under an `mdc` object they would need a different query and could not
        // be used as labels directly.
        //
        // Only allowlisted keys survive (#806), and `route` is deliberately NOT one: it travels on the
        // access line as a logstash marker (#805) so it can stay a typed field alongside numeric
        // `status`/`durationMs`. PiiLeakTest covers the dropping of an unlisted key.
        val line =
            encode(
                level = Level.INFO,
                message = "handled",
                mdc = mapOf(LogFields.REQUEST_ID to "probe-1", LogFields.TRACE to "projects/p/traces/t"),
            )

        line.getValue(key = LogFields.REQUEST_ID).jsonPrimitive.content shouldBe "probe-1"
        line.getValue(key = LogFields.TRACE).jsonPrimitive.content shouldBe "projects/p/traces/t"
        line.keys shouldNotContain "mdc"
    }

    @Test
    fun `a line with no exception carries no stack_trace field at all`() {
        // An empty-string stack_trace would make Error Reporting treat ordinary INFO lines as errors.
        encode(level = Level.INFO, message = "fine").keys shouldNotContain "stack_trace"
    }
}

/**
 * A Kotlin-declared exception so the test can name the constructor argument, which the repo's
 * `NamedArguments` rule requires and a Java constructor cannot accept.
 */
private class ProbeFailure(message: String) : IllegalStateException(message)
