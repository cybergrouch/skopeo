// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.user.ResultsBucket
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.Match
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.ServiceError
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class PlayerServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val matches = MatchRepository()
    private val service = PlayerService(users = users, ratings = ratings, matches = matches)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(
        uid: String,
        names: List<UserName>,
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = names,
                ),
        )

    private fun display(name: String): List<UserName> = listOf(element = UserName(type = NameType.DISPLAY, value = name))

    private fun newAdmin(uid: String): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = display(name = "Admin"),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun fixture(
        u1: UUID,
        u2: UUID,
        date: LocalDate,
    ) = matches.createFixture(
        command =
            CreateFixtureCommand(
                matchFormat = TeamType.SINGLES,
                matchType = MatchType.OPEN_PLAY,
                matchDate = date,
                team1UserIds = listOf(element = u1),
                team2UserIds = listOf(element = u2),
                team1Name = "T1",
                team2Name = "T2",
                createdBy = u1,
            ),
    )

    private fun doublesFixture(
        team1: List<UUID>,
        team2: List<UUID>,
        date: LocalDate,
    ) = matches.createFixture(
        command =
            CreateFixtureCommand(
                matchFormat = TeamType.DOUBLES,
                matchType = MatchType.OPEN_PLAY,
                matchDate = date,
                team1UserIds = team1,
                team2UserIds = team2,
                team1Name = "T1",
                team2Name = "T2",
                createdBy = team1.first(),
            ),
    )

    /** Append a single player's rating-history row for a match, carrying their pre-match band. */
    private fun history(
        userId: UUID,
        matchId: UUID,
        previousLevel: String,
    ) = ratings.appendHistory(
        write =
            RatingHistoryWrite(
                userId = userId,
                matchId = matchId,
                previousRating = BigDecimal("4.0"),
                newRating = BigDecimal("4.1"),
                ratingChange = BigDecimal("0.1"),
                percentChange = null,
                previousLevel = previousLevel,
                newLevel = previousLevel,
                levelChanged = false,
                breakdown = null,
                calculatedAt = LocalDateTime.now(),
            ),
    )

    @Test
    fun `resolves a public profile by code (case-insensitive) with the active display name and rating`() {
        // Two names so the DISPLAY filter is exercised against a non-display name too.
        val user =
            newUser(
                uid = "p1",
                names =
                    listOf(
                        UserName(type = NameType.FIRST, value = "Ignore"),
                        UserName(type = NameType.DISPLAY, value = "Ana"),
                    ),
            )
        ratings.setRating(userId = user.id, rating = BigDecimal("4.0"), level = "4.0", confidence = BigDecimal("0.50"))

        val profile = service.publicProfile(code = user.publicCode.lowercase()).shouldBeRight()

        profile.publicCode shouldBe user.publicCode
        profile.displayName shouldBe "Ana"
        profile.rating?.level shouldBe "4.0"
    }

    @Test
    fun `omits the rating when the player has none`() {
        val user = newUser(uid = "p2", names = listOf(element = UserName(type = NameType.DISPLAY, value = "Bea")))

        service.publicProfile(code = user.publicCode).shouldBeRight().rating.shouldBeNull()
    }

    @Test
    fun `an unknown code is a not-found`() {
        service.publicProfile(code = "ZZZZZZ").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `an inactive player is a not-found`() {
        val user = newUser(uid = "p3", names = listOf(element = UserName(type = NameType.DISPLAY, value = "Cy")))
        users.deactivate(id = user.id)

        service.publicProfile(code = user.publicCode).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a disabled duplicate's public profile is a merged card linking to the canonical (#124)`() {
        val canonical = newUser(uid = "keep", names = display(name = "Real"))
        val dup = newUser(uid = "dup", names = display(name = "Dupe"))
        users.markDuplicates(canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))

        val profile = service.publicProfile(code = dup.publicCode.lowercase()).shouldBeRight()

        profile.isDisabled shouldBe true
        profile.canonical?.publicCode shouldBe canonical.publicCode
        profile.canonical?.displayName shouldBe "Real"
        profile.rating.shouldBeNull()
    }

    @Test
    fun `the canonical's match history includes a duplicate's matches (#124)`() {
        val canonical = newUser(uid = "keep", names = display(name = "Real"))
        val dup = newUser(uid = "dup", names = display(name = "Dupe"))
        val opp = newUser(uid = "opp", names = display(name = "Opp"))
        // A match the DUPLICATE played, then the duplicate is folded into the canonical.
        val match = fixture(u1 = dup.id, u2 = opp.id, date = LocalDate.of(2026, 1, 1))
        users.markDuplicates(canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))

        val history = service.matchHistory(code = canonical.publicCode).shouldBeRight()

        history shouldHaveSize 1
        history.single().let {
            it.matchId shouldBe match.id.toString()
            it.opponents.single().displayName shouldBe "Opp"
            it.partners.shouldBeEmpty()
        }
    }

    @Test
    fun `match history lists newest first, orients the result and exposes at-the-time bands for rated matches`() {
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        val ben = newUser(uid = "b", names = display(name = "Ben"))

        // A rated win in January, reconstructable from each player's history row.
        val rated = fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 1, 1))
        matches.addResult(
            matchId = rated.id,
            sets =
                listOf(
                    MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = rated.team1.teamId),
                    MatchSetResult(setNumber = 2, team1Games = 6, team2Games = 3, winnerTeamId = rated.team1.teamId),
                ),
            winnerTeamId = rated.team1.teamId,
            recordedBy = ana.id,
            completedAt = LocalDateTime.now(),
        )
        matches.markRated(matchId = rated.id, ratedAt = LocalDateTime.now(), ratedBy = ana.id)
        history(userId = ana.id, matchId = rated.id, previousLevel = "4.0")
        history(userId = ben.id, matchId = rated.id, previousLevel = "3.5")

        // A later, still-scheduled fixture — no result, no bands.
        fixture(u1 = ben.id, u2 = ana.id, date = LocalDate.of(2026, 3, 1))

        val historyForAna = service.matchHistory(code = ana.publicCode.lowercase()).shouldBeRight()

        historyForAna shouldHaveSize 2
        val upcoming = historyForAna[0]
        upcoming.matchDate shouldBe "2026-03-01"
        upcoming.rated shouldBe false
        upcoming.result.shouldBeNull()
        upcoming.playerLevelAtMatch.shouldBeNull()
        upcoming.opponents.single().displayName shouldBe "Ben"

        val win = historyForAna[1]
        win.rated shouldBe true
        win.result shouldBe "WIN"
        win.setScores shouldBe listOf("6-4", "6-3")
        win.partners.shouldBeEmpty()
        win.opponents.single().displayName shouldBe "Ben"
        win.opponents.single().publicCode shouldBe ben.publicCode
        win.playerLevelAtMatch shouldBe "4.0"
        win.opponents.single().levelAtMatch shouldBe "3.5"
    }

    @Test
    fun `match history orients scores and result from the opponent's perspective`() {
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        val ben = newUser(uid = "b", names = display(name = "Ben"))
        val match = fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 1, 1))
        matches.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = ana.id,
            completedAt = LocalDateTime.now(),
        )

        // Ben is on team2, so from his perspective the set reads 4-6 and the result is a loss.
        val historyForBen = service.matchHistory(code = ben.publicCode).shouldBeRight()

        historyForBen shouldHaveSize 1
        historyForBen[0].result shouldBe "LOSS"
        historyForBen[0].setScores shouldBe listOf(element = "4-6")
        historyForBen[0].rated shouldBe false
    }

    /** Record a winner for [match] so it counts as a decided result. */
    private fun decide(
        match: Match,
        winnerTeamId: UUID,
        recordedBy: UUID,
    ) = matches.addResult(
        matchId = match.id,
        sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = winnerTeamId)),
        winnerTeamId = winnerTeamId,
        recordedBy = recordedBy,
        completedAt = LocalDateTime.now(),
    )

    @Test
    fun `results summary buckets wins and losses by month, split singles vs doubles (#276)`() {
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        val ben = newUser(uid = "b", names = display(name = "Ben"))
        val cy = newUser(uid = "c", names = display(name = "Cy"))
        val deb = newUser(uid = "d", names = display(name = "Deb"))

        // Singles: a Jan win + a Jan loss (Ana on team2 of a team1 win), and a Feb win.
        val janWin = fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 1, 5))
        decide(match = janWin, winnerTeamId = janWin.team1.teamId, recordedBy = ana.id)
        val janLoss = fixture(u1 = ben.id, u2 = ana.id, date = LocalDate.of(2026, 1, 20))
        decide(match = janLoss, winnerTeamId = janLoss.team1.teamId, recordedBy = ben.id)
        val febWin = fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 2, 2))
        decide(match = febWin, winnerTeamId = febWin.team1.teamId, recordedBy = ana.id)

        // Doubles: a Jan win for Ana & Ben over Cy & Deb (MIXED_DOUBLES would count here too).
        val dbl = doublesFixture(team1 = listOf(ana.id, ben.id), team2 = listOf(cy.id, deb.id), date = LocalDate.of(2026, 1, 10))
        decide(match = dbl, winnerTeamId = dbl.team1.teamId, recordedBy = ana.id)

        // A still-scheduled match (no recorded winner) must NOT count.
        fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 3, 1))

        val summary = service.resultsSummary(code = ana.publicCode.lowercase()).shouldBeRight()

        summary.singles shouldBe
            listOf(
                ResultsBucket(period = "2026-01", wins = 1, losses = 1),
                ResultsBucket(period = "2026-02", wins = 1, losses = 0),
            )
        summary.doubles shouldBe listOf(element = ResultsBucket(period = "2026-01", wins = 1, losses = 0))
    }

    @Test
    fun `results summary is empty for a player with no decided matches`() {
        val user = newUser(uid = "solo", names = display(name = "Solo"))
        val summary = service.resultsSummary(code = user.publicCode).shouldBeRight()
        summary.singles.shouldBeEmpty()
        summary.doubles.shouldBeEmpty()
    }

    @Test
    fun `doubles match history surfaces the partner and both opponents with their at-the-time bands (#256)`() {
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        val bea = newUser(uid = "b", names = display(name = "Bea"))
        val cy = newUser(uid = "c", names = display(name = "Cy"))
        val deb = newUser(uid = "d", names = display(name = "Deb"))

        // Ana & Bea (team1) beat Cy & Deb (team2), then the match is rated.
        val match = doublesFixture(team1 = listOf(ana.id, bea.id), team2 = listOf(cy.id, deb.id), date = LocalDate.of(2026, 1, 1))
        matches.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 3, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = ana.id,
            completedAt = LocalDateTime.now(),
        )
        matches.markRated(matchId = match.id, ratedAt = LocalDateTime.now(), ratedBy = ana.id)
        history(userId = ana.id, matchId = match.id, previousLevel = "4.0")
        history(userId = bea.id, matchId = match.id, previousLevel = "3.5")
        history(userId = cy.id, matchId = match.id, previousLevel = "3.0")
        history(userId = deb.id, matchId = match.id, previousLevel = "3.5")

        val entry = service.matchHistory(code = ana.publicCode).shouldBeRight().single()

        entry.result shouldBe "WIN"
        entry.playerLevelAtMatch shouldBe "4.0"
        // The partner (Bea) is surfaced, not folded into the opponents.
        entry.partners.single().displayName shouldBe "Bea"
        entry.partners.single().levelAtMatch shouldBe "3.5"
        // Both opponents appear, each with their own band at the time.
        entry.opponents.map { it.displayName } shouldContainExactlyInAnyOrder listOf("Cy", "Deb")
        entry.opponents.map { it.levelAtMatch } shouldContainExactlyInAnyOrder listOf("3.0", "3.5")
    }

    @Test
    fun `match history is empty for a player with no matches`() {
        val user = newUser(uid = "lonely", names = display(name = "Solo"))

        service.matchHistory(code = user.publicCode).shouldBeRight() shouldHaveSize 0
    }

    @Test
    fun `match history for an unknown code is a not-found`() {
        service.matchHistory(code = "ZZZZZZ").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `match history for a deactivated player's code is a not-found`() {
        val user = newUser(uid = "gone", names = display(name = "Gone"))
        users.deactivate(id = user.id)

        service.matchHistory(code = user.publicCode).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `an admin reads a player's rating history by code, and a non-admin is forbidden`() {
        newAdmin(uid = "admin")
        val player = newUser(uid = "p", names = display(name = "Ana"))
        val other = newUser(uid = "o", names = display(name = "Bob"))
        val match = fixture(u1 = player.id, u2 = other.id, date = LocalDate.of(2026, 1, 1))
        history(userId = player.id, matchId = match.id, previousLevel = "3.5")

        service.ratingHistory(token = token(uid = "admin"), code = player.publicCode.lowercase()).shouldBeRight() shouldHaveSize 1

        // A plain player cannot read anyone's rating history by code.
        newUser(uid = "plain", names = display(name = "Plain"))
        service
            .ratingHistory(token = token(uid = "plain"), code = player.publicCode)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `rating history by code rejects an unknown caller and an unknown code`() {
        newAdmin(uid = "admin")
        // No provisioned user for this token → not an admin → forbidden.
        service.ratingHistory(token = token(uid = "ghost"), code = "ABC234").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // Admin, but the code resolves to nobody.
        service.ratingHistory(token = token(uid = "admin"), code = "ZZZZZZ").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }
}
