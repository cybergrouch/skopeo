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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.circuit.CreateCircuitRequest
import org.skopeo.dto.circuit.UpdateCircuitRequest
import org.skopeo.dto.circuit.toResponse
import org.skopeo.service.circuit.CircuitService

/**
 * Circuits (#525): admin-defined groupings of tournaments (e.g. NORTH, SOUTH). Create / rename /
 * delete are ADMINISTRATOR-only; listing is staff-visible so tournament organizers can pick a
 * circuit (all enforced in [CircuitService]).
 */
fun Application.configureCircuitRoutes(service: CircuitService = CircuitService()) {
    routing {
        route(path = "/api/v1/circuits") {
            authenticate(FIREBASE_AUTH) {
                listAndCreateCircuits(service = service)
                circuitMutations(service = service)
            }
        }
    }
}

private fun Route.listAndCreateCircuits(service: CircuitService) {
    post {
        respondMappingErrors {
            val request = call.receive<CreateCircuitRequest>()
            respondEither(result = service.create(token = verifiedToken(), name = request.name)) { circuit ->
                call.respond(status = HttpStatusCode.Created, message = circuit.toResponse())
            }
        }
    }
    get {
        respondMappingErrors {
            respondEither(result = service.list(token = verifiedToken())) { circuits ->
                call.respond(status = HttpStatusCode.OK, message = circuits.map { it.toResponse() })
            }
        }
    }
}

/** Rename and delete a circuit, keyed by id. ADMINISTRATOR-only (enforced in the service). */
private fun Route.circuitMutations(service: CircuitService) {
    patch(path = "/{id}") {
        respondMappingErrors {
            val name = call.receive<UpdateCircuitRequest>().name
            respondEither(
                result = service.rename(token = verifiedToken(), circuitId = uuidParam(name = "id"), name = name),
            ) { circuit -> call.respond(status = HttpStatusCode.OK, message = circuit.toResponse()) }
        }
    }
    delete(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.delete(token = verifiedToken(), circuitId = uuidParam(name = "id"))) {
                call.respond(status = HttpStatusCode.NoContent, message = "")
            }
        }
    }
}
