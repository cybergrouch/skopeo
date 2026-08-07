// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test

class OpenAPIIntegrationTest {
    /** Circuits (#525): the admin-managed groupings-of-tournaments contract is documented. */
    @Test
    fun testOpenAPISpecIncludesCircuits() =
        testApplication {
            application {
                module(initDatabase = false)
            }
            val body = client.get(urlString = "/openapi.yaml").bodyAsText()
            body shouldContain "/api/v1/circuits"
            body shouldContain "CircuitResponse"
            body shouldContain "CreateCircuitRequest"
        }

    /** Client API (#225/#599): the admin client/key routes, the client-key reads, and their schemas. */
    @Test
    fun testOpenAPISpecIncludesClientApi() =
        testApplication {
            application {
                module(initDatabase = false)
            }
            val body = client.get(urlString = "/openapi.yaml").bodyAsText()
            // The X-Api-Key security scheme and the admin + client-key paths.
            body shouldContain "X-Api-Key"
            body shouldContain "/api/v1/api-clients"
            body shouldContain "/api/v1/api-clients/{clientId}/keys/{keyId}"
            body shouldContain "/api/v1/client/me"
            body shouldContain "/api/v1/client/players"
            body shouldContain "/api/v1/client/me/capabilities"
            // The request/response schemas the web client generates from.
            body shouldContain "CreateApiClientRequest"
            body shouldContain "IssueApiKeyRequest"
            body shouldContain "ApiClientResponse"
            body shouldContain "IssuedApiKeyResponse"
            body shouldContain "ClientIdentityResponse"
            body shouldContain "PartnerPlayerResponse"
            body shouldContain "ClientEffectiveCapabilitiesResponse"
            // Per-client rate-limit override (#603).
            body shouldContain "/api/v1/api-clients/{id}/rate-limit"
            body shouldContain "SetRateLimitRequest"
            body shouldContain "rateLimitPerMin"
        }

    /** Per-admin raw-rating preview toggle (#583): the self-service route + its request schema. */
    @Test
    fun testOpenAPISpecIncludesRatingPreviewToggle() =
        testApplication {
            application {
                module(initDatabase = false)
            }
            val body = client.get(urlString = "/openapi.yaml").bodyAsText()
            body shouldContain "/api/v1/users/me/rating-preview"
            body shouldContain "SetRatingPreviewRequest"
            body shouldContain "previewRatingsAsNonAdmin"
        }

    /** Match-history privacy flag (#622): the self-service route, its request schema, and the response field. */
    @Test
    fun testOpenAPISpecIncludesMatchHistoryVisibility() =
        testApplication {
            application {
                module(initDatabase = false)
            }
            val body = client.get(urlString = "/openapi.yaml").bodyAsText()
            body shouldContain "/api/v1/users/{id}/match-history-visibility"
            body shouldContain "MatchHistoryVisibilityRequest"
            body shouldContain "matchHistoryHidden"
        }

    /** Tournaments (#525): circuit-on-event, club sanction, and placement-match fields are documented. */
    @Test
    fun testOpenAPISpecIncludesTournamentFields() =
        testApplication {
            application {
                module(initDatabase = false)
            }
            val body = client.get(urlString = "/openapi.yaml").bodyAsText()
            body shouldContain "/api/v1/clubs/{id}/sanction"
            body shouldContain "SetSanctionRequest"
            body shouldContain "tournamentsSanctioned"
            body shouldContain "isPlacementMatch"
            body shouldContain "placementBracket"
        }

    @Test
    fun testOpenAPISpecEndpoint() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val response = client.get(urlString = "/openapi.yaml")

            response.status shouldBe HttpStatusCode.OK
            // OpenAPI YAML file should be served as plain text
            response.contentType()?.match(ContentType.Text.Plain) shouldBe true

