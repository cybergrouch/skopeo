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
import org.skopeo.dto.duplicate.ConfirmCandidateRequest
import org.skopeo.dto.duplicate.FlagCandidateRequest
import org.skopeo.dto.duplicate.toResponse
import org.skopeo.model.DuplicateCandidateStatus
import org.skopeo.service.user.DuplicateCandidateService
import java.util.UUID

private const val DEFAULT_CANDIDATE_PAGE_SIZE = 20

/** Parse a `status` filter to a [DuplicateCandidateStatus]; an unknown value is a 400. */
private fun parseCandidateStatus(raw: String): DuplicateCandidateStatus =
    requireNotNull(value = DuplicateCandidateStatus.entries.find { it.name == raw.uppercase() }) {
        "Unknown status '$raw'; expected one of ${DuplicateCandidateStatus.entries.joinToString { it.name }}"
    }

private fun parseUserId(raw: String): UUID =
    try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid user id '$raw'", e)
    }

/**
 * The duplicate-candidate queue (issue #126) — ADMINISTRATOR-only (enforced in [DuplicateCandidateService]).
 * Surfaces suspected duplicate accounts for review; confirming resolves via the #124 marking tool.
 */
fun Application.configureDuplicateCandidateRoutes(service: DuplicateCandidateService = DuplicateCandidateService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/duplicate-candidates") {
                get {
                    respondMappingErrors {
                        val params = call.request.queryParameters
                        respondEither(
                            result =
                                service.list(
                                    token = verifiedToken(),
                                    limit = params["limit"]?.toIntOrNull() ?: DEFAULT_CANDIDATE_PAGE_SIZE,
                                    offset = params["offset"]?.toIntOrNull() ?: 0,
                                    status = params["status"]?.let { parseCandidateStatus(raw = it) },
                                ),
                        ) { page -> call.respond(status = HttpStatusCode.OK, message = page.toResponse()) }
                    }
                }
                post {
                    respondMappingErrors {
                        val request = call.receive<FlagCandidateRequest>()
                        respondEither(
                            result =
                                service.flagManual(
                                    token = verifiedToken(),
                                    userAId = parseUserId(raw = request.userAId),
                                    userBId = parseUserId(raw = request.userBId),
                                    reason = request.reason,
                                ),
                        ) { candidate -> call.respond(status = HttpStatusCode.Created, message = candidate.toResponse()) }
                    }
                }
                post(path = "/{id}/confirm") {
                    respondMappingErrors {
                        val request = call.receive<ConfirmCandidateRequest>()
                        respondEither(
                            result =
                                service.confirm(
                                    token = verifiedToken(),
                                    id = uuidParam(name = "id"),
                                    canonicalId = parseUserId(raw = request.canonicalId),
                                ),
                        ) { call.respond(status = HttpStatusCode.NoContent, message = "") }
                    }
                }
                delete(path = "/{id}") {
                    respondMappingErrors {
                        respondEither(result = service.dismiss(token = verifiedToken(), id = uuidParam(name = "id"))) {
                            call.respond(status = HttpStatusCode.NoContent, message = "")
                        }
                    }
                }
            }
        }
    }
}
