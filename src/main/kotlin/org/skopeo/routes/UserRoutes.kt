package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.ProfileRequest
import org.skopeo.dto.user.toResponse
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.UserNotFoundException
import org.skopeo.service.user.UserService
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.service.user.toProfilePatch
import java.util.UUID

@Suppress("TopLevelPropertyNaming") // matches the `logger` convention used across the codebase
private val logger = KotlinLogging.logger {}

/**
 * User-management API. Identity is taken from the verified Firebase token; access to
 * a specific user is restricted to that user or an ADMINISTRATOR (enforced in the
 * service). Routes stay thin — parse, delegate, map errors to status codes.
 */
fun Application.configureUserRoutes(service: UserService = UserService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route("/api/v1/users") {
                createUser(service)
                currentUser(service)
                userById(service)
            }
        }
    }
}

private fun Route.createUser(service: UserService) {
    post {
        respondMappingErrors {
            val request = call.receive<CreateUserRequest>()
            val result = service.provision(token = verifiedToken(), request = request)
            val status = if (result.created) HttpStatusCode.Created else HttpStatusCode.OK
            call.respond(status = status, message = result.user.toResponse())
        }
    }
}

private fun Route.currentUser(service: UserService) {
    get("/me") {
        respondMappingErrors {
            val user = service.currentUser(verifiedToken())
            if (user == null) {
                call.respond(
                    status = HttpStatusCode.NotFound,
                    message = mapOf("error" to "Not provisioned", "message" to "POST /api/v1/users to create your profile"),
                )
            } else {
                call.respond(status = HttpStatusCode.OK, message = user.toResponse())
            }
        }
    }
}

private fun Route.userById(service: UserService) {
    get("/{id}") {
        respondMappingErrors {
            val user = service.getById(token = verifiedToken(), id = pathId())
            call.respond(status = HttpStatusCode.OK, message = user.toResponse())
        }
    }
    patch("/{id}") {
        respondMappingErrors {
            val patch = call.receive<ProfileRequest>().toProfilePatch()
            val user = service.patchProfile(token = verifiedToken(), id = pathId(), patch = patch)
            call.respond(status = HttpStatusCode.OK, message = user.toResponse())
        }
    }
    put("/{id}") {
        respondMappingErrors {
            val patch = call.receive<ProfileRequest>().toProfilePatch()
            val user = service.replaceProfile(token = verifiedToken(), id = pathId(), patch = patch)
            call.respond(status = HttpStatusCode.OK, message = user.toResponse())
        }
    }
    delete("/{id}") {
        respondMappingErrors {
            service.deactivate(token = verifiedToken(), id = pathId())
            call.respond(status = HttpStatusCode.NoContent, message = "")
        }
    }
}

/** Lift the verified Firebase identity out of the JWT claims (the only place that touches the token shape). */
private fun RoutingContext.verifiedToken(): VerifiedFirebaseToken {
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

private fun RoutingContext.pathId(): UUID {
    val raw = call.parameters["id"] ?: throw BadRequestException("Missing user id")
    return try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        throw BadRequestException("Invalid user id '$raw'", e)
    }
}

private fun errorBody(
    error: String,
    message: String?,
): Map<String, String> = mapOf("error" to error, "message" to (message ?: error))

/** Run a handler, mapping domain/parse failures to the right status code. */
@Suppress("TooGenericExceptionCaught") // intentional catch-all that maps to a 500
private suspend fun RoutingContext.respondMappingErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: UserNotFoundException) {
        logger.info { e.message }
        call.respond(HttpStatusCode.NotFound, errorBody(error = "Not found", message = e.message))
    } catch (e: ForbiddenException) {
        logger.warn { "Access denied: ${e.message}" }
        call.respond(HttpStatusCode.Forbidden, errorBody(error = "Forbidden", message = e.message))
    } catch (e: IllegalArgumentException) {
        logger.warn(e) { "Invalid user request" }
        call.respond(HttpStatusCode.BadRequest, errorBody(error = "Validation error", message = e.message))
    } catch (e: SerializationException) {
        logger.warn(e) { "Malformed JSON in user request" }
        call.respond(HttpStatusCode.BadRequest, errorBody(error = "Invalid JSON", message = e.message))
    } catch (e: BadRequestException) {
        val rootCause = generateSequence<Throwable>(e) { it.cause }.last()
        logger.warn(e) { "Bad user request" }
        call.respond(HttpStatusCode.BadRequest, errorBody(error = "Validation error", message = rootCause.message))
    } catch (e: Exception) {
        logger.error(e) { "Unexpected error handling user request" }
        call.respond(
            HttpStatusCode.InternalServerError,
            errorBody(error = "Internal server error", message = "An unexpected error occurred"),
        )
    }
}