            val body = response.bodyAsText()
            // Verify it contains OpenAPI spec content
            body shouldContain "openapi: 3.0.0"
            body shouldContain "Skopeo API"
            // The production server target (#225) so Swagger "Try it out" can hit live via skopeo.co.
            body shouldContain "https://skopeo.co"
            body shouldContain "/api/v1/calculate-ranking"
            body shouldContain "/api/v1/theme"
            // Per-user local theme (#514): the self-service path and its request/response schemas.
            body shouldContain "/api/v1/users/me/theme"
            body shouldContain "LocalThemeResponse"
            body shouldContain "SetLocalThemeRequest"
            // The standings serving-source toggle (#146): its read/write path and schemas.
            body shouldContain "/api/v1/settings/standings-source"
            body shouldContain "StandingsSourceResponse"
            body shouldContain "SetStandingsSourceRequest"
            // The paged standings serving layer (#220): the page endpoint, the jump-to-me endpoint,
            // and the response schemas are all documented.
            body shouldContain "/api/v1/standings/me"
            body shouldContain "StandingsPageResponse"
            // The effective serving source is documented on the page response (#428).
            body shouldContain "The effective serving source"
            body shouldContain "StandingsLocateResponse"
            // Every NTRP band is advertised for the dropdown (#113).
            body shouldContain "StandingsBandResponse"
            // The points-based recompute trigger (#146 phase 2).
            body shouldContain "/api/v1/standings/calculations"
            body shouldContain "StandingsCalculationResponse"
            body shouldContain "/ranking-points"
            // The paginated list-all "Points awarded" list (#472): the paged response schema is documented.
            body shouldContain "AwardedPointsPageResponse"
            body shouldContain "AwardedPointRow"
            // The manual signed point adjustment (#469): the adjustments endpoint + its request schema.
            body shouldContain "/api/v1/users/{userId}/ranking-points/adjustments"
            body shouldContain "AdjustRankingPointsRequest"
            // Placeholder ("dummy") player accounts + claim/adopt (#496): the create/list, claim-code
            // generation, and claim paths plus their request/response schemas are documented.
            body shouldContain "/api/v1/users/placeholders"
            body shouldContain "/api/v1/users/{id}/claim-code"
            body shouldContain "/api/v1/users/claim"
            body shouldContain "CreatePlaceholderRequest"
            body shouldContain "ClaimRequest"
            body shouldContain "ClaimCodeResponse"
            // Admin soft-delete + re-allow login (#518): the reactivate path, the Research include-inactive
            // search param, and the "Deleted" flag on player-reference DTOs are documented.
            body shouldContain "/api/v1/users/{id}/reactivate"
            body shouldContain "includeInactive"
            body shouldContain "isDeleted"
            // Profile band+sex rank + points and the owner-or-admin points audit (#448).
            body shouldContain "/api/v1/players/{code}/standing"
            body shouldContain "PlayerStandingResponse"
            body shouldContain "/api/v1/players/{code}/points"
            body shouldContain "ActivePointsAwardResponse"
            // Public event-participation history (#704): parity with /events/mine, resolved by code.
            body shouldContain "/api/v1/players/{code}/events"
            // Event types + finalize state (#403 Phase A): the finalize path is documented.
            body shouldContain "/api/v1/events/{id}/finalize"
            // Un-finalize (#477): the reverse-finalize path is documented.
            body shouldContain "/api/v1/events/{id}/unfinalize"
            // Event-sourced seeding (#714): the generate/read path from an event's participants.
            body shouldContain "/api/v1/events/{id}/seeding"
            // Single "Award Ranking Points" flag (#559): the per-event points budget + designation
            // subsystem was removed; awarding is controlled solely by this event-level flag.
            body shouldContain "awardRankingPoints"
        }

    /** The removed points budget + designation subsystem (#559/#561) leaves no trace in the spec. */
    @Test
    fun testOpenAPISpecDropsRemovedPointsBudgetSubsystem() =
        testApplication {
            application {
                module(initDatabase = false)
            }
            val body = client.get(urlString = "/openapi.yaml").bodyAsText()
            body shouldNotContain "/api/v1/events/{id}/points-config"
            body shouldNotContain "SetPointsConfigRequest"
            body shouldNotContain "/api/v1/matches/{id}/designation"
            body shouldNotContain "SetDesignationRequest"
            body shouldNotContain "designatedPoints"
            body shouldNotContain "/api/v1/points/budgets"
            body shouldNotContain "ClubBudgetResponse"
            body shouldNotContain "/api/v1/clubs/{clubId}/points-summary"
        }

    /** Admin-configurable global points schedules (#552/#553): the open-play + tournament config contract. */
    @Test
    fun testOpenAPISpecIncludesPointsConfig() =
        testApplication {
            application {
                module(initDatabase = false)
            }
            val body = client.get(urlString = "/openapi.yaml").bodyAsText()
            body shouldContain "/api/v1/settings/points/open-play"
            body shouldContain "/api/v1/settings/points/tournament"
            body shouldContain "OpenPlayConfigResponse"
            body shouldContain "TournamentConfigResponse"
            body shouldContain "OpenPlayPointsConfig"
            body shouldContain "TournamentPointsConfig"
        }

    @Test
    fun testOpenAPISpecParsesAsValidYaml() {
        // Substring checks alone let a malformed documentation.yaml through the backend gate and
        // only break later in the web orval step (this happened in #400: a description with an
        // unquoted ": " mid-string made the YAML invalid). Parse it here so the backend gate fails
        // on malformed specs. Read the classpath resource directly — no server needed.
        val specStream =
            javaClass.classLoader.getResourceAsStream("openapi/documentation.yaml")
        val specText =
            requireNotNull(value = specStream).bufferedReader().use { reader -> reader.readText() }

        var parsed: Map<String, Any>? = null
        shouldNotThrowAny {
            @Suppress("UNCHECKED_CAST")
            parsed = Yaml().load<Any>(specText) as Map<String, Any>
        }

        // A structurally-broken spec (valid YAML but missing the OpenAPI skeleton) should also fail.
        val document = parsed.shouldNotBeNull()
        document.shouldContainKey(key = "openapi")
        document.shouldContainKey(key = "paths")

        @Suppress("UNCHECKED_CAST")
        val components = document["components"] as? Map<String, Any>
        components.shouldNotBeNull()
        components.shouldContainKey(key = "schemas")
    }

    @Test
    fun testServedOpenAPISpecParsesAsValidYaml() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val body = client.get(urlString = "/openapi.yaml").bodyAsText()

            // The body actually served over HTTP must also be parseable, not just the resource file.
            shouldNotThrowAny {
                Yaml().load<Any>(body)
            }
        }

    @Test
    fun testSwaggerUIEndpoint() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val response = client.get(urlString = "/swagger")

            response.status shouldBe HttpStatusCode.OK
            response.contentType()?.match(ContentType.Text.Html) shouldBe true

            val body = response.bodyAsText()
            // Verify it's actually Swagger UI
            (body.contains(other = "swagger-ui") || body.contains(other = "Swagger UI")) shouldBe true
        }
}
