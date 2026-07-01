// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import org.skopeo.dto.event.EventPublicResponse
import org.skopeo.dto.match.MatchPublicPlayer
import org.skopeo.dto.match.MatchPublicResponse
import org.skopeo.dto.user.PublicPlayerResponse

/*
 * Pure Open Graph helpers for per-page rich link previews (#238). The public pages are a client-rendered
 * SPA, so social crawlers (which don't run JS) see no per-page Open Graph tags. The responder
 * (OpenGraphRoutes) resolves the entity and returns the deployed index.html with these per-page tags
 * injected into <head>; the SPA still boots for humans while crawlers read the tags. This file holds the
 * pure, unit-tested logic; the network/config plumbing lives in OpenGraphRoutes.
 */

private val TITLE = Regex(pattern = "<title>.*?</title>", option = RegexOption.DOT_MATCHES_ALL)

/** Per-page Open Graph / Twitter Card metadata. */
internal data class OgMeta(
    val title: String,
    val description: String,
    val url: String,
    val image: String,
)

/** Supplies the SPA index.html to inject into; null when it can't be fetched (→ a minimal fallback). */
fun interface WebShellProvider {
    fun shell(): String?
}

private fun escapeHtml(value: String): String =
    value
        .replace(oldValue = "&", newValue = "&amp;")
        .replace(oldValue = "<", newValue = "&lt;")
        .replace(oldValue = ">", newValue = "&gt;")
        .replace(oldValue = "\"", newValue = "&quot;")

/** Inject [meta] into [shell]: replace the SPA's `<title>` and add OG/Twitter `<meta>` before `</head>`. */
internal fun injectOg(
    shell: String,
    meta: OgMeta,
): String {
    val title = escapeHtml(value = meta.title)
    val description = escapeHtml(value = meta.description)
    val url = escapeHtml(value = meta.url)
    val image = escapeHtml(value = meta.image)
    val head =
        buildString {
            append("<title>$title</title>")
            append("<meta property=\"og:type\" content=\"website\" />")
            append("<meta property=\"og:site_name\" content=\"Skopeo\" />")
            append("<meta property=\"og:title\" content=\"$title\" />")
            append("<meta property=\"og:description\" content=\"$description\" />")
            append("<meta property=\"og:url\" content=\"$url\" />")
            append("<meta property=\"og:image\" content=\"$image\" />")
            append("<meta name=\"twitter:card\" content=\"summary_large_image\" />")
            append("<meta name=\"twitter:title\" content=\"$title\" />")
            append("<meta name=\"twitter:description\" content=\"$description\" />")
            append("<meta name=\"twitter:image\" content=\"$image\" />")
        }
    val withoutTitle = TITLE.replace(input = shell, replacement = "")
    return if (withoutTitle.contains(other = "</head>")) {
        withoutTitle.replaceFirst(oldValue = "</head>", newValue = "$head</head>")
    } else {
        "$head$withoutTitle"
    }
}

/** A side's headline name for the match card: the first player's display name, else code, else generic. */
internal fun sideName(side: List<MatchPublicPlayer>): String {
    val player = side.firstOrNull()
    return player?.displayName ?: player?.publicCode ?: "A player"
}

internal fun matchMeta(
    match: MatchPublicResponse,
    url: String,
    image: String,
): OgMeta =
    OgMeta(
        title = "${sideName(side = match.team1)} vs ${sideName(side = match.team2)} on Skopeo",
        description = "Match on ${match.matchDate}${match.venue?.let { " · $it" }.orEmpty()} — view the result on Skopeo.",
        url = url,
        image = image,
    )

internal fun playerMeta(
    player: PublicPlayerResponse,
    url: String,
    image: String,
): OgMeta =
    OgMeta(
        title = "${player.displayName ?: player.publicCode} on Skopeo",
        description = "View this player's Skopeo profile and match history.",
        url = url,
        image = image,
    )

internal fun eventMeta(
    event: EventPublicResponse,
    url: String,
    image: String,
): OgMeta =
    OgMeta(
        title = "${event.name} on Skopeo",
        description = "${event.startDate} – ${event.endDate} · participants, matches, and results on Skopeo.",
        url = url,
        image = image,
    )

/** The default card (site-wide) used when the code resolves to no entity, so the page still previews. */
internal fun defaultMeta(
    url: String,
    image: String,
): OgMeta =
    OgMeta(
        title = "Skopeo — performance-based tennis ratings",
        description = "Skopeo calculates performance-based NTRP tennis ratings from your match results.",
        url = url,
        image = image,
    )
