// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import mu.KotlinLogging
import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.service.calculator.RankingCalculator
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl

private val logger = KotlinLogging.logger {}

fun Application.configureRankingRoutes() {
    // TODO: Make this configurable via dependency injection
    // For now, hardcode to use the performance-based implementation
    val rankingCalculator: RankingCalculator = PerformanceBasedRankingCalculatorImpl()

    routing {
        post(path = "/api/v1/calculate-ranking") {
            logger.info { "Received ranking calculation request" }

            try {
                // Parse and validate request
                val request = call.receive<RankingCalculationRequest>()

                logger.info {
                    "Processing ranking calculation for teams: ${request.teams.keys.joinToString()}"
                }

                // Calculate ranking using Elo-based algorithm (pure function)
                val result = rankingCalculator.calculate(request = request)

                // Log the audit trail from the calculation
                result.audit.forEach { entry ->
                    logger.info { entry.message }
                }

                logger.info { "Ranking calculation completed successfully" }
                call.respond(status = HttpStatusCode.OK, message = result.response)
            } catch (e: BadRequestException) {
                // Ktor's content negotiation wraps deserialization + DTO `init` validation
                // failures (SerializationException, IllegalArgumentException) in BadRequestException,
                // so this single catch handles all malformed/invalid request bodies.
                logger.warn(t = e) { "Invalid request body in ranking calculation request" }
                call.respond(status = HttpStatusCode.BadRequest, message = badRequestErrorBody(e = e))
            } catch (e: Exception) {
                logger.error(t = e) { "Error processing ranking calculation request" }
                call.respond(
                    status = HttpStatusCode.InternalServerError,
                    message = mapOf("error" to "Internal server error", "message" to "An unexpected error occurred"),
                )
            }
        }
    }
}

/**
 * Build the error body for a request that failed while being deserialized.
 *
 * Ktor wraps exceptions thrown during body deserialization (JSON syntax errors and
 * DTO init-block validation) in [BadRequestException]; unwrap to the root cause so
 * the client sees the actual validation message.
 */
private fun badRequestErrorBody(e: BadRequestException): Map<String, String> {
    val rootCause = generateSequence<Throwable>(seed = e) { it.cause }.last()
    val error = if (rootCause is kotlinx.serialization.SerializationException) "Invalid JSON" else "Validation error"
    return mapOf("error" to error, "message" to (rootCause.message ?: "Invalid request"))
}
