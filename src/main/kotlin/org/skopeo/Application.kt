// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimiter
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.skopeo.common.logging.CLOUD_TRACE_HEADER
import org.skopeo.common.logging.CLOUD_TRACE_MDC_KEY
import org.skopeo.common.logging.REQUEST_ID_HEADER
import org.skopeo.common.logging.REQUEST_ID_MDC_KEY
import org.skopeo.common.logging.RequestLog
import org.skopeo.common.logging.cloudTraceField
import org.skopeo.config.DatabaseConfig
import org.skopeo.domain.service.capability.CapabilityService
import org.skopeo.domain.service.client.ApiClientService
import org.skopeo.domain.service.user.UserService
import org.skopeo.routes.configureApiClientRoutes
import org.skopeo.routes.configureAuditRoutes
import org.skopeo.routes.configureCapabilityRoutes
import org.skopeo.routes.configureCircuitRoutes
import org.skopeo.routes.configureClubRoutes
import org.skopeo.routes.configureContactRoutes
import org.skopeo.routes.configureDuplicateCandidateRoutes
import org.skopeo.routes.configureEventRoutes
import org.skopeo.routes.configureEventTeamRoutes
import org.skopeo.routes.configureFeatureFlagRoutes
import org.skopeo.routes.configureInviteRoutes
import org.skopeo.routes.configureMatchRoutes
import org.skopeo.routes.configureNameRoutes
import org.skopeo.routes.configureOpenGraphRoutes
import org.skopeo.routes.configurePlaceholderRoutes
import org.skopeo.routes.configurePlayerListRoutes
import org.skopeo.routes.configurePlayerRoutes
import org.skopeo.routes.configurePointsConfigRoutes
import org.skopeo.routes.configureRankingPointRoutes
import org.skopeo.routes.configureRankingRoutes
import org.skopeo.routes.configureRatingRequestRoutes
import org.skopeo.routes.configureRatingRoutes
import org.skopeo.routes.configureReportRoutes
import org.skopeo.routes.configureStandingsCalculationRoutes
import org.skopeo.routes.configureStandingsRoutes
import org.skopeo.routes.configureStandingsSourceRoutes
import org.skopeo.routes.configureThemeRoutes
import org.skopeo.routes.configureUserRoutes
import org.skopeo.routes.errorBody
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

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

/** Default partner (per-client) rate limit — requests per minute per client id (#598). */
const val DEFAULT_PARTNER_RATE_LIMIT: Int = 120

/** The named token-bucket limiter applied to the partner client routes, keyed by client id (#598). */
val PARTNER_RATE_LIMIT_NAME: RateLimitName = RateLimitName(name = "partner")

fun Application.module(
    initDatabase: Boolean = true,
    firebaseAuth: FirebaseAuthSettings? = null,
    partnerRateLimit: Int = DEFAULT_PARTNER_RATE_LIMIT,
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
    configureRateLimit(partnerRateLimit = partnerRateLimit)
    configureOpenAPI()
    configureRouting()
    configureRankingRoutes()
    configureUserRoutes(service = UserService(adminEmails = adminEmails()))
    configurePlaceholderRoutes()
    configurePlayerRoutes()
    configureContactRoutes()
    configureNameRoutes()
    configureCapabilityRoutes(service = CapabilityService(adminEmails = adminEmails()))
    configureRatingRoutes()
    configureRatingRequestRoutes()
    configureMatchRoutes()
    configureEventRoutes()
    configureEventTeamRoutes()
    configureClubRoutes()
    configureApiClientRoutes()
    configureCircuitRoutes()
    configurePointsConfigRoutes()
    configureInviteRoutes()
    configureDuplicateCandidateRoutes()
    configurePlayerListRoutes()
    configureStandingsRoutes()
    configureStandingsCalculationRoutes()
    configureRankingPointRoutes()
    configureAuditRoutes()
    configureReportRoutes()
    configureOpenGraphRoutes()
    configureThemeRoutes()
    configureStandingsSourceRoutes()
    configureFeatureFlagRoutes()
    logger.info { "Skopeo API started successfully on port 8080" }
}

/**
 * Per-client rate limiting for partner traffic (#225/#598). A single token-bucket tier keyed by the
 * client id behind the `X-Api-Key` (unauthenticated callers fall back to a per-remote-host bucket, so a
 * flood of bad keys can't exhaust a real client's quota). Applied only to the client routes via
 * `rateLimit(PARTNER_RATE_LIMIT_NAME)`; nothing else is throttled.
 */
fun Application.configureRateLimit(
    partnerRateLimit: Int,
    service: ApiClientService = ApiClientService(),
) {
    install(plugin = RateLimit) {
        register(name = PARTNER_RATE_LIMIT_NAME) {
            requestKey { call ->
                val rawKey = call.request.headers["X-Api-Key"].orEmpty()
                service.resolveClientId(rawKey = rawKey)?.toString() ?: "anon:${call.request.origin.remoteHost}"
            }
            // Per-client limit (#603): a client's override when present, else the default tier. The
            // provider is invoked once per distinct key, so the override is read off the hot path.
            rateLimiter { _, key ->
                val limit = service.rateLimitForKey(key = key.toString(), default = partnerRateLimit)
                RateLimiter.default(limit = limit, refillPeriod = 60.seconds)
            }
        }
    }
    logger.info { "Partner rate limiting configured (default $partnerRateLimit/min per client)" }
}

