package org.lange.tennis.levelr.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import mu.KotlinLogging
import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.service.AuditLevel
import org.lange.tennis.levelr.service.RankingCalculator

private val logger = KotlinLogging.logger {}

fun Application.configureRankingRoutes() {
    val rankingCalculator = RankingCalculator()

    routing {
        post("/api/v1/calculate-ranking") {
            logger.info { "Received ranking calculation request" }

            try {
                // Parse and validate request
                val request = call.receive<RankingCalculationRequest>()

                logger.info {
                    "Processing ranking calculation for players: ${request.players.keys.joinToString()}"
                }

                // Calculate ranking using ELO-based algorithm (pure function)
                val result = rankingCalculator.calculate(request)

                // Log the audit trail from the calculation
                result.audit.forEach { entry ->
                    when (entry.level) {
                        AuditLevel.DEBUG -> logger.debug { entry.message }
                        AuditLevel.INFO -> logger.info { entry.message }
                        AuditLevel.WARN -> logger.warn { entry.message }
                        AuditLevel.ERROR -> logger.error { entry.message }
                    }
                }

                logger.info { "Ranking calculation completed successfully" }
                call.respond(HttpStatusCode.OK, result.response)
            } catch (e: IllegalArgumentException) {
                logger.warn(e) { "Validation error in ranking calculation request" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Validation error", "message" to (e.message ?: "Invalid request")),
                )
            } catch (e: kotlinx.serialization.SerializationException) {
                logger.warn(e) { "JSON serialization error in ranking calculation request" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid JSON", "message" to (e.message ?: "Invalid request format")),
                )
            } catch (e: Exception) {
                logger.error(e) { "Error processing ranking calculation request" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Internal server error", "message" to "An unexpected error occurred"),
                )
            }
        }
    }
}
