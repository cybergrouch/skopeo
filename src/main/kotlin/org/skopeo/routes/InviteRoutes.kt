// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.invite.CreateInviteRequest
import org.skopeo.service.invite.InviteService

private const val DEFAULT_INVITE_PAGE_SIZE = 20

/** Validate + normalize the invite email at the boundary (#116): trimmed, lower-cased, with an '@'; else a 400. */
private fun validatedEmail(raw: String): String {
    val normalized = raw.trim().lowercase()
    require(value = normalized.isNotBlank() && normalized.contains(char = '@')) { "A valid email is required" }
    return normalized
}

/**
 * Admin-only onboarding invites (issue #74). All endpoints require ADMINISTRATOR (enforced in
 * [InviteService]). The email-link itself is sent client-side via Firebase; these routes only
 * record/list/revoke the invite the provisioning gate checks.
 */
fun Application.configureInviteRoutes(service: InviteService = InviteService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/invites") {
                post {
                    respondMappingErrors {
                        val request = call.receive<CreateInviteRequest>()
                        respondEither(
                            result = service.create(token = verifiedToken(), email = validatedEmail(raw = request.email)),
                        ) { invite -> call.respond(status = HttpStatusCode.Created, message = invite) }
                    }
                }
                get {
                    respondMappingErrors {
                        val params = call.request.queryParameters
                        respondEither(
                            result =
                                service.list(
                                    token = verifiedToken(),
                                    limit = params["limit"]?.toIntOrNull() ?: DEFAULT_INVITE_PAGE_SIZE,
                                    offset = params["offset"]?.toIntOrNull() ?: 0,
                                    statusRaw = params["status"],
                                ),
                        ) { page -> call.respond(status = HttpStatusCode.OK, message = page) }
                    }
                }
                delete(path = "/{id}") {
                    respondMappingErrors {
                        respondEither(result = service.revoke(token = verifiedToken(), id = uuidParam(name = "id"))) {
                            call.respond(status = HttpStatusCode.NoContent, message = "")
                        }
                    }
                }
            }
        }
    }
}