/**
 * Request logging (#751). Logs are structured JSON — see `logback.xml` for the field contract.
 *
 * There is deliberately no metrics registry here any more. `/metrics` and the Micrometer/Prometheus
 * registry were removed: nothing scraped them, and a scrape would have been misleading anyway — the
 * service runs `--max-instances=2`, so a single pull sees one instance's counters, not the service's.
 * The endpoint was also anonymously reachable, handing route names and JVM internals to anyone who
 * asked. Per-endpoint volume, latency and error rate now come from Cloud Logging log-based metrics
 * over the fields on the access line, which aggregate across instances by construction.
 */
fun Application.configureMonitoring() {
    // Read config on the Application receiver — `environment` isn't reachable inside the install lambda.
    val gcpProjectId = environment.config.propertyOrNull(path = "gcp.projectId")?.getString()

    // Accept a caller-supplied request id, generate one when absent, and echo it on the response (#805).
    // Echoing is what makes a user's screenshot enough to find the log line: the id is visible to them.
    install(plugin = CallId) {
        header(headerName = REQUEST_ID_HEADER)
        generate { UUID.randomUUID().toString() }
        // Without a verifier Ktor accepts any inbound value, including one crafted to collide with
        // another request's id or to inject noise into a log field.
        verify { candidate -> candidate.isNotBlank() && candidate.length <= MAX_REQUEST_ID_LENGTH }
        replyToHeader(headerName = REQUEST_ID_HEADER)
    }

    // CallLogging earns its place purely as the MDC vehicle: it is what propagates these entries into
    // the coroutine context so *application* logs inside a handler carry them. Its own access line is
    // emitted at DEBUG and therefore suppressed by the INFO threshold on `io.ktor` in logback.xml —
    // RequestLog below emits the real one, because the fields that matter (route, status, duration) do
    // not exist yet at the point CallLogging resolves its MDC. See RequestLog's KDoc.
    install(plugin = CallLogging) {
        level = Level.DEBUG
        callIdMdc(name = REQUEST_ID_MDC_KEY)
        // Cloud Logging joins a line to its request through this field, so it belongs in the MDC — on
        // every line the request emits, not just the access line. It resolves at call start because it
        // comes straight off an inbound header. A null return omits the field entirely, which is what
        // happens locally (no header) and in tests (no project).
        mdc(name = CLOUD_TRACE_MDC_KEY) { call ->
            cloudTraceField(header = call.request.headers[CLOUD_TRACE_HEADER], projectId = gcpProjectId)
        }
    }

    install(plugin = RequestLog)

    // The backstop for anything that escapes a route's own handling (#805). Most routes wrap their body
    // in `respondMappingErrors`, whose `catch (e: Exception)` both logs at ERROR and swallows — so this
    // will rarely fire. What it does cover is the remainder: OpenGraphRoutes has no handling at all,
    // plus failures in plugins, authentication, and response serialization. Before this, those were a
    // bare Ktor 500 that reached no logger.
    install(plugin = StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(throwable = cause) { "Unhandled exception escaped the route" }
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message =
                    errorBody(
                        error = "Internal server error",
                        message = "An unexpected error occurred",
                        requestId = call.callId,
                    ),
            )
        }
    }

    logger.info { "Request logging configured (structured JSON, request id, unhandled-exception backstop)" }
}

/**
 * Cap on an inbound `X-Request-Id`. A UUID is 36 characters; the slack allows a caller's own correlation
 * id while keeping an unbounded header out of every log line this request emits.
 */
private const val MAX_REQUEST_ID_LENGTH = 128

fun Application.configurePlugins() {
    install(plugin = ContentNegotiation) {
        json()
    }
    logger.info { "Content negotiation configured with JSON support" }
}

/** The web bundle's build id, sent by the SPA on every request (#752) — see [configureCORS]. */
const val CLIENT_VERSION_HEADER = "X-Client-Version"

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
        // The web bundle reports its build on every request (#752). A custom header is not a "simple"
        // one, so the browser lists it in the preflight's Access-Control-Request-Headers — and Ktor's
        // CORS plugin 403s the whole preflight when any listed header is unknown. Omitting it here took
        // production down: every API call from https://skopeo.co failed before routing, while local dev
        // was unaffected because the Vite proxy makes /api same-origin and never preflights.
        allowHeader(header = CLIENT_VERSION_HEADER)
    }
    logger.info { "CORS configured for web UI origins" }
}

fun Application.configureOpenAPI() {
    // The interactive docs (Swagger UI + raw spec) are unauthenticated. They default to exposed, but can
    // be turned off in a hardened deployment via `docs.exposed=false` (env DOCS_EXPOSED) before opening
    // the API to external clients (#599).
    val docsExposed = environment.config.propertyOrNull(path = "docs.exposed")?.getString()?.toBoolean() ?: true
    if (!docsExposed) {
        logger.info { "API documentation endpoints are disabled (docs.exposed=false)" }
        return
    }
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
    logger.info { "Routing configured with endpoints: /, /health, /api/v1/calculate-ranking" }
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
