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
    val signInProvider = firebase?.get("sign_in_provider") as? String

    @Suppress("UNCHECKED_CAST")
    val identities = firebase?.get("identities") as? Map<String, List<String>>
    val providerUid = identities?.get(signInProvider)?.firstOrNull() ?: payload.subject

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
    val raw = call.parameters[name] ?: throw BadRequestException("Missing path parameter '$name'")
    return try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        throw BadRequestException("Invalid $name '$raw'", e)
    }
}

/** Run a handler, mapping domain/parse failures to the right status code. */
@Suppress("TooGenericExceptionCaught") // intentional catch-all that maps to a 500
internal suspend fun RoutingContext.respondMappingErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ResourceNotFoundException) {
        logger.info { e.message }
        call.respond(HttpStatusCode.NotFound, errorBody(error = "Not found", message = e.message))
    } catch (e: ForbiddenException) {
        logger.warn { "Access denied: ${e.message}" }
        call.respond(HttpStatusCode.Forbidden, errorBody(error = "Forbidden", message = e.message))
    } catch (e: ConflictException) {
        logger.warn { "Conflict: ${e.message}" }
        call.respond(HttpStatusCode.Conflict, errorBody(error = "Conflict", message = e.message))
    } catch (e: IllegalArgumentException) {
        logger.warn(e) { "Invalid request" }
        call.respond(HttpStatusCode.BadRequest, errorBody(error = "Validation error", message = e.message))
    } catch (e: SerializationException) {
        logger.warn(e) { "Malformed JSON" }
        call.respond(HttpStatusCode.BadRequest, errorBody(error = "Invalid JSON", message = e.message))
    } catch (e: BadRequestException) {
        val rootCause = generateSequence<Throwable>(e) { it.cause }.last()
        logger.warn(e) { "Bad request" }
        call.respond(HttpStatusCode.BadRequest, errorBody(error = "Validation error", message = rootCause.message))
    } catch (e: Exception) {
        logger.error(e) { "Unexpected error handling request" }
        call.respond(
            HttpStatusCode.InternalServerError,
            errorBody(error = "Internal server error", message = "An unexpected error occurred"),
        )
    }
}
