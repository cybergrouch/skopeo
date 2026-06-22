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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.contact.ContactCreateRequest
import org.skopeo.dto.contact.ContactStateRequest
import org.skopeo.dto.contact.VerificationRequest
import org.skopeo.dto.contact.toResponse
import org.skopeo.service.contact.ContactService

/**
 * Contact-information API, nested under the owning user. Editing the address is
 * self-or-ADMINISTRATOR; setting the verification state is ADMINISTRATOR-only
 * (enforced in [ContactService]). Routes stay thin.
 */
fun Application.configureContactRoutes(service: ContactService = ContactService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route("/api/v1/users/{userId}/contacts") {
                listAndCreate(service)
                byId(service)
                state(service)
                verification(service)
            }
        }
    }
}

private fun Route.listAndCreate(service: ContactService) {
    get {
        respondMappingErrors {
            val list = service.list(token = verifiedToken(), userId = uuidParam("userId"))
            call.respond(status = HttpStatusCode.OK, message = list.map { it.toResponse() })
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<ContactCreateRequest>()
            val contact = service.create(token = verifiedToken(), userId = uuidParam("userId"), request = request)
            call.respond(status = HttpStatusCode.Created, message = contact.toResponse())
        }
    }
}

private fun Route.byId(service: ContactService) {
    get("/{id}") {
        respondMappingErrors {
            val contact = service.get(token = verifiedToken(), userId = uuidParam("userId"), contactId = uuidParam("id"))
            call.respond(status = HttpStatusCode.OK, message = contact.toResponse())
        }
    }
}

private fun Route.state(service: ContactService) {
    put("/{id}/state") {
        respondMappingErrors {
            val request = call.receive<ContactStateRequest>()
            val contact =
                service.setActive(
                    token = verifiedToken(),
                    userId = uuidParam("userId"),
                    contactId = uuidParam("id"),
                    active = request.isActive,
                )
            call.respond(status = HttpStatusCode.OK, message = contact.toResponse())
        }
    }
}

private fun Route.verification(service: ContactService) {
    put("/{id}/verification") {
        respondMappingErrors {
            val request = call.receive<VerificationRequest>()
            val contact =
                service.setVerification(
                    token = verifiedToken(),
                    userId = uuidParam("userId"),
                    contactId = uuidParam("id"),
                    request = request,
                )
            call.respond(status = HttpStatusCode.OK, message = contact.toResponse())
        }
    }
}
