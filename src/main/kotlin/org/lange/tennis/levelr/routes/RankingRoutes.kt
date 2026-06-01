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
import org.lange.tennis.levelr.dto.RankingCalculationResponse
import org.lange.tennis.levelr.dto.RatingChange
import org.lange.tennis.levelr.model.Rating

private val logger = KotlinLogging.logger {}

fun Application.configureRankingRoutes() {
    routing {
        post("/api/v1/calculate-ranking") {
            logger.info { "Received ranking calculation request" }

            try {
                // Parse and validate request
                val request = call.receive<RankingCalculationRequest>()

                logger.info {
                    "Processing ranking calculation for players: ${request.players.keys.joinToString()}"
                }

                // TODO: Implement actual ranking calculation algorithm
                // For now, return hardcoded response with small rating changes

                val playerIds = request.players.keys.toList()
                val player1Id = playerIds[0]
                val player2Id = playerIds[1]

                val player1 = request.players[player1Id]!!
                val player2 = request.players[player2Id]!!

                // Hardcoded: Player 1 wins, gets +0.02, Player 2 gets -0.02
                val player1NewRating =
                    Rating(
                        value = player1.rating.value + 0.02,
                        system = player1.rating.system,
                    )
                val player2NewRating =
                    Rating(
                        value = player2.rating.value - 0.02,
                        system = player2.rating.system,
                    )

                val updatedPlayers =
                    mapOf(
                        player1Id to player1.copy(rating = player1NewRating),
                        player2Id to player2.copy(rating = player2NewRating),
                    )

                val ratingChanges =
                    mapOf(
                        player1Id to
                            RatingChange(
                                change = 0.02,
                                percentChange = (0.02 / player1.rating.value) * 100,
                                previousRating = player1.rating,
                                newRating = player1NewRating,
                            ),
                        player2Id to
                            RatingChange(
                                change = -0.02,
                                percentChange = (-0.02 / player2.rating.value) * 100,
                                previousRating = player2.rating,
                                newRating = player2NewRating,
                            ),
                    )

                val response =
                    RankingCalculationResponse(
                        players = updatedPlayers,
                        ratingChanges = ratingChanges,
                    )

                logger.info { "Ranking calculation completed successfully" }
                call.respond(HttpStatusCode.OK, response)
            } catch (e: IllegalArgumentException) {
                logger.warn(e) { "Validation error in ranking calculation request" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Validation error", "message" to (e.message ?: "Invalid request")),
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
