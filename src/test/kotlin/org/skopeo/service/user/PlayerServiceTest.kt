// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ResourceNotFoundException
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

        val profile = service.publicProfile(code = user.publicCode.lowercase())

        profile.publicCode shouldBe user.publicCode
        profile.displayName shouldBe "Ana"
        profile.rating?.level shouldBe "4.0"
    }

    @Test
    fun `omits the rating when the player has none`() {
        val user = newUser(uid = "p2", names = listOf(element = UserName(type = NameType.DISPLAY, value = "Bea")))

        service.publicProfile(code = user.publicCode).rating.shouldBeNull()
    }

    @Test
    fun `an unknown code is a not-found`() {
        shouldThrow<ResourceNotFoundException> { service.publicProfile(code = "ZZZZZZ") }
    }

    @Test
    fun `an inactive player is a not-found`() {
        val user = newUser(uid = "p3", names = listOf(element = UserName(type = NameType.DISPLAY, value = "Cy")))
        users.deactivate(id = user.id)

        shouldThrow<ResourceNotFoundException> { service.publicProfile(code = user.publicCode) }
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

        val historyForAna = service.matchHistory(code = ana.publicCode.lowercase())

        historyForAna shouldHaveSize 2
        val upcoming = historyForAna[0]
        upcoming.matchDate shouldBe "2026-03-01"
        upcoming.rated shouldBe false
        upcoming.result.shouldBeNull()
        upcoming.playerLevelAtMatch.shouldBeNull()
        upcoming.opponent?.displayName shouldBe "Ben"

        val win = historyForAna[1]
        win.rated shouldBe true
        win.result shouldBe "WIN"
        win.setScores shouldBe listOf("6-4", "6-3")
        win.opponent?.displayName shouldBe "Ben"
        win.opponent?.publicCode shouldBe ben.publicCode
        win.playerLevelAtMatch shouldBe "4.0"
        win.opponentLevelAtMatch shouldBe "3.5"
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
        val historyForBen = service.matchHistory(code = ben.publicCode)

        historyForBen shouldHaveSize 1
        historyForBen[0].result shouldBe "LOSS"
        historyForBen[0].setScores shouldBe listOf(element = "4-6")
        historyForBen[0].rated shouldBe false
    }

    @Test
    fun `match history is empty for a player with no matches`() {
        val user = newUser(uid = "lonely", names = display(name = "Solo"))

        service.matchHistory(code = user.publicCode) shouldHaveSize 0
    }

    @Test
    fun `match history for an unknown code is a not-found`() {
        shouldThrow<ResourceNotFoundException> { service.matchHistory(code = "ZZZZZZ") }
    }

    @Test
    fun `an admin reads a player's rating history by code, and a non-admin is forbidden`() {
        newAdmin(uid = "admin")
        val player = newUser(uid = "p", names = display(name = "Ana"))
        val other = newUser(uid = "o", names = display(name = "Bob"))
        val match = fixture(u1 = player.id, u2 = other.id, date = LocalDate.of(2026, 1, 1))
        history(userId = player.id, matchId = match.id, previousLevel = "3.5")

        service.ratingHistory(token = token(uid = "admin"), code = player.publicCode.lowercase()) shouldHaveSize 1

        // A plain player cannot read anyone's rating history by code.
        newUser(uid = "plain", names = display(name = "Plain"))
        shouldThrow<ForbiddenException> { service.ratingHistory(token = token(uid = "plain"), code = player.publicCode) }
    }

    @Test
    fun `rating history by code rejects an unknown caller and an unknown code`() {
        newAdmin(uid = "admin")
        // No provisioned user for this token → not an admin → forbidden.
        shouldThrow<ForbiddenException> { service.ratingHistory(token = token(uid = "ghost"), code = "ABC234") }
        // Admin, but the code resolves to nobody.
        shouldThrow<ResourceNotFoundException> { service.ratingHistory(token = token(uid = "admin"), code = "ZZZZZZ") }
    }
}
