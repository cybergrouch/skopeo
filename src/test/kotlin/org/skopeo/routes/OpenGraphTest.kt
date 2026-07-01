// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.skopeo.dto.event.EventPublicResponse
import org.skopeo.dto.match.MatchPublicPlayer
import org.skopeo.dto.match.MatchPublicResponse
import org.skopeo.dto.match.MatchPublicSet
import org.skopeo.dto.user.PublicPlayerResponse

/** Unit tests for the pure Open Graph helpers (#238): tag injection, escaping, and per-entity metadata. */
class OpenGraphTest {
    private val meta =
        OgMeta(
            title = "Ana vs Bo on Skopeo",
            description = "A great match.",
            url = "https://skopeo.co/matches/abc",
            image = "https://skopeo.co/og-cover.png",
        )

    private val shell =
        "<!doctype html><html lang=\"en\"><head><title>Skopeo</title></head>" +
            "<body><div id=\"root\"></div></body></html>"

    @Test
    fun `injectOg replaces the title and inserts OG and Twitter tags before head close`() {
        val result = injectOg(shell = shell, meta = meta)

        result shouldContain "<title>Ana vs Bo on Skopeo</title>"
        result shouldNotContain "<title>Skopeo</title>"
        result shouldContain "<meta property=\"og:title\" content=\"Ana vs Bo on Skopeo\" />"
        result shouldContain "<meta property=\"og:description\" content=\"A great match.\" />"
        result shouldContain "<meta property=\"og:url\" content=\"https://skopeo.co/matches/abc\" />"
        result shouldContain "<meta property=\"og:image\" content=\"https://skopeo.co/og-cover.png\" />"
        result shouldContain "<meta name=\"twitter:card\" content=\"summary_large_image\" />"
        // The injected tags land inside <head>, and the SPA body is preserved so the app still boots.
        result shouldContain "og:title"
        result.substringBefore(delimiter = "</head>") shouldContain "og:title"
        result shouldContain "<div id=\"root\"></div>"
    }

    @Test
    fun `injectOg escapes HTML-significant characters in the metadata`() {
        val nasty =
            OgMeta(
                title = "A & B <script> \"x\"",
                description = "1 < 2 & 3",
                url = "https://skopeo.co/x",
                image = "https://skopeo.co/og-cover.png",
            )

        val result = injectOg(shell = shell, meta = nasty)

        result shouldContain "A &amp; B &lt;script&gt; &quot;x&quot;"
        result shouldNotContain "<script>"
        result shouldContain "1 &lt; 2 &amp; 3"
    }

    @Test
    fun `injectOg falls back to prepending tags when the shell has no head close`() {
        val result = injectOg(shell = "<html><body>no head</body></html>", meta = meta)

        result shouldContain "<meta property=\"og:title\""
        result shouldContain "no head"
    }

    @Test
    fun `sideName prefers the display name, then the code, then a generic label`() {
        sideName(side = listOf(element = MatchPublicPlayer(displayName = "Ana", publicCode = "A1"))) shouldBe "Ana"
        sideName(side = listOf(element = MatchPublicPlayer(displayName = null, publicCode = "A1"))) shouldBe "A1"
        sideName(side = emptyList()) shouldBe "A player"
    }

    @Test
    fun `matchMeta builds a versus title and includes the venue when present`() {
        val result =
            matchMeta(
                match = match(venue = "Center Court"),
                url = "https://skopeo.co/matches/abc",
                image = "img",
            )

        result.title shouldBe "Ana vs Bo on Skopeo"
        result.description shouldContain "2026-06-30"
        result.description shouldContain "Center Court"
    }

    @Test
    fun `matchMeta omits the venue separator when there is no venue`() {
        val result = matchMeta(match = match(venue = null), url = "u", image = "img")

        result.description shouldContain "2026-06-30"
        result.description shouldNotContain " · "
    }

    @Test
    fun `playerMeta uses the display name and falls back to the public code`() {
        playerMeta(player = player(name = "Ana"), url = "u", image = "img").title shouldBe "Ana on Skopeo"
        playerMeta(player = player(name = null), url = "u", image = "img").title shouldBe "P1 on Skopeo"
    }

    @Test
    fun `eventMeta names the event and shows its date range`() {
        val result = eventMeta(event = event(), url = "u", image = "img")

        result.title shouldBe "Club Open on Skopeo"
        result.description shouldContain "2026-07-01"
        result.description shouldContain "2026-07-03"
    }

    @Test
    fun `defaultMeta produces the site-wide card`() {
        val result = defaultMeta(url = "https://skopeo.co/matches/x", image = "img")

        result.title shouldContain "Skopeo"
        result.url shouldBe "https://skopeo.co/matches/x"
    }

    private fun match(venue: String?) =
        MatchPublicResponse(
            publicCode = "abc",
            matchFormat = "BEST_OF_THREE",
            matchType = "SINGLES",
            matchDate = "2026-06-30",
            status = "COMPLETED",
            team1 = listOf(element = MatchPublicPlayer(displayName = "Ana", publicCode = "A1")),
            team2 = listOf(element = MatchPublicPlayer(displayName = "Bo", publicCode = "B1")),
            winner = "TEAM1",
            sets = listOf(element = MatchPublicSet(setNumber = 1, team1Games = 6, team2Games = 4)),
            venue = venue,
        )

    private fun player(name: String?) = PublicPlayerResponse(publicCode = "P1", displayName = name, photoUrl = null, rating = null)

    private fun event() =
        EventPublicResponse(
            publicCode = "E1",
            name = "Club Open",
            startDate = "2026-07-01",
            endDate = "2026-07-03",
            participants = emptyList(),
            matches = emptyList(),
        )
}
