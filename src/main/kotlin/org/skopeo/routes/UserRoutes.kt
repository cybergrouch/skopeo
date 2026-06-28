// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.MarkDuplicatesRequest
import org.skopeo.dto.user.ProfileRequest
import org.skopeo.dto.user.toResponse
import org.skopeo.dto.user.toSummary
import org.skopeo.model.NumericRange
import org.skopeo.service.user.DuplicateService
import org.skopeo.service.user.UserSearchFilters
import org.skopeo.service.user.UserService
import org.skopeo.service.user.toProfilePatch
import java.util.UUID

/**
 * User-management API. Identity is taken from the verified Firebase token; access to
 * a specific user is restricted to that user or an ADMINISTRATOR (enforced in the
 * service). Routes stay thin — parse, delegate, map errors to status codes.
 */
fun Application.configureUserRoutes(
    service: UserService = UserService(),
    duplicates: DuplicateService = DuplicateService(),
) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/users") {
                searchUsers(service = service)
                createUser(service = service)
                currentUser(service = service)
                duplicateRoutes(service = duplicates)
                userById(service = service)
            }
        }
    }
}

/**
 * Duplicate-profile rectification (#124) — ADMINISTRATOR-only (enforced in [DuplicateService]).
 * Registered before [userById] so `/{id}/duplicates` and `/{id}/duplicate` resolve ahead of `/{id}`.
 */
private fun Route.duplicateRoutes(service: DuplicateService) {
    post(path = "/{id}/duplicates") {
        respondMappingErrors {
            val request = call.receive<MarkDuplicatesRequest>()
            respondEither(
                result =
                    service.markDuplicates(
                        token = verifiedToken(),
                        canonicalId = uuidParam(name = "id"),
                        duplicateIds = parseIds(raw = request.duplicateIds.joinToString(separator = ",")),
                    ),
            ) { duplicates -> call.respond(status = HttpStatusCode.OK, message = duplicates.map { it.toSummary() }) }
        }
    }
    get(path = "/{id}/duplicates") {
        respondMappingErrors {
            respondEither(result = service.duplicatesOf(token = verifiedToken(), canonicalId = uuidParam(name = "id"))) { duplicates ->
                call.respond(status = HttpStatusCode.OK, message = duplicates.map { it.toSummary() })
            }
        }
    }
    delete(path = "/{id}/duplicate") {
        respondMappingErrors {
            respondEither(result = service.restore(token = verifiedToken(), id = uuidParam(name = "id"))) {
                call.respond(status = HttpStatusCode.NoContent, message = "")
            }
        }
    }
}

private val FILTER_PARAMS = listOf("name", "code", "q", "sex", "age", "rating")

private fun Route.searchUsers(service: UserService) {
    get {
        respondMappingErrors {
            val params = call.request.queryParameters
            val ids = params["ids"]
            val results =
                if (ids != null) {
                    // Id resolution is its own exclusive mode — it can't be combined with filters.
                    require(value = FILTER_PARAMS.none { params[it] != null }) {
                        "'ids' cannot be combined with other filters"
                    }
                    service.findByIds(token = verifiedToken(), ids = parseIds(raw = ids))
                } else {
                    service.search(
                        token = verifiedToken(),
                        filters =
                            UserSearchFilters(
                                name = params["name"],
                                code = params["code"],
                                q = params["q"],
                                sex = validatedSex(value = params["sex"]),
                                age = params["age"]?.let { NumericRange.parse(raw = it) },
                                rating = params["rating"]?.let { NumericRange.parse(raw = it) },
                            ),
                    )
                }
            respondEither(result = results) { users ->
                val ratings = service.currentRatings(ids = users.map { it.id })
                call.respond(status = HttpStatusCode.OK, message = users.map { it.toSummary(rating = ratings[it.id]) })
            }
        }
    }
}

private val ALLOWED_SEXES = setOf("Male", "Female")

/** Validate the sex facet at the boundary (#116): absent, or one of the allowed values; else a 400. */
private fun validatedSex(value: String?): String? {
    if (value == null) return null
    require(value = value in ALLOWED_SEXES) { "Invalid sex '$value'; expected one of $ALLOWED_SEXES" }
    return value
}

/** Parse a comma-separated list of UUIDs; any malformed id is a 400. */
private fun parseIds(raw: String): List<UUID> =
    raw
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { value ->
            try {
                UUID.fromString(value)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid user id '$value'", e)
            }
        }

private fun Route.createUser(service: UserService) {
    post {
        respondMappingErrors {
            val request = call.receive<CreateUserRequest>()
            respondEither(result = service.provision(token = verifiedToken(), request = request)) { result ->
                val status = if (result.created) HttpStatusCode.Created else HttpStatusCode.OK
                call.respond(status = status, message = result.user.toResponse())
            }
        }
    }
}

private fun Route.currentUser(service: UserService) {
    get(path = "/me") {
        respondMappingErrors {
            val user = service.currentUser(token = verifiedToken())
            if (user == null) {
                call.respond(
                    status = HttpStatusCode.NotFound,
                    message = errorBody(error = "Not provisioned", message = "POST /api/v1/users to create your profile"),
                )
            } else {
                call.respond(status = HttpStatusCode.OK, message = user.toResponse())
            }
        }
    }
}

private fun Route.userById(service: UserService) {
    get(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.getById(token = verifiedToken(), id = uuidParam(name = "id"))) { user ->
                call.respond(status = HttpStatusCode.OK, message = user.toResponse())
            }
        }
    }
    patch(path = "/{id}") {
        respondMappingErrors {
            val patch = call.receive<ProfileRequest>().toProfilePatch()
            respondEither(result = service.patchProfile(token = verifiedToken(), id = uuidParam(name = "id"), patch = patch)) { user ->
                call.respond(status = HttpStatusCode.OK, message = user.toResponse())
            }
        }
    }
    put(path = "/{id}") {
        respondMappingErrors {
            val patch = call.receive<ProfileRequest>().toProfilePatch()
            respondEither(result = service.replaceProfile(token = verifiedToken(), id = uuidParam(name = "id"), patch = patch)) { user ->
                call.respond(status = HttpStatusCode.OK, message = user.toResponse())
            }
        }
    }
    delete(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.deactivate(token = verifiedToken(), id = uuidParam(name = "id"))) {
                call.respond(status = HttpStatusCode.NoContent, message = "")
            }
        }
    }
}
