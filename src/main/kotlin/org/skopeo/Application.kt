// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import mu.KotlinLogging
import org.skopeo.config.DatabaseConfig
import org.skopeo.routes.configureAuditRoutes
import org.skopeo.routes.configureCapabilityRoutes
import org.skopeo.routes.configureClubRoutes
import org.skopeo.routes.configureContactRoutes
import org.skopeo.routes.configureDuplicateCandidateRoutes
import org.skopeo.routes.configureEventRoutes
import org.skopeo.routes.configureInviteRoutes
import org.skopeo.routes.configureMatchRoutes
import org.skopeo.routes.configureNameRoutes
import org.skopeo.routes.configureOpenGraphRoutes
import org.skopeo.routes.configurePlayerListRoutes
import org.skopeo.routes.configurePlayerRoutes
import org.skopeo.routes.configureRankingRoutes
import org.skopeo.routes.configureRatingRequestRoutes
import org.skopeo.routes.configureRatingRoutes
import org.skopeo.routes.configureReportRoutes
import org.skopeo.routes.configureStandingsRoutes
import org.skopeo.routes.configureThemeRoutes
import org.skopeo.routes.configureUserRoutes
import org.skopeo.service.capability.CapabilityService
import org.skopeo.service.user.UserService
import org.slf4j.event.Level

private val logger = KotlinLogging.logger {}

/**
 * The app version reported by /health, read from the build-generated `version.properties` (see
 * build.gradle.kts `generateVersionProperties`) so the version lives in exactly one place — and a
 * release tag's version flows through automatically. Falls back to "unknown" if the resource is absent.
 */
private val APP_VERSION: String by lazy {
    object {}.javaClass.getResourceAsStream("/version.properties")?.use { stream ->
        java.util.Properties().apply { load(stream) }.getProperty("version")
    } ?: "unknown"
}

fun main(args: Array<String>) {
    logger.info { "Starting Skopeo API..." }
    EngineMain.main(args = args)
}

fun Application.module(
    initDatabase: Boolean = true,
    firebaseAuth: FirebaseAuthSettings? = null,
) {
    if (initDatabase) {
        // Initialize database connection and run migrations
        DatabaseConfig.init(application = this)

        // Set up shutdown hook to close database connection
        monitor.subscribe(definition = io.ktor.server.application.ApplicationStopped) {
            logger.info { "Application stopping, closing database connections..." }
            DatabaseConfig.close()
        }
    }

    configureMonitoring()
    configurePlugins()
    configureCORS()
    configureSecurity(settings = firebaseAuth)
    configureOpenAPI()
    configureRouting()
    configureRankingRoutes()
    configureUserRoutes(service = UserService(adminEmails = adminEmails()))
    configurePlayerRoutes()
    configureContactRoutes()
    configureNameRoutes()
    configureCapabilityRoutes(service = CapabilityService(adminEmails = adminEmails()))
    configureRatingRoutes()
    configureRatingRequestRoutes()
    configureMatchRoutes()
    configureEventRoutes()
    configureClubRoutes()
    configureInviteRoutes()
    configureDuplicateCandidateRoutes()
    configurePlayerListRoutes()
    configureStandingsRoutes()
    configureAuditRoutes()
    configureReportRoutes()
    configureOpenGraphRoutes()
    configureThemeRoutes()
    logger.info { "Skopeo API started successfully on port 8080" }
}

fun Application.configureMonitoring() {
    // Request/Response logging
    install(plugin = CallLogging) {
        level = Level.INFO
        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
            "$method $uri - Status: $status - User-Agent: $userAgent"
        }
    }

    // Performance metrics
    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(plugin = MicrometerMetrics) {
        registry = prometheusRegistry
    }
    logger.info { "Metrics monitoring configured (Prometheus)" }

    // Expose metrics endpoint
    routing {
        get(path = "/metrics") {
            logger.debug { "Metrics endpoint accessed" }
            call.respond(message = prometheusRegistry.scrape())
        }
    }
    logger.info { "Metrics endpoint available at /metrics" }
}

fun Application.configurePlugins() {
    install(plugin = ContentNegotiation) {
        json()
    }
    logger.info { "Content negotiation configured with JSON support" }
}

