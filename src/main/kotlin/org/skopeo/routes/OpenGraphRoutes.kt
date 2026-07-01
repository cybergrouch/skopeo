// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.skopeo.service.event.EventService
import org.skopeo.service.match.MatchService
import org.skopeo.service.user.PlayerService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

private const val SHELL_TIMEOUT_SECONDS = 5L
private const val HTTP_OK = 200

/** Minimal shell used only if the deployed index.html can't be fetched — crawlers still get the tags. */
private const val FALLBACK_SHELL =
    "<!doctype html><html lang=\"en\"><head></head><body><div id=\"root\"></div></body></html>"

private suspend fun RoutingContext.respondOg(
    provider: WebShellProvider,
    meta: OgMeta,
) {
    val shell = provider.shell() ?: FALLBACK_SHELL
    call.respondText(text = injectOg(shell = shell, meta = meta), contentType = ContentType.Text.Html)
}

/**
 * Register the crawler-facing pages (#238). These live at the *frontend* paths (`/matches/{code}` etc.)
 * and are reached only because Firebase Hosting rewrites those paths to this service; everything else
 * stays on the SPA. Entities are resolved anonymously (bands only) — no auth needed for a public card.
 */
fun Application.configureOpenGraphRoutes(
    matches: MatchService = MatchService(),
    players: PlayerService = PlayerService(),
    events: EventService = EventService(),
    shellProvider: WebShellProvider = httpShellProvider(indexUrl = webIndexUrl()),
    origin: String = webOrigin(),
) {
    val image = "$origin/og-cover.png"
    routing {
        get(path = "/matches/{code}") {
            val code = call.parameters["code"].orEmpty()
            val url = "$origin/matches/$code"
            val meta =
                matches
                    .publicByCode(token = null, code = code)
                    .fold(ifLeft = { defaultMeta(url = url, image = image) }, ifRight = { matchMeta(match = it, url = url, image = image) })
            respondOg(provider = shellProvider, meta = meta)
        }
        get(path = "/players/{code}") {
            val code = call.parameters["code"].orEmpty()
            val url = "$origin/players/$code"
            val meta =
                players
                    .publicProfile(code = code)
                    .fold(
                        ifLeft = { defaultMeta(url = url, image = image) },
                        ifRight = { playerMeta(player = it, url = url, image = image) },
                    )
            respondOg(provider = shellProvider, meta = meta)
        }
        get(path = "/events/{code}") {
            val code = call.parameters["code"].orEmpty()
            val url = "$origin/events/$code"
            val meta =
                events
                    .publicByCode(token = null, code = code)
                    .fold(ifLeft = { defaultMeta(url = url, image = image) }, ifRight = { eventMeta(event = it, url = url, image = image) })
            respondOg(provider = shellProvider, meta = meta)
        }
    }
}

/** Fetch the deployed index.html once from [indexUrl] and cache it (the shell changes only on redeploy). */
internal fun httpShellProvider(indexUrl: String): WebShellProvider {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(SHELL_TIMEOUT_SECONDS)).build()
    val cache = AtomicReference<String?>(null)
    return WebShellProvider {
        cache.get() ?: runCatching {
            val request =
                HttpRequest.newBuilder(URI.create(indexUrl)).timeout(Duration.ofSeconds(SHELL_TIMEOUT_SECONDS)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == HTTP_OK) response.body().also { cache.set(it) } else null
        }.getOrNull()
    }
}

/** The site origin for canonical URLs + the og:image, from config (`web.origin`) or the prod default. */
internal fun Application.webOrigin(): String =
    environment.config.propertyOrNull(path = "web.origin")?.getString()?.trimEnd('/') ?: "https://skopeo.co"

internal fun Application.webIndexUrl(): String = "${webOrigin()}/index.html"
