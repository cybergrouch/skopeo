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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.user.ClaimRequest
import org.skopeo.dto.user.CreatePlaceholderRequest
import org.skopeo.dto.user.toResponse
import org.skopeo.dto.user.toSummary
import org.skopeo.service.user.PlaceholderService
import java.time.LocalDate

/**
 * Placeholder ("dummy") player accounts + claim/adopt API (#496). Create is HOST/CLUB_OWNER/ADMIN;
 * generate-claim-code is ADMINISTRATOR-only; claim is any authenticated user (enforced in the service).
 * Routes stay thin — parse, delegate, map errors to status codes.
 */
fun Application.configurePlaceholderRoutes(service: PlaceholderService = PlaceholderService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/users") {
                createPlaceholder(service = service)
                listPlaceholders(service = service)
                claimPlaceholder(service = service)
                generateClaimCode(service = service)
            }
        }
    }
}

private fun Route.createPlaceholder(service: PlaceholderService) {
    post(path = "/placeholders") {
        respondMappingErrors {
            val request = call.receive<CreatePlaceholderRequest>()
            val result =
                service.createPlaceholder(
                    token = verifiedToken(),
                    displayName = request.displayName,
                    sex = request.sex,
                    dateOfBirth = request.dateOfBirth?.let { LocalDate.parse(it) },
                )
            respondEither(result = result) { user -> call.respond(status = HttpStatusCode.Created, message = user.toResponse()) }
        }
    }
}

private fun Route.listPlaceholders(service: PlaceholderService) {
    get(path = "/placeholders") {
        respondMappingErrors {
            respondEither(result = service.listPlaceholders(token = verifiedToken())) { players ->
                call.respond(status = HttpStatusCode.OK, message = players.map { it.toSummary() })
            }
        }
    }
}

private fun Route.claimPlaceholder(service: PlaceholderService) {
    post(path = "/claim") {
        respondMappingErrors {
            val request = call.receive<ClaimRequest>()
            respondEither(result = service.claim(token = verifiedToken(), code = request.code)) { user ->
                call.respond(status = HttpStatusCode.OK, message = user.toResponse())
            }
        }
    }
}

private fun Route.generateClaimCode(service: PlaceholderService) {
    post(path = "/{id}/claim-code") {
        respondMappingErrors {
            respondEither(
                result = service.generateClaimCode(token = verifiedToken(), placeholderId = uuidParam(name = "id")),
            ) { generated ->
                call.respond(status = HttpStatusCode.Created, message = generated.toResponse())
            }
        }
    }
}
