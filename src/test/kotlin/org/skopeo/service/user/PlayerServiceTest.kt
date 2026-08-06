// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.dto.user.ResultsBucket
import org.skopeo.mapper.entity.event.toDomain
import org.skopeo.mapper.entity.user.toDomain
import org.skopeo.model.AuthProvider
import org.skopeo.model.AwardStatus
import org.skopeo.model.ContactInfo
import org.skopeo.model.ContactSource
import org.skopeo.model.ContactType
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.Match
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.RankingPointAwardWrite
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Suppress("LargeClass") // broad coverage of the public/standing/points/rating-visibility surfaces (#583); size is inherent
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
        sex: String? = null,
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = names,
                    sex = sex,
                ),
        ).toDomain()

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
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun emailContact(value: String): ContactInfo =
        ContactInfo(
            type = ContactType.EMAIL,
            value = value,
            source = ContactSource.MANUAL,
            status = VerificationStatus.VERIFIED,
            isPrimary = true,
        )

    private fun newUserWithEmail(
        uid: String,
        name: String,
        email: String,
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = display(name = name),
                    email = emailContact(value = email),
                ),
        ).toDomain()

    private fun viewerWith(
        uid: String,
        capabilities: Set<Capability>,
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = display(name = uid),
                    capabilities = capabilities,
                ),
        ).toDomain()

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
                completedAt = null,
                calculatedAt = LocalDateTime.now(),
                ratingRunId = null,
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
        ratings.setRating(userId = user.id, rating = BigDecimal("4.0"), level = "4.0")

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
    fun `the profile owner sees their own email on their public profile (#630)`() {
        val owner = newUserWithEmail(uid = "own", name = "Ana", email = "ana@example.com")

        val profile = service.publicProfile(code = owner.publicCode, token = token(uid = "own")).shouldBeRight()

        profile.email shouldBe "ana@example.com"
    }

    @Test
    fun `a HOST, CLUB_OWNER, RATER or ADMINISTRATOR viewer sees the player's email (#630)`() {
        val target = newUserWithEmail(uid = "tgt", name = "Bea", email = "bea@example.com")
        val elevated =
            mapOf(
                "h" to Capability.HOST,
                "c" to Capability.CLUB_OWNER,
                "r" to Capability.RATER,
                "a" to Capability.ADMINISTRATOR,
            )
        elevated.forEach { (uid, capability) ->
            viewerWith(uid = uid, capabilities = setOf(Capability.PLAYER, capability))
            val profile = service.publicProfile(code = target.publicCode, token = token(uid = uid)).shouldBeRight()
            profile.email shouldBe "bea@example.com"
        }
    }

    @Test
    fun `another plain PLAYER viewer does not see the player's email (#630)`() {
        val target = newUserWithEmail(uid = "tgt", name = "Bea", email = "bea@example.com")
        // A plain player: newUser provisions with the default PLAYER-only capability set.
        newUser(uid = "peer", names = display(name = "Peer"))

        val profile = service.publicProfile(code = target.publicCode, token = token(uid = "peer")).shouldBeRight()

        profile.email.shouldBeNull()
    }

    @Test
    fun `an anonymous viewer does not see the player's email (#630)`() {
        val target = newUserWithEmail(uid = "tgt", name = "Bea", email = "bea@example.com")

        // No token → the OpenGraph/anonymous path — email must never be included.
        service.publicProfile(code = target.publicCode).shouldBeRight().email.shouldBeNull()
    }

    @Test
    fun `the email is null when the player has none on file even for a privileged viewer (#630)`() {
        val target = newUser(uid = "tgt", names = display(name = "Bea"))
        viewerWith(uid = "admin", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val profile = service.publicProfile(code = target.publicCode, token = token(uid = "admin")).shouldBeRight()

        profile.email.shouldBeNull()
    }

    @Test
    fun `the canonical's match history includes a duplicate's matches (#124)`() {
        val canonical = newUser(uid = "keep", names = display(name = "Real"))
        val dup = newUser(uid = "dup", names = display(name = "Dupe"))
        val opp = newUser(uid = "opp", names = display(name = "Opp"))
        // A match the DUPLICATE played, then the duplicate is folded into the canonical.
        val match = fixture(u1 = dup.id, u2 = opp.id, date = LocalDate.of(2026, 1, 1))
        users.markDuplicates(canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))

        val history = service.matchHistory(code = canonical.publicCode).shouldBeRight().items

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

        val historyForAna = service.matchHistory(code = ana.publicCode.lowercase()).shouldBeRight().items

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
    fun `match history reveals raw NTRP-at-match only to a raw-rating viewer (#654)`() {
        val admin = newAdmin(uid = "admin")
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        val ben = newUser(uid = "b", names = display(name = "Ben"))
        val rated = fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 1, 1))
        matches.addResult(
            matchId = rated.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = rated.team1.teamId)),
            winnerTeamId = rated.team1.teamId,
            recordedBy = ana.id,
            completedAt = LocalDateTime.now(),
        )
        matches.markRated(matchId = rated.id, ratedAt = LocalDateTime.now(), ratedBy = ana.id)
        history(userId = ana.id, matchId = rated.id, previousLevel = "4.0")
        history(userId = ben.id, matchId = rated.id, previousLevel = "3.5")

        // Anonymous viewer: bands only, no raw NTRP-at-match.
        service.matchHistory(code = ana.publicCode).shouldBeRight().items.single().let {
            it.playerLevelAtMatch shouldBe "4.0"
            it.playerRatingAtMatch.shouldBeNull()
            it.opponents.single().ratingAtMatch.shouldBeNull()
        }

        // Admin with raw enabled: raw NTRP-at-match for the player and the opponent.
        service.matchHistory(code = ana.publicCode, token = token(uid = "admin")).shouldBeRight().items.single().let {
            it.playerRatingAtMatch.shouldNotBeNull()
            it.opponents.single().ratingAtMatch.shouldNotBeNull()
        }

        // Admin previewing as a non-admin (#583): raw is hidden again, bands remain.
        users.setPreviewRatingsAsNonAdmin(id = admin.id, preview = true).shouldBeRight()
        service.matchHistory(code = ana.publicCode, token = token(uid = "admin")).shouldBeRight().items.single().let {
            it.playerRatingAtMatch.shouldBeNull()
            it.opponents.single().ratingAtMatch.shouldBeNull()
            it.playerLevelAtMatch shouldBe "4.0"
        }
    }

    private var sparringCounter = 0

    /** Give [userId] a current rating plus [matchCount] in-window completed open-play matches (#459 confidence). */
    @Test
    fun `hidden match history is withheld from unprivileged viewers but shown to owner and elevated roles (#622)`() {
        val ana = newUser(uid = "ana", names = display(name = "Ana"))
        val ben = newUser(uid = "ben", names = display(name = "Ben"))
        fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 1, 1))

        // Default OFF: the history is visible to everyone, including an anonymous viewer.
        service.matchHistory(code = ana.publicCode).shouldBeRight().let { page ->
            page.items.size shouldBe 1
            page.hidden shouldBe false
        }

        users.setMatchHistoryHidden(id = ana.id, hidden = true).shouldBeRight()

        // The owner still sees their own history.
        service.matchHistory(code = ana.publicCode, token = token(uid = "ana")).shouldBeRight().let { page ->
            page.items.size shouldBe 1
            page.hidden shouldBe false
        }
        // An elevated role (RESEARCHER) still sees it.
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "res",
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "res", isPrimary = true),
                    names = display(name = "Res"),
                    capabilities = setOf(Capability.PLAYER, Capability.RESEARCHER),
                ),
        )
        service.matchHistory(code = ana.publicCode, token = token(uid = "res")).shouldBeRight().let { page ->
            page.items.size shouldBe 1
            page.hidden shouldBe false
        }
        // A plain player gets a deliberately-empty, hidden-flagged page.
        newUser(uid = "plain", names = display(name = "Plain"))
        service.matchHistory(code = ana.publicCode, token = token(uid = "plain")).shouldBeRight().let { page ->
            page.items.shouldBeEmpty()
            page.hidden shouldBe true
        }
        // An anonymous viewer likewise.
        service.matchHistory(code = ana.publicCode).shouldBeRight().let { page ->
            page.items.shouldBeEmpty()
            page.hidden shouldBe true
        }
    }

    private fun rampCurrentRating(
        userId: UUID,
        matchCount: Int,
    ) {
        ratings.setRating(userId = userId, rating = BigDecimal("4.0"), level = "4.0")
        repeat(times = matchCount) {
            val opponent = newUser(uid = "spar-${sparringCounter++}", names = display(name = "Spar"))
            val match = fixture(u1 = userId, u2 = opponent.id, date = LocalDate.now())
            matches.addResult(
                matchId = match.id,
                sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 0, winnerTeamId = match.team1.teamId)),
                winnerTeamId = match.team1.teamId,
                recordedBy = userId,
                completedAt = LocalDateTime.now(),
            )
        }
    }

    @Test
    fun `match history carries each player's current rating confidence (#459)`() {
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
        matches.markRated(matchId = match.id, ratedAt = LocalDateTime.now(), ratedBy = ana.id)

        // Current confidence keys off in-window play (#459): Ana 8 open-play matches (wc 4.0 → ≈0.979),
        // Ben 2 (wc 1.0 → ≈0.595). Each ramp adds its own sparring matches, so pick out the Ana-vs-Ben row.
        rampCurrentRating(userId = ana.id, matchCount = 8)
        rampCurrentRating(userId = ben.id, matchCount = 2)

        val win =
            service.matchHistory(code = ana.publicCode).shouldBeRight().items
                .single { row -> row.opponents.any { it.publicCode == ben.publicCode } }
        win.playerConfidence shouldBe "0.979186"
        win.opponents.single().confidence shouldBe "0.595169"
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
        val historyForBen = service.matchHistory(code = ben.publicCode).shouldBeRight().items

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

        val entry = service.matchHistory(code = ana.publicCode).shouldBeRight().items.single()

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

        service.matchHistory(code = user.publicCode).shouldBeRight().items shouldHaveSize 0
    }

    @Test
    fun `match history paginates and searches by opponent (#284)`() {
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        val ben = newUser(uid = "ben", names = display(name = "Ben"))
        val cara = newUser(uid = "cara", names = display(name = "Cara"))
        // Ana's matches, oldest to newest: two vs Ben, then one vs Cara.
        fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 1, 1))
        fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 2, 1))
        fixture(u1 = ana.id, u2 = cara.id, date = LocalDate.of(2026, 3, 1))

        // Page 1 (limit 2): the newest two, with the full total.
        val page1 = service.matchHistory(code = ana.publicCode, limit = 2, offset = 0).shouldBeRight()
        page1.total shouldBe 3
        page1.items.map { it.matchDate } shouldBe listOf("2026-03-01", "2026-02-01")
        // Page 2 (offset 2): the remaining oldest one.
        val page2 = service.matchHistory(code = ana.publicCode, limit = 2, offset = 2).shouldBeRight()
        page2.items.map { it.matchDate } shouldBe listOf(element = "2026-01-01")

        // Search by opponent name narrows to the two Ben matches; total reflects the filter, not the page.
        val benMatches = service.matchHistory(code = ana.publicCode, limit = 20, offset = 0, search = "ben").shouldBeRight()
        benMatches.total shouldBe 2
        benMatches.items.all { it.opponents.single().displayName == "Ben" } shouldBe true

        // Search by public code matches too.
        service.matchHistory(code = ana.publicCode, search = cara.publicCode).shouldBeRight().total shouldBe 1
    }

    @Test
    fun `match history filters by the opponent's at-the-time NTRP band (#563)`() {
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        val ben = newUser(uid = "b", names = display(name = "Ben"))
        val cara = newUser(uid = "c", names = display(name = "Cara"))

        // A rated Ana win vs [opponent], parking the opponent's at-the-time band at [oppLevel].
        fun ratedVs(
            opponent: UUID,
            date: LocalDate,
            oppLevel: String,
        ) {
            val m = fixture(u1 = ana.id, u2 = opponent, date = date)
            matches.addResult(
                matchId = m.id,
                sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = m.team1.teamId)),
                winnerTeamId = m.team1.teamId,
                recordedBy = ana.id,
                completedAt = LocalDateTime.now(),
            )
            matches.markRated(matchId = m.id, ratedAt = LocalDateTime.now(), ratedBy = ana.id)
            history(userId = ana.id, matchId = m.id, previousLevel = "4.0")
            history(userId = opponent, matchId = m.id, previousLevel = oppLevel)
        }

        ratedVs(opponent = ben.id, date = LocalDate.of(2026, 1, 1), oppLevel = "3.5")
        ratedVs(opponent = cara.id, date = LocalDate.of(2026, 2, 1), oppLevel = "4.0")

        // Band 3.5 → only the Ben match; band 4.0 → only Cara; blank → no filter (both).
        val band35 = service.matchHistory(code = ana.publicCode, opponentBand = "3.5").shouldBeRight()
        band35.total shouldBe 1
        band35.items.single().opponents.single().displayName shouldBe "Ben"
        service
            .matchHistory(code = ana.publicCode, opponentBand = "4.0")
            .shouldBeRight()
            .items
            .single()
            .opponents
            .single()
            .displayName shouldBe "Cara"
        service.matchHistory(code = ana.publicCode, opponentBand = "").shouldBeRight().total shouldBe 2
    }

    @Test
    fun `match history search matches on a public code even when the opponent has no display name`() {
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        // An opponent with only a FIRST name has a null display name (the DISPLAY-only filter yields none).
        val noDisplay = newUser(uid = "nd", names = listOf(element = UserName(type = NameType.FIRST, value = "Nemo")))
        fixture(u1 = ana.id, u2 = noDisplay.id, date = LocalDate.of(2026, 1, 1))

        // Searching by the null-display opponent's public code hits the left (publicCode) arm.
        val byCode = service.matchHistory(code = ana.publicCode, search = noDisplay.publicCode.lowercase()).shouldBeRight()
        byCode.total shouldBe 1
        byCode.items.single().opponents.single().displayName.shouldBeNull()

        // A needle that matches neither the code nor the (null) display name exercises the right arm's null branch.
        service.matchHistory(code = ana.publicCode, search = "zzz-no-such-needle").shouldBeRight().total shouldBe 0
    }

    @Test
    fun `match history search hits by display name when the public code does not match`() {
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        val ben = newUser(uid = "ben", names = display(name = "Benjamin"))
        fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 1, 1))

        // "benjamin" cannot be a public code (codes are short), so only the display-name (right) arm can match.
        val byName = service.matchHistory(code = ana.publicCode, search = "benjamin").shouldBeRight()
        byName.total shouldBe 1
        byName.items.single().opponents.single().displayName shouldBe "Benjamin"
    }

    @Test
    fun `a whitespace-only search returns the full history unfiltered`() {
        val ana = newUser(uid = "a", names = display(name = "Ana"))
        val ben = newUser(uid = "ben", names = display(name = "Ben"))
        fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 1, 1))
        fixture(u1 = ana.id, u2 = ben.id, date = LocalDate.of(2026, 2, 1))

        // A blank needle (the empty-needle arm) must not filter anything out.
        val page = service.matchHistory(code = ana.publicCode, search = "   ").shouldBeRight()
        page.total shouldBe 2
        page.items shouldHaveSize 2
    }

    @Test
    fun `results summary for a canonical includes a merged duplicate's decided matches (#124)`() {
        val canonical = newUser(uid = "keep", names = display(name = "Real"))
        val dup = newUser(uid = "dup", names = display(name = "Dupe"))
        val opp = newUser(uid = "opp", names = display(name = "Opp"))
        // A decided match the DUPLICATE played, then folded into the canonical (exercises the findDuplicatesOf arm).
        val match = fixture(u1 = dup.id, u2 = opp.id, date = LocalDate.of(2026, 1, 1))
        decide(match = match, winnerTeamId = match.team1.teamId, recordedBy = dup.id)
        users.markDuplicates(canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))

        val summary = service.resultsSummary(code = canonical.publicCode).shouldBeRight()

        summary.singles shouldBe listOf(element = ResultsBucket(period = "2026-01", wins = 1, losses = 0))
        summary.doubles.shouldBeEmpty()
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
        val admin = newAdmin(uid = "admin")
        val player = newUser(uid = "p", names = display(name = "Ana"))
        val other = newUser(uid = "o", names = display(name = "Bob"))
        val match = fixture(u1 = player.id, u2 = other.id, date = LocalDate.of(2026, 1, 1))
        history(userId = player.id, matchId = match.id, previousLevel = "3.5")

        val adminHistory = service.ratingHistory(token = token(uid = "admin"), code = player.publicCode.lowercase()).shouldBeRight()
        adminHistory shouldHaveSize 1
        // The admin sees the RAW NTRP values, not just bands (#654) — the reveal must reach the DTO.
        adminHistory.single().previousRating.shouldNotBeNull()
        adminHistory.single().newRating.shouldNotBeNull()

        // A plain player cannot read anyone's rating history by code.
        newUser(uid = "plain", names = display(name = "Plain"))
        service
            .ratingHistory(token = token(uid = "plain"), code = player.publicCode)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()

        // An admin previewing as a non-admin (#583/#654) is treated like a non-admin here — the raw
        // rating history is a raw-NTRP reveal, so it's forbidden while the preview toggle is on.
        users.setPreviewRatingsAsNonAdmin(id = admin.id, preview = true).shouldBeRight()
        service
            .ratingHistory(token = token(uid = "admin"), code = player.publicCode)
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

    private val awardRepo = RankingPointRepository()

    /** Insert an ACTIVE award for [userId], optionally linked to [matchId] / [eventId] (#448). */
    private fun grant(
        userId: UUID,
        points: String,
        matchId: UUID? = null,
        eventId: UUID? = null,
        validFrom: LocalDateTime = LocalDateTime.now().minusDays(1),
        validUntil: LocalDateTime = LocalDateTime.now().plusMonths(6),
        status: AwardStatus = AwardStatus.ACTIVE,
    ) = awardRepo.award(
        write =
            RankingPointAwardWrite(
                userId = userId,
                points = BigDecimal(points),
                pointClass = PointClass.SEASONAL_TOURNAMENT_6M,
                sourceType = PointSourceType.INTERNAL,
                sourceId = eventId?.toString(),
                band = "4.0",
                sex = "Male",
                reason = null,
                validFrom = validFrom,
                validUntil = validUntil,
                status = status,
                revokesAwardId = null,
                grantedBy = null,
                awardedAt = LocalDateTime.now(),
                eventId = eventId,
                matchId = matchId,
            ),
    )

    @Test
    fun `standing under RATING reveals the rating only to an ADMINISTRATOR, not the owner (#457, #583)`() {
        // Sex drives the (band, sex) group, so provision the player as Male, then give them a rating.
        val player = newUser(uid = "p", names = display(name = "Ana"), sex = "Male")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.2"), level = "4.0")
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "admin",
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "admin", isPrimary = true),
                    names = display(name = "Admin"),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )

        // The owner (a non-admin) sees rank + band, but NOT their own raw rating (#583).
        val ownerView =
            service.standing(token = token(uid = "p"), code = player.publicCode.lowercase()).shouldBeRight().shouldNotBeNull()
        ownerView.band shouldBe "4.0"
        ownerView.bandLabel shouldBe "NTRP 4.0 Band Race"
        ownerView.sex shouldBe "Male"
        ownerView.rank shouldBe 1
        ownerView.source shouldBe "RATING"
        ownerView.rating.shouldBeNull()
        ownerView.points.shouldBeNull()
        // Only an ADMINISTRATOR sees the precise rating.
        service.standing(token = token(uid = "admin"), code = player.publicCode)
            .shouldBeRight().shouldNotBeNull().rating shouldBe "4.200000"
    }

    @Test
    fun `standing under RATING hides the raw rating from RATER, owner, anonymous and other viewers (#583)`() {
        val player = newUser(uid = "p", names = display(name = "Ana"), sex = "Male")
        ratings.setRating(userId = player.id, rating = BigDecimal("4.2"), level = "4.0")
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "rater",
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "rater", isPrimary = true),
                    names = display(name = "Rater"),
                    capabilities = setOf(Capability.PLAYER, Capability.RATER),
                ),
        )
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "admin",
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "admin", isPrimary = true),
                    names = display(name = "Admin"),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        newUser(uid = "other", names = display(name = "Other"))

        // Only an ADMINISTRATOR sees the precise rating (#583).
        service.standing(token = token(uid = "admin"), code = player.publicCode)
            .shouldBeRight().shouldNotBeNull().rating shouldBe "4.200000"
        // A RATER no longer sees it, nor does the owner, anonymous, or another plain player — band only.
        service.standing(token = token(uid = "rater"), code = player.publicCode)
            .shouldBeRight().shouldNotBeNull().rating.shouldBeNull()
        service.standing(token = token(uid = "p"), code = player.publicCode).shouldBeRight().shouldNotBeNull().rating.shouldBeNull()
        service.standing(token = null, code = player.publicCode).shouldBeRight().shouldNotBeNull().rating.shouldBeNull()
        service.standing(token = token(uid = "other"), code = player.publicCode)
            .shouldBeRight().shouldNotBeNull().rating.shouldBeNull()
    }

    @Test
    fun `standing is a right-null for an unranked (unrated) player (#448)`() {
        val player = newUser(uid = "p", names = display(name = "Ana"))
        service.standing(token = null, code = player.publicCode).shouldBeRight().shouldBeNull()
    }

    @Test
    fun `publicProfile reveals the raw rating only to an ADMINISTRATOR, honoring the preview toggle (#583)`() {
        val player = newUser(uid = "p", names = display(name = "Ana"))
        ratings.setRating(userId = player.id, rating = BigDecimal("4.2"), level = "4.0")
        val admin =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "admin",
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "admin", isPrimary = true),
                        names = display(name = "Admin"),
                        capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                    ),
            ).toDomain()

        // Anonymous + the owner (non-admin): band + confidence, but no raw value.
        service.publicProfile(code = player.publicCode).shouldBeRight().rating.shouldNotBeNull().let {
            it.value.shouldBeNull()
            it.level shouldBe "4.0"
            it.confidence.shouldNotBeNull()
        }
        service.publicProfile(code = player.publicCode, token = token(uid = "p"))
            .shouldBeRight().rating.shouldNotBeNull().value.shouldBeNull()
        // An ADMINISTRATOR sees the raw value.
        service.publicProfile(code = player.publicCode, token = token(uid = "admin"))
            .shouldBeRight().rating.shouldNotBeNull().value shouldBe "4.200000"
        // With the per-admin preview toggle on, that admin now sees the non-admin (band-only) view.
        users.setPreviewRatingsAsNonAdmin(id = admin.id, preview = true).shouldBeRight()
        service.publicProfile(code = player.publicCode, token = token(uid = "admin"))
            .shouldBeRight().rating.shouldNotBeNull().value.shouldBeNull()
    }

    @Test
    fun `active points audit lists only ACTIVE in-window awards, linked to the granting match (#448)`() {
        val owner = newUser(uid = "owner", names = display(name = "Owner"))
        val opp = newUser(uid = "opp", names = display(name = "Opp"))
        val match = fixture(u1 = owner.id, u2 = opp.id, date = LocalDate.now())

        val active = grant(userId = owner.id, points = "30", matchId = match.id)
        // Expired, future, and revoked awards all drop out of the audit.
        grant(
            userId = owner.id,
            points = "10",
            validFrom = LocalDateTime.now().minusMonths(6),
            validUntil = LocalDateTime.now().minusDays(1),
        )
        grant(userId = owner.id, points = "20", validFrom = LocalDateTime.now().plusDays(1), validUntil = LocalDateTime.now().plusMonths(6))
        grant(userId = owner.id, points = "40", status = AwardStatus.REVOKED)

        val rows = service.activePoints(token = token(uid = "owner"), code = owner.publicCode.lowercase()).shouldBeRight()
        rows shouldHaveSize 1
        rows.single().let {
            it.id shouldBe active.id.toString()
            // Points serialize from the NUMERIC(10,4) column, so the plain string carries the scale.
            it.points shouldBe "30.0000"
            it.band shouldBe "4.0"
            it.matchCode shouldBe match.publicCode
            it.eventCode.shouldBeNull()
        }
    }

    @Test
    fun `active points audit falls back to the event link when an award has no match (#448)`() {
        val owner = newUser(uid = "owner", names = display(name = "Owner"))
        // Simulate an event id via a real event so the code lookup resolves. Use EventRepository through
        // the service's default dependency by inserting an event and referencing its id on the award.
        val event = org.skopeo.repository.EventRepository().create(command = eventCommand(createdBy = owner.id)).toDomain()
        grant(userId = owner.id, points = "15", matchId = null, eventId = event.id)

        val row = service.activePoints(token = token(uid = "owner"), code = owner.publicCode).shouldBeRight().single()
        row.matchCode.shouldBeNull()
        row.eventCode shouldBe event.publicCode
    }

    @Test
    fun `an administrator may read any player's active points audit (#448)`() {
        newAdmin(uid = "admin")
        val owner = newUser(uid = "owner", names = display(name = "Owner"))
        grant(userId = owner.id, points = "30")

        service.activePoints(token = token(uid = "admin"), code = owner.publicCode).shouldBeRight() shouldHaveSize 1
    }

    @Test
    fun `a non-owner non-admin cannot read a player's active points audit (#448)`() {
        val owner = newUser(uid = "owner", names = display(name = "Owner"))
        grant(userId = owner.id, points = "30")
        newUser(uid = "nosy", names = display(name = "Nosy"))

        // Another logged-in user is forbidden.
        service.activePoints(
            token = token(uid = "nosy"),
            code = owner.publicCode,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // An unknown / anonymous caller is forbidden too.
        service.activePoints(
            token = token(uid = "ghost"),
            code = owner.publicCode,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    private fun eventCommand(createdBy: UUID) =
        org.skopeo.model.CreateEventCommand(
            name = "Points Cup",
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(7),
            participantIds = emptyList(),
            createdBy = createdBy,
        )
}
