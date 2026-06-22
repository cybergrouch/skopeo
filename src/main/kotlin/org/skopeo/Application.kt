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
import org.skopeo.routes.configureRankingRoutes
import org.skopeo.routes.configureUserRoutes
import org.slf4j.event.Level

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "Starting Skopeo API..." }
    EngineMain.main(args)
}

fun Application.module(
    initDatabase: Boolean = true,
    firebaseAuth: FirebaseAuthSettings? = null,
) {
    if (initDatabase) {
        // Initialize database connection and run migrations
        DatabaseConfig.init(this)

        // Set up shutdown hook to close database connection
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
            logger.info { "Application stopping, closing database connections..." }
            DatabaseConfig.close()
        }
    }

    configureMonitoring()
    configurePlugins()
    configureCORS()
    configureSecurity(firebaseAuth)
    configureOpenAPI()
    configureRouting()
    configureRankingRoutes()
    configureUserRoutes()
    logger.info { "Skopeo API started successfully on port 8080" }
}

fun Application.configureMonitoring() {
    // Request/Response logging
    install(CallLogging) {
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
    install(MicrometerMetrics) {
        registry = prometheusRegistry
    }
    logger.info { "Metrics monitoring configured (Prometheus)" }

    // Expose metrics endpoint
    routing {
        get("/metrics") {
            logger.debug { "Metrics endpoint accessed" }
            call.respond(prometheusRegistry.scrape())
        }
    }
    logger.info { "Metrics endpoint available at /metrics" }
}

fun Application.configurePlugins() {
    install(ContentNegotiation) {
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
 * Add the deployed web UI origin (e.g. the Firebase Hosting domain) when it is known.
 */
fun Application.configureCORS() {
    install(CORS) {
        // Local development: Vite dev server default origin
        allowHost(host = "localhost:5173", schemes = listOf("http", "https"))
        // TODO: add the deployed web UI origin, e.g.:
        // allowHost(host = "skopeo-web.web.app", schemes = listOf("https"))

        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)

        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }
    logger.info { "CORS configured for web UI origins" }
}

fun Application.configureOpenAPI() {
    routing {
        // Serve raw OpenAPI specification file
        get("/openapi.yaml") {
            logger.debug { "OpenAPI YAML specification requested" }
            val yamlContent =
                this::class.java.classLoader.getResource("openapi/documentation.yaml")?.readText()
                    ?: throw IllegalStateException("OpenAPI specification file not found")
            call.respondText(yamlContent, ContentType.Text.Plain)
        }

        // Serve Swagger UI (interactive API documentation)
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
    }
    logger.info { "API documentation available at /swagger (Swagger UI) and /openapi.yaml (raw spec)" }
}

fun Application.configureRouting() {
    routing {
        get("/") {
            logger.info { "Root endpoint accessed" }
            call.respondText("Skopeo API")
        }

        get("/health") {
            logger.debug { "Health check endpoint accessed" }
            call.respond(
                status = HttpStatusCode.OK,
                message =
                    mapOf(
                        "status" to "UP",
                        "service" to "Skopeo API",
                        "version" to "0.0.1-SNAPSHOT",
                    ),
            )
        }
    }
    logger.info { "Routing configured with endpoints: /, /health, /metrics, /api/v1/calculate-ranking" }
}
