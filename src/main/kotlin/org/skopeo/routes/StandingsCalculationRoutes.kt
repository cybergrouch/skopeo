// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.common.dto.standings.StandingsCalculationRequest
import org.skopeo.domain.service.client.ApiClientService
import org.skopeo.domain.service.standings.StandingsCalculationService

/**
 * Points-based standings recompute trigger (POINTS_MANAGER or ADMINISTRATOR, #146 phase 2 —
 * widened in #389 so a scheduled run can hold a points-scoped key rather than blanket admin).
 * Mirrors the rating
 * calculation route: `dryRun` defaults to true (an empty/unparseable body is a dry run) and previews
 * with no writes; an explicit `{"dryRun": false}` publishes a POINTS snapshot. The route stays thin —
 * the recompute + persistence live in [StandingsCalculationService].
 */
fun Application.configureStandingsCalculationRoutes(
    service: StandingsCalculationService = StandingsCalculationService(),
    clients: ApiClientService = ApiClientService(),
) {
    routing {
        // `optional = true` so BOTH credentials reach the handler (#389). A Firebase token is used when
        // present; otherwise the request must carry an `X-Api-Key`. It cannot be a required Firebase
        // block, because Cloud Scheduler has no way to mint a Firebase ID token — and it cannot be
        // key-only, because this is the same endpoint an administrator drives from the dashboard.
        authenticate(FIREBASE_AUTH, optional = true) {
            post(path = "/api/v1/standings/calculations") {
                respondMappingErrors {
                    // No/unparseable body → a dry run (the safe default; only an explicit false commits).
                    val request =
                        runCatching { call.receiveNullable<StandingsCalculationRequest>() }.getOrNull() ?: StandingsCalculationRequest()
                    // A person takes precedence: if someone presents a token, the run is attributed to
                    // them. Falling back to the key only when there is no user keeps the audit actor
                    // unambiguous rather than depending on which credential the client happened to send.
                    val token = optionalVerifiedToken()
                    val result =
                        if (token != null) {
                            service.calculate(token = token, dryRun = request.dryRun)
                        } else {
                            val principal = resolveClient(service = clients) ?: return@respondMappingErrors
                            service.calculate(principal = principal, dryRun = request.dryRun)
                        }
                    respondEither(result = result) { outcome ->
                        call.respond(status = HttpStatusCode.OK, message = outcome)
                    }
                }
            }
        }
    }
}
