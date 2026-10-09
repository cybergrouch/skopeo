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
import org.skopeo.common.dto.user.StaleAccountSweepRequest
import org.skopeo.domain.service.client.ApiClientService
import org.skopeo.domain.service.user.StaleAccountService

/**
 * The stale-account sweep trigger (#1122) — `ACCOUNT_SWEEPER` or ADMINISTRATOR. Shaped like the standings
 * recompute route (#389): both credentials reach the handler, a person's token takes precedence over an
 * `X-Api-Key`, and an empty or unparseable body is a **dry run**. Only an explicit `{"dryRun": false}`
 * soft-deletes.
 */
fun Application.configureStaleAccountRoutes(
    service: StaleAccountService = StaleAccountService(),
    clients: ApiClientService = ApiClientService(),
) {
    routing {
        // `optional = true` so the Cloud Scheduler job (an API key, no Firebase token) and an administrator
        // (a token) reach the same handler, as for the standings recompute.
        authenticate(FIREBASE_AUTH, optional = true) {
            post(path = "/api/v1/users/stale-sweeps") {
                respondMappingErrors {
                    val request =
                        runCatching { call.receiveNullable<StaleAccountSweepRequest>() }.getOrNull() ?: StaleAccountSweepRequest()
                    val token = optionalVerifiedToken()
                    val result =
                        if (token != null) {
                            service.sweep(token = token, dryRun = request.dryRun)
                        } else {
                            val principal = resolveClient(service = clients) ?: return@respondMappingErrors
                            service.sweep(principal = principal, dryRun = request.dryRun)
                        }
                    respondEither(result = result) { outcome ->
                        call.respond(status = HttpStatusCode.OK, message = outcome)
                    }
                }
            }
        }
    }
}
