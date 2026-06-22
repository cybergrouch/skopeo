// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.capability.CapabilityGrantRequest
import org.skopeo.dto.capability.toResponse
import org.skopeo.service.capability.CapabilityService

/**
 * Capability (role) management, nested under the user. The entire surface is
 * ADMINISTRATOR-only (enforced in [CapabilityService]); a user reads their own roles via
 * GET /users/me. Capabilities are addressed by name (a fixed enum), not a UUID.
 */
fun Application.configureCapabilityRoutes(service: CapabilityService = CapabilityService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route("/api/v1/users/{userId}/capabilities") {
                listAndGrant(service)
                revoke(service)
            }
        }
    }
}

private fun Route.listAndGrant(service: CapabilityService) {
    get {
        respondMappingErrors {
            val grants = service.list(token = verifiedToken(), userId = uuidParam("userId"))
            call.respond(status = HttpStatusCode.OK, message = grants.map { it.toResponse() })
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<CapabilityGrantRequest>()
            val result = service.grant(token = verifiedToken(), userId = uuidParam("userId"), capabilityName = request.capability)
            val status = if (result.created) HttpStatusCode.Created else HttpStatusCode.OK
            call.respond(status = status, message = result.grant.toResponse())
        }
    }
}

private fun Route.revoke(service: CapabilityService) {
    delete("/{capability}") {
        respondMappingErrors {
            val capability = call.parameters["capability"] ?: throw BadRequestException("Missing capability")
            service.revoke(token = verifiedToken(), userId = uuidParam("userId"), capabilityName = capability)
            call.respond(status = HttpStatusCode.NoContent, message = "")
        }
    }
}
