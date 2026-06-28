// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import org.skopeo.service.ConflictException
import org.skopeo.service.ResourceNotFoundException
import org.skopeo.service.user.AccountMergedException
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

@Suppress("TopLevelPropertyNaming") // matches the `logger` convention used across the codebase
private val logger = KotlinLogging.logger {}

internal fun errorBody(
    error: String,
    message: String?,
): Map<String, String> = mapOf("error" to error, "message" to (message ?: error))

/** Lift the verified Firebase identity out of the JWT claims (the only place that touches the token shape). */
internal fun RoutingContext.verifiedToken(): VerifiedFirebaseToken {
    val payload = call.principal<JWTPrincipal>()!!.payload
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

/** Run a handler, mapping domain/parse failures to the right status code. */
@Suppress("TooGenericExceptionCaught") // intentional catch-all that maps to a 500
internal suspend fun RoutingContext.respondMappingErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ResourceNotFoundException) {
        logger.info { e.message }
        call.respond(status = HttpStatusCode.NotFound, message = errorBody(error = "Not found", message = e.message))
    } catch (e: AccountMergedException) {
        // A merged duplicate's sign-in (#124): 403 + the canonical code so the client can link to it.
        logger.warn { "Account merged: ${e.message}" }
        val base = errorBody(error = "Account merged", message = e.message)
        val body = e.canonicalPublicCode?.let { base + ("canonicalCode" to it) } ?: base
        call.respond(status = HttpStatusCode.Forbidden, message = body)
    } catch (e: ForbiddenException) {
        logger.warn { "Access denied: ${e.message}" }
        call.respond(status = HttpStatusCode.Forbidden, message = errorBody(error = "Forbidden", message = e.message))
    } catch (e: ConflictException) {
        logger.warn { "Conflict: ${e.message}" }
        call.respond(status = HttpStatusCode.Conflict, message = errorBody(error = "Conflict", message = e.message))
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
