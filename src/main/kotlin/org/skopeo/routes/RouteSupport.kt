// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import org.skopeo.model.ServiceError
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

@Suppress("TopLevelPropertyNaming") // matches the `logger` convention used across the codebase
private val logger = KotlinLogging.logger {}

internal fun errorBody(
    error: String,
    message: String?,
): Map<String, String> = mapOf("error" to error, "message" to (message ?: error))

/** Lift the verified Firebase identity out of the JWT claims (the only place that touches the token shape). */
internal fun RoutingContext.verifiedToken(): VerifiedFirebaseToken = call.principal<JWTPrincipal>()!!.toVerifiedToken()

/**
 * The verified identity when present, or null on an anonymous request (#193). For routes under
 * `authenticate(FIREBASE_AUTH, optional = true)`, where a token is used if supplied but not required.
 */
internal fun RoutingContext.optionalVerifiedToken(): VerifiedFirebaseToken? = call.principal<JWTPrincipal>()?.toVerifiedToken()

private fun JWTPrincipal.toVerifiedToken(): VerifiedFirebaseToken {
    val firebase = payload.getClaim("firebase").asMap()
    val signInProvider = firebase?.get(key = "sign_in_provider") as? String

    @Suppress("UNCHECKED_CAST")
    val identities = firebase?.get(key = "identities") as? Map<String, List<String>>
    val providerUid = identities?.get(key = signInProvider)?.firstOrNull() ?: payload.subject

    return VerifiedFirebaseToken(
        uid = payload.subject,
        email = payload.getClaim("email").asString(),
        emailVerified = payload.getClaim("email_verified").asBoolean() ?: false,
        name = payload.getClaim("name").asString(),
        picture = payload.getClaim("picture").asString(),
        signInProvider = signInProvider,
        providerUid = providerUid,
    )
}

internal fun RoutingContext.uuidParam(name: String): UUID {
    val raw = call.parameters[name] ?: throw BadRequestException(message = "Missing path parameter '$name'")
    return try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        throw BadRequestException(message = "Invalid $name '$raw'", cause = e)
    }
}

/**
 * Parse [value] into enum [T] at the request boundary (issue #116) — a 400 when it isn't a valid
 * member. Services then receive the typed value and don't re-validate the request shape.
 */
internal inline fun <reified T : Enum<T>> parseEnumParam(
    value: String,
    field: String,
): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: throw BadRequestException(
            message = "Invalid $field '$value'; expected one of ${enumValues<T>().joinToString { it.name }}",
        )

/**
 * Map a service/repository [ServiceError] (the Arrow `Either` left, issue #115) to its HTTP response.
 * This is the single place the error taxonomy meets the transport layer.
 */
internal suspend fun RoutingContext.respondError(error: ServiceError) {
    // Every ServiceError (an Either left, #115) is logged here — the one sink where the error taxonomy
    // meets the transport — so EVERY handled failure is traceable, not just uncaught exceptions. Logged
    // at WARN with request context (method + path + error type) so it surfaces regardless of the prod
    // log level (previously NotFound logged at INFO and was easily filtered out) and points at the call.
    logger.warn { "${call.request.httpMethod.value} ${call.request.path()} → ${error::class.simpleName}: ${error.message}" }
    when (error) {
        is ServiceError.NotFound ->
            call.respond(status = HttpStatusCode.NotFound, message = errorBody(error = "Not found", message = error.message))
        is ServiceError.Forbidden ->
            call.respond(status = HttpStatusCode.Forbidden, message = errorBody(error = "Forbidden", message = error.message))
        is ServiceError.Conflict ->
            call.respond(status = HttpStatusCode.Conflict, message = errorBody(error = "Conflict", message = error.message))
        is ServiceError.Validation ->
            call.respond(status = HttpStatusCode.BadRequest, message = errorBody(error = "Validation error", message = error.message))
        is ServiceError.AccountMerged -> {
            // A merged duplicate's sign-in (#124): 403 + the canonical code so the client can link to it.
            val base = errorBody(error = "Account merged", message = error.message)
            val body = error.canonicalPublicCode?.let { base + ("canonicalCode" to it) } ?: base
            call.respond(status = HttpStatusCode.Forbidden, message = body)
        }
        is ServiceError.AccountDeleted ->
            // An admin-deleted account's sign-in (#518): 403 with a contact-an-administrator message.
            call.respond(status = HttpStatusCode.Forbidden, message = errorBody(error = "Account deleted", message = error.message))
    }
}

/**
 * Fold a service [result]: on the right, run [onSuccess] to write the success response; on the left,
 * map the [ServiceError] to its status via [respondError]. Wrap calls in [respondMappingErrors] so
 * request-shape failures (bad JSON, DTO-init validation, parse errors) and bugs still map correctly.
 */
internal suspend fun <T> RoutingContext.respondEither(
    result: Either<ServiceError, T>,
    onSuccess: suspend (T) -> Unit,
) {
    result.fold(ifLeft = { respondError(error = it) }, ifRight = { onSuccess(it) })
}

/**
 * Run a handler, mapping request-shape failures and bugs to the right status code. Expected domain
 * failures now flow through [ServiceError]/[respondEither]; this catches what remains: malformed JSON,
 * DTO/model `init` validation (`IllegalArgumentException`), boundary parse errors (`BadRequestException`
 * from `uuidParam`/`parseEnumParam`), and anything unexpected (→ 500).
 */
@Suppress("TooGenericExceptionCaught") // intentional catch-all that maps to a 500
internal suspend fun RoutingContext.respondMappingErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: SerializationException) {
        // Must precede IllegalArgumentException: kotlinx SerializationException is a subtype of it.
        logger.warn(t = e) { "Malformed JSON" }
        call.respond(status = HttpStatusCode.BadRequest, message = errorBody(error = "Invalid JSON", message = e.message))
    } catch (e: IllegalArgumentException) {
        logger.warn(t = e) { "Invalid request" }
        call.respond(status = HttpStatusCode.BadRequest, message = errorBody(error = "Validation error", message = e.message))
    } catch (e: BadRequestException) {
        val rootCause = generateSequence<Throwable>(seed = e) { it.cause }.last()
        logger.warn(t = e) { "Bad request" }
        call.respond(status = HttpStatusCode.BadRequest, message = errorBody(error = "Validation error", message = rootCause.message))
    } catch (e: Exception) {
        logger.error(t = e) { "Unexpected error handling request" }
        call.respond(
            status = HttpStatusCode.InternalServerError,
            message = errorBody(error = "Internal server error", message = "An unexpected error occurred"),
        )
    }
}