/**
 * Cross-Origin Resource Sharing for the decoupled web UI.
 *
 * The UI is deployed separately (a static SPA), so browser calls to this API are
 * cross-origin and require explicit CORS allowances. Token-based auth (Authorization
 * header) means credentials/cookies are not needed, so allowCredentials stays off.
 *
 * Production web origins are supplied via config (`cors.origins`, env `WEB_ORIGINS`) as a
 * comma-separated list of `scheme://host[:port]`, so a new deploy origin needs no code change.
 */
fun Application.configureCORS() {
    // Read config on the Application receiver — `environment` isn't reachable inside the install lambda.
    val webOrigins = parseWebOrigins(raw = environment.config.propertyOrNull(path = "cors.origins")?.getString())
    install(plugin = CORS) {
        // Local development: Vite dev server default origin (always allowed).
        allowHost(host = "localhost:5173", schemes = listOf("http", "https"))
        // Production web origins from config, e.g. "https://skopeo.com,https://skopeo-prod.web.app".
        webOrigins.forEach { (host, scheme) -> allowHost(host = host, schemes = listOf(element = scheme)) }

        allowMethod(method = HttpMethod.Get)
        allowMethod(method = HttpMethod.Post)
        allowMethod(method = HttpMethod.Put)
        // PATCH backs every rename/partial-update route (club/event rename, set-club, audit comment,
        // profile edits, …). Without it the CORS plugin 403s those cross-origin requests before routing.
        allowMethod(method = HttpMethod.Patch)
        allowMethod(method = HttpMethod.Delete)
        allowMethod(method = HttpMethod.Options)

        allowHeader(header = HttpHeaders.ContentType)
        allowHeader(header = HttpHeaders.Authorization)
    }
    logger.info { "CORS configured for web UI origins" }
}

fun Application.configureOpenAPI() {
    routing {
        // Serve raw OpenAPI specification file
        get(path = "/openapi.yaml") {
            logger.debug { "OpenAPI YAML specification requested" }
            val yamlContent =
                this::class.java.classLoader.getResource("openapi/documentation.yaml")?.readText()
                    ?: throw IllegalStateException("OpenAPI specification file not found")
            call.respondText(text = yamlContent, contentType = ContentType.Text.Plain)
        }

        // Serve Swagger UI (interactive API documentation)
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
    }
    logger.info { "API documentation available at /swagger (Swagger UI) and /openapi.yaml (raw spec)" }
}

fun Application.configureRouting() {
    routing {
        get(path = "/") {
            logger.info { "Root endpoint accessed" }
            call.respondText(text = "Skopeo API")
        }

        get(path = "/health") {
            logger.debug { "Health check endpoint accessed" }
            call.respond(
                status = HttpStatusCode.OK,
                message =
                    mapOf(
                        "status" to "UP",
                        "service" to "Skopeo API",
                        "version" to APP_VERSION,
                    ),
            )
        }
    }
    logger.info { "Routing configured with endpoints: /, /health, /metrics, /api/v1/calculate-ranking" }
}

/**
 * Parse a comma-separated `WEB_ORIGINS` value into `(host[:port], scheme)` pairs for CORS
 * `allowHost`. Blank and malformed entries (missing scheme or host) are dropped.
 */
internal fun parseWebOrigins(raw: String?): List<Pair<String, String>> =
    raw?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.mapNotNull { origin ->
            val scheme = origin.substringBefore(delimiter = "://", missingDelimiterValue = "")
            val host = origin.substringAfter(delimiter = "://", missingDelimiterValue = "")
            if (scheme.isNotBlank() && host.isNotBlank()) host to scheme else null
        }
        .orEmpty()

/**
 * The ADMIN_EMAILS allowlist (config `admin.emails`), normalized to a lowercase/trimmed set.
 * Empty/unset ⇒ no auto-admins. See docs/engineering/architecture/ADMIN_BOOTSTRAP.md.
 */
private fun Application.adminEmails(): Set<String> =
    environment.config.propertyOrNull(path = "admin.emails")?.getString()
        ?.split(",")
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()
