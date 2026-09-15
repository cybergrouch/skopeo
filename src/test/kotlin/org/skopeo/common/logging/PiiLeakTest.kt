// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import arrow.core.Either
import arrow.core.flatMap
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.postgresql.util.PSQLException
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.configureMonitoring
import org.skopeo.configurePlugins
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.ContactType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.model.VerificationMethod
import org.skopeo.domain.model.VerificationStatus
import org.skopeo.repository.ContactRepository
import org.skopeo.repository.UserRepository
import org.skopeo.repository.persistence.ContactEntity
import org.skopeo.routes.respondMappingErrors
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.uniqueViolation
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.LocalDateTime
import java.util.UUID

/**
 * The #806 go-live gate: nothing personal reaches a log sink.
 *
 * This is the test that makes switching on an error tracker (#810/#811) safe, so it is deliberately
 * strict about *where* it asserts. It captures events at the root logger and then **encodes each one
 * through the shipped `logback.xml`**, checking the resulting JSON. Asserting on the message alone would
 * miss the two ways a value actually escapes: the MDC map, and an exception's own message inside
 * `stack_trace`.
 *
 * The exception-message case has a third form, added in #992 and the reason this class now needs a
 * database: a message composed **inside the JDBC driver**. `Redactable` covers values the application
 * formats; a Postgres unique violation quotes the offending row (`Detail: Key (contact_type,
 * value)=(EMAIL, …)`) with no Kotlin call site anywhere in between, and Exposed logs it before our code
 * sees the exception at all. So the last three tests here assert on both halves of that fix — the value
 * is gone, the constraint name and SQLSTATE are not.
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

    /**
     * The exception Postgres hands back for a second VERIFIED contact with the same value, built the way
     * the driver builds it: from the wire-format ErrorResponse, so `getMessage()` is composed by pgjdbc
     * rather than by this test. That is the whole point of the hazard — the sentence, `Detail:` and all,
     * is assembled inside the driver from column values no Kotlin call site ever touches, which is why
     * `Redactable` has nothing to wrap (#992).
     */
    private fun duplicateVerifiedEmail(): PSQLException =
        uniqueViolation(
            constraint = "uq_contact_verified_value",
            table = "contact_information",
            detail = "Key (contact_type, value)=(EMAIL, $EMAIL) already exists.",
        )

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
    fun `a driver exception quoting an email address reaches the sink without it`() {
        // The hazard in one line: `contact_information.value` IS the email address, and Postgres names the
        // offending tuple in `Detail:`. No Kotlin call site composed that sentence, so `Redactable` has
        // nothing to wrap; the only place left to stop it is between the throwable and the appender.
        LoggerFactory.getLogger("org.skopeo.probe").error("saving the contact failed", duplicateVerifiedEmail())

        leaks().shouldBeEmpty()
        val line = encodedLines().single()
        // Still diagnosable — the constraint name is the useful half of that message, and #989 is the
        // cautionary tale of a constraint violation nobody logged being misdiagnosed as something else.
        line.contains(other = "uq_contact_verified_value") shouldBe true
        line.contains(other = "23505") shouldBe true
        // The driver's wording goes wholesale rather than being scrubbed of its `Detail:` clause, and the
        // call site's own sentence goes with it: a log site that interpolates `e.message` into its own
        // message is exactly the mistake this backstops, so "we wrote it" is not evidence it is clean.
        line.contains(other = "duplicate key value") shouldBe false
        line.contains(other = "saving the contact failed") shouldBe false
    }

    @Test
    fun `the 500 boundary keeps its own wording and still names the constraint`() =
        testApplication {
            application {
                configureMonitoring()
                configurePlugins()
                routing {
                    post(path = "/probe") {
                        // A violation that no repository translated into a ServiceError reaches the
                        // boundary, which logs the throwable (#805).
                        respondMappingErrors { throw duplicateVerifiedEmail() }
                    }
                }
            }

            client.post(urlString = "/probe")

            leaks().shouldBeEmpty()
            // The boundary redacts the throwable itself, so its authored message survives alongside the
            // facts. That is what separates the sanctioned path from the filter backstop above, where the
            // message is dropped because nothing there can vouch for it.
            encodedLines().any {
                it.contains(other = "Unexpected error handling request") && it.contains(other = "uq_contact_verified_value")
            } shouldBe true
        }

    @Test
    fun `a duplicate verified email cannot reach the sink through Exposed retry logging`() {
        // The end-to-end path against a real Postgres, because the copy that matters is not the one our
        // boundary logs: Exposed logs the failed transaction attempt itself, at WARN, with the driver's
        // message, before our code ever sees the exception — and again on each retry. No catch clause can
        // reach that, which is why the guard is registered in `logback.xml` (#992).
        PostgresTestDatabase.start()
        PostgresTestDatabase.truncate()
        val contacts = ContactRepository()
        val holder = provisionUser(uid = "probe-holder")
        val rival = provisionUser(uid = "probe-rival")
        verifyEmail(contacts = contacts, userId = holder).shouldBeRight()

        val clash = verifyEmail(contacts = contacts, userId = rival)

        // The rule itself is untouched: a verified value still belongs to exactly one active contact.
        clash.shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
        leaks().shouldBeEmpty()
        encodedLines().any {
            it.contains(other = "uq_contact_verified_value") && it.contains(other = "23505")
        } shouldBe true
    }

    private fun provisionUser(uid: String): UUID =
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.FIRST, value = "Probe")),
                ),
        ).user.id

    /** Add [EMAIL] to [userId] and verify it — the second call is the one that hits the unique index. */
    private fun verifyEmail(
        contacts: ContactRepository,
        userId: UUID,
    ): Either<ServiceError, ContactEntity> =
        contacts.create(userId = userId, type = ContactType.EMAIL, value = EMAIL, isPrimary = true).flatMap {
            contacts.setVerification(
                id = it.id,
                status = VerificationStatus.VERIFIED,
                method = VerificationMethod.ADMIN_OVERRIDE,
                verifiedBy = userId,
                verifiedAt = LocalDateTime.now(),
            )
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
