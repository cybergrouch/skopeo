// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.client.CreateApiClientRequest
import org.skopeo.dto.client.IssueApiKeyRequest
import org.skopeo.dto.client.toResponse
import org.skopeo.model.ApiKeyEnvironment
import org.skopeo.model.Capability
import org.skopeo.model.ClientAuthResult
import org.skopeo.model.ClientPrincipal
import org.skopeo.service.client.ApiClientService

/** The header a partner presents its API key in (#225/#596). */
private const val API_KEY_HEADER = "X-Api-Key"

/**
 * Partner API clients and keys (#225/#596). Management (`/api/v1/api-clients`) is ADMINISTRATOR-only
 * (enforced in [ApiClientService]). `/api/v1/client/me` is authenticated by the API key itself — a
 * partner uses it to verify a key resolves — and is the first consumer of the client-identity resolver.
 */
fun Application.configureApiClientRoutes(service: ApiClientService = ApiClientService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/api-clients") {
                manageClients(service = service)
                manageKeys(service = service)
            }
        }
        route(path = "/api/v1/client") {
            clientSelfIdentity(service = service)
        }
    }
}

private fun Route.manageClients(service: ApiClientService) {
    post {
        respondMappingErrors {
            val request = call.receive<CreateApiClientRequest>()
            respondEither(result = service.createClient(token = verifiedToken(), name = request.name)) { client ->
                call.respond(status = HttpStatusCode.Created, message = client.toResponse())
            }
        }
    }
    get {
        respondMappingErrors {
            respondEither(result = service.listClients(token = verifiedToken())) { clients ->
                call.respond(status = HttpStatusCode.OK, message = clients.map { it.toResponse() })
            }
        }
    }
}

private fun Route.manageKeys(service: ApiClientService) {
    // Issue a key: the plaintext is returned once in the response and never again.
    post(path = "/{id}/keys") {
        respondMappingErrors {
            val body = call.receive<IssueApiKeyRequest>()
            val scopes = body.scopes.map { parseEnumParam<Capability>(value = it, field = "scope") }.toSet()
            val environment =
                body.environment?.let { parseEnumParam<ApiKeyEnvironment>(value = it, field = "environment") }
                    ?: ApiKeyEnvironment.LIVE
            respondEither(
                result =
                    service.issueKey(
                        token = verifiedToken(),
                        clientId = uuidParam(name = "id"),
                        scopes = scopes,
                        environment = environment,
                        expiresInDays = body.expiresInDays,
                    ),
            ) { issued -> call.respond(status = HttpStatusCode.Created, message = issued.toResponse()) }
        }
    }
    delete(path = "/{clientId}/keys/{keyId}") {
        respondMappingErrors {
            respondEither(
                result =
                    service.revokeKey(
                        token = verifiedToken(),
                        clientId = uuidParam(name = "clientId"),
                        keyId = uuidParam(name = "keyId"),
                    ),
            ) { call.respond(status = HttpStatusCode.NoContent, message = "") }
        }
    }
}

/** `GET /api/v1/client/me` — resolve the caller's API key and echo back its client identity + scopes. */
private fun Route.clientSelfIdentity(service: ApiClientService) {
    get(path = "/me") {
        respondMappingErrors {
            val principal = resolveClient(service = service) ?: return@respondMappingErrors
            call.respond(status = HttpStatusCode.OK, message = principal.toResponse())
        }
    }
}

/**
 * Resolve the `X-Api-Key` header to a [ClientPrincipal], or respond with the right status and return
 * null (the caller then stops). Missing/malformed/unknown → 401; revoked/expired/suspended → 403. This
 * is the call-level client-identity resolver, kept separate from the Firebase JWT auth provider (#596).
 */
private suspend fun RoutingContext.resolveClient(service: ApiClientService): ClientPrincipal? {
    val raw = call.request.header(name = API_KEY_HEADER).orEmpty()
    return when (val result = service.authenticate(rawKey = raw)) {
        is ClientAuthResult.Authenticated -> result.principal
        ClientAuthResult.Missing, ClientAuthResult.Invalid -> {
            call.respond(
                status = HttpStatusCode.Unauthorized,
                message = errorBody(error = "Unauthorized", message = "A valid $API_KEY_HEADER is required"),
            )
            null
        }
        ClientAuthResult.Forbidden -> {
            call.respond(
                status = HttpStatusCode.Forbidden,
                message = errorBody(error = "Forbidden", message = "This API key is not permitted"),
            )
            null
        }
    }
}
