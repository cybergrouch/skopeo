package org.skopeo.service.calculator.impl

data class RankingTestCase(
    val scenario: TestScenario,
    val expectedPlayer1Rating: String,
    val expectedPlayer2Rating: String,
) {
    val winnerId: String
        get() = if (scenario.matchScore.isPlayer1Winner) "P1" else "P2"

    override fun toString(): String = scenario.description
}

sealed class TestScore(
    open val player1Games: Int,
    open val player2Games: Int,
) {
    open val isPlayer1Winner: Boolean get() = player1Games > player2Games

    data class MatchScore(
        override val player1Games: Int,
        override val player2Games: Int,
        val setScores: Map<Int, SetScore> = emptyMap(),
    ) : TestScore(player1Games, player2Games)

    data class SetScore(
        override val player1Games: Int,
        override val player2Games: Int,
        val tieBrakeScore: TieBreakScore? = null,
    ) : TestScore(player1Games, player2Games) {
        override val isPlayer1Winner: Boolean get() =
            when {
                player1Games != player2Games -> player1Games > player2Games
                else -> tieBrakeScore?.isPlayer1Winner ?: false
            }
    }

    data class TieBreakScore(
        override val player1Games: Int,
        override val player2Games: Int,
    ) : TestScore(player1Games, player2Games)
}

data class TestScenario(
    val player1Rating: String,
    val player2Rating: String,
    val ratingSystem: String,
    val matchScore: TestScore.MatchScore,
) {
    val isUpset =
        matchScore.isPlayer1Winner && player1Rating.toDouble() < player2Rating.toDouble()

    val description =
        if (isUpset) {
            "$ratingSystem: Lower rated player wins upset (${matchScore.player1Games}-${matchScore.player2Games})"
        } else {
            "$ratingSystem: Higher rated player wins (${matchScore.player1Games}-${matchScore.player2Games})"
        }

    val swapPlayerRatings: TestScenario
        get() =
            TestScenario(
                player1Rating = player2Rating,
                player2Rating = player1Rating,
                ratingSystem = ratingSystem,
                matchScore = matchScore,
            )
}

object Scenarios {
    fun createMatchScores(
        player1Games: Int,
        player2Games: Int,
        tieBreakScore: TestScore.TieBreakScore? = null,
    ): TestScore.MatchScore =
        TestScore.MatchScore(
            player1Games = player1Games,
            player2Games = player2Games,
            setScores =
                mapOf(
                    1 to
                        TestScore.SetScore(
                            player1Games = player1Games,
                            player2Games = player2Games,
                            tieBrakeScore = tieBreakScore,
                        ),
                ),
        )

    val scenario_Hw_6_0 =
        TestScenario(
            player1Rating = "4.5",
            player2Rating = "4.0",
            ratingSystem = "NTRP",
            matchScore =
                createMatchScores(
                    player1Games = 6,
                    player2Games = 0,
                ),
        )

    val scenario_Hw_6_1 =
        TestScenario(
            player1Rating = "4.5",
            player2Rating = "4.0",
            ratingSystem = "NTRP",
            matchScore =
                createMatchScores(
                    player1Games = 6,
                    player2Games = 1,
                ),
        )

    val scenario_Hw_6_2 =
        TestScenario(
            player1Rating = "4.5",
            player2Rating = "4.0",
            ratingSystem = "NTRP",
            matchScore =
                createMatchScores(
                    player1Games = 6,
                    player2Games = 2,
                ),
        )

    val scenario_Hw_6_3 =
        TestScenario(
            player1Rating = "4.5",
            player2Rating = "4.0",
            ratingSystem = "NTRP",
            matchScore =
                createMatchScores(
                    player1Games = 6,
                    player2Games = 3,
                ),
        )

    val scenario_Hw_6_4 =
        TestScenario(
            player1Rating = "4.5",
            player2Rating = "4.0",
            ratingSystem = "NTRP",
            matchScore =
                createMatchScores(
                    player1Games = 6,
                    player2Games = 4,
                ),
        )

    // 6-5 is an invalid tennis score (would require tiebreak at 6-6)
    // Commenting out until validation is fixed

    /*
    val scenario_Hw_6_5 =
        TestScenario(
            player1Rating = "4.5",
            player2Rating = "4.0",
            ratingSystem = "NTRP",
            matchScore = createMatchScores(
                player1Games = 6,
                player2Games = 5
            )
        )
     */

    val scenario_Hw_7_5 =
        TestScenario(
            player1Rating = "4.5",
            player2Rating = "4.0",
            ratingSystem = "NTRP",
            matchScore =
                createMatchScores(
                    player1Games = 7,
                    player2Games = 5,
                    tieBreakScore =
                        TestScore.TieBreakScore(
                            player1Games = 1,
                            player2Games = 0,
                        ),
                ),
        )

    // 7-6 requires a tiebreak to be specified
    // Commenting out until validation is fixed or tiebreak is added

    /*
    val scenario_Hw_7_6 =
        TestScenario(
            player1Rating = "4.5",
            player2Rating = "4.0",
            ratingSystem = "NTRP",
            matchScore = createMatchScores(
                player1Games = 7,
                player2Games = 6
            )
        )
     */

    val scenario_Lw_6_0 =
        scenario_Hw_6_0.swapPlayerRatings

    val scenario_Lw_6_1 =
        scenario_Hw_6_1.swapPlayerRatings

    val scenario_Lw_6_2 =
        scenario_Hw_6_2.swapPlayerRatings

    val scenario_Lw_6_3 =
        scenario_Hw_6_3.swapPlayerRatings

    val scenario_Lw_6_4 =
        scenario_Hw_6_4.swapPlayerRatings

    /*
    val scenario_Lw_6_5 =
        scenario_Hw_6_5.swapPlayerRatings
     */

    val scenario_Lw_7_5 =
        scenario_Hw_7_5.swapPlayerRatings

    // 7-6 requires a tiebreak to be specified
    // Commenting out until validation is fixed or tiebreak is added

    /*
    val scenario_Lw_7_6 =
        scenario_Hw_7_5.swapPlayerRatings
     */
}
