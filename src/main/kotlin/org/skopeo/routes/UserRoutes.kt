// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("TooManyFunctions") // Many small, cohesive route-registration helpers; splitting the file adds no clarity.

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
import org.skopeo.common.dto.user.CreateUserRequest
import org.skopeo.common.dto.user.MarkDuplicatesRequest
import org.skopeo.common.dto.user.MatchHistoryVisibilityRequest
import org.skopeo.common.dto.user.PhotoSettingsRequest
import org.skopeo.common.dto.user.ProfileRequest
import org.skopeo.common.dto.user.RatingPreviewResponse
import org.skopeo.common.dto.user.SetRatingPreviewRequest
import org.skopeo.domain.service.user.DuplicateService
import org.skopeo.domain.service.user.UserSearchFilters
import org.skopeo.domain.service.user.UserService
import org.skopeo.domain.service.user.toProfilePatch
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
                searchUsersPaged(service = service)
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
            ) { duplicates -> call.respond(status = HttpStatusCode.OK, message = duplicates) }
        }
    }
    get(path = "/{id}/duplicates") {
        respondMappingErrors {
            respondEither(result = service.duplicatesOf(token = verifiedToken(), canonicalId = uuidParam(name = "id"))) { duplicates ->
                call.respond(status = HttpStatusCode.OK, message = duplicates)
            }
        }
    }
    // Replace Account (#124): import a marked duplicate's history + rating into the canonical ({id}),
    // then delete the old account.
    post(path = "/{id}/duplicates/{duplicateId}/replace") {
        respondMappingErrors {
            respondEither(
                result =
                    service.replaceAccount(
                        token = verifiedToken(),
                        canonicalId = uuidParam(name = "id"),
                        duplicateId = uuidParam(name = "duplicateId"),
                    ),
            ) { canonical -> call.respond(status = HttpStatusCode.OK, message = canonical) }
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

private val FILTER_PARAMS = listOf("name", "code", "q", "sex", "age", "rating", "capability")

// Page size used when a search request omits `limit` (preserves the pre-pagination behaviour).
private const val DEFAULT_SEARCH_PAGE_SIZE = 20

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
                                age = params["age"],
                                rating = params["rating"],
                                capability = params["capability"],
                            ),
                        limit = params["limit"]?.toIntOrNull() ?: DEFAULT_SEARCH_PAGE_SIZE,
                        offset = params["offset"]?.toIntOrNull() ?: 0,
                        includeInactive = params["includeInactive"]?.toBoolean() ?: false,
                    )
                }
            respondEither(result = results) { users ->
                call.respond(status = HttpStatusCode.OK, message = users)
            }
        }
    }
}

/**
 * Paged player search for numbered pagination (#232): same filters as the list search but returns
 * `{ items, total }` so the UI can show the current page, page links, and the total. Separate from the
 * bare-list search above, which the typeahead/seeding/id-resolution callers keep using.
 */
private fun Route.searchUsersPaged(service: UserService) {
    get(path = "/search") {
        respondMappingErrors {
            val params = call.request.queryParameters
            val page =
                service.searchPage(
                    token = verifiedToken(),
                    filters =
                        UserSearchFilters(
                            name = params["name"],
                            code = params["code"],
                            q = params["q"],
                            sex = validatedSex(value = params["sex"]),
                            age = params["age"],
                            rating = params["rating"],
                            capability = params["capability"],
                        ),
                    limit = params["limit"]?.toIntOrNull() ?: DEFAULT_SEARCH_PAGE_SIZE,
                    offset = params["offset"]?.toIntOrNull() ?: 0,
                    // Research opts in to include soft-deleted accounts (flagged), #518.
                    includeInactive = params["includeInactive"]?.toBoolean() ?: false,
                )
            respondEither(result = page) { result ->
                call.respond(status = HttpStatusCode.OK, message = result)
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
                call.respond(status = status, message = result.user)
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
                call.respond(status = HttpStatusCode.OK, message = user)
            }
        }
    }
    // Per-admin "preview ratings as non-admin" toggle (#583): ADMINISTRATOR-only (enforced in the service).
    put(path = "/me/rating-preview") {
        respondMappingErrors {
            val body = call.receive<SetRatingPreviewRequest>()
            respondEither(
                result = service.setRatingPreview(token = verifiedToken(), previewAsNonAdmin = body.previewAsNonAdmin),
            ) { value -> call.respond(status = HttpStatusCode.OK, message = RatingPreviewResponse(previewAsNonAdmin = value)) }
        }
    }
}

private fun Route.userById(service: UserService) {
    get(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.getById(token = verifiedToken(), id = uuidParam(name = "id"))) { user ->
                call.respond(status = HttpStatusCode.OK, message = user)
            }
        }
    }
    patch(path = "/{id}") {
        respondMappingErrors {
            val patch = call.receive<ProfileRequest>().toProfilePatch()
            respondEither(result = service.patchProfile(token = verifiedToken(), id = uuidParam(name = "id"), patch = patch)) { user ->
                call.respond(status = HttpStatusCode.OK, message = user)
            }
        }
    }
    put(path = "/{id}") {
        respondMappingErrors {
            val patch = call.receive<ProfileRequest>().toProfilePatch()
            respondEither(result = service.replaceProfile(token = verifiedToken(), id = uuidParam(name = "id"), patch = patch)) { user ->
                call.respond(status = HttpStatusCode.OK, message = user)
            }
        }
    }
    put(path = "/{id}/photo") {
        respondMappingErrors {
            val body = call.receive<PhotoSettingsRequest>()
            // Blank clears the custom photo (revert to provider); validation of a non-blank URL happens
            // in the request's init block.
            val customPhotoUrl = body.customPhotoUrl?.trim()?.ifEmpty { null }
            val result =
                service.updatePhotoSettings(
                    token = verifiedToken(),
                    id = uuidParam(name = "id"),
                    customPhotoUrl = customPhotoUrl,
                    photoHidden = body.hidden,
                )
            respondEither(result = result) { user ->
                call.respond(status = HttpStatusCode.OK, message = user)
            }
        }
    }
    matchHistoryVisibility(service = service)
    delete(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.deactivate(token = verifiedToken(), id = uuidParam(name = "id"))) {
                call.respond(status = HttpStatusCode.NoContent, message = "")
            }
        }
    }
    // Admin-only "Allow login": re-enable a soft-deleted account (#518).
    post(path = "/{id}/reactivate") {
        respondMappingErrors {
            respondEither(result = service.reactivate(token = verifiedToken(), id = uuidParam(name = "id"))) {
                call.respond(status = HttpStatusCode.NoContent, message = "")
            }
        }
    }
}

/** Self-or-ADMINISTRATOR toggle of the match-history privacy flag (#622). */
private fun Route.matchHistoryVisibility(service: UserService) {
    put(path = "/{id}/match-history-visibility") {
        respondMappingErrors {
            val body = call.receive<MatchHistoryVisibilityRequest>()
            respondEither(
                result = service.setMatchHistoryHidden(token = verifiedToken(), id = uuidParam(name = "id"), hidden = body.hidden),
            ) { user -> call.respond(status = HttpStatusCode.OK, message = user) }
        }
    }
}
