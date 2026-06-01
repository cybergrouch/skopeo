package org.lange.tennis.levelr

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import mu.KotlinLogging
import org.lange.tennis.levelr.routes.configureRankingRoutes
import org.slf4j.event.Level

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting Tennis Levelr API..." }
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureMonitoring()
    configurePlugins()
    configureRouting()
    configureRankingRoutes()
    logger.info { "Tennis Levelr API started successfully on port 8080" }
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

fun Application.configureRouting() {
    routing {
        get("/") {
            logger.info { "Root endpoint accessed" }
            call.respondText("Tennis Levelr API")
        }

        get("/health") {
            logger.debug { "Health check endpoint accessed" }
            call.respond(
                status = HttpStatusCode.OK,
                message =
                    mapOf(
                        "status" to "UP",
                        "service" to "Tennis Levelr API",
                        "version" to "0.0.1-SNAPSHOT",
                    ),
            )
        }
    }
    logger.info { "Routing configured with endpoints: /, /health, /metrics, /api/v1/calculate-ranking" }
}
