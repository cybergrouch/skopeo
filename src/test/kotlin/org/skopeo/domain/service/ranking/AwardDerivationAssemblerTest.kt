// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.ranking

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.AwardStatus
import org.skopeo.domain.model.CreateCircuitCommand
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.EventType
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.PlacementBracket
import org.skopeo.domain.model.PointClass
import org.skopeo.domain.model.PointSourceType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.RankingPointAward
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.repository.CircuitRepository
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.seedFixtureClub
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import org.skopeo.domain.mapper.entity.event.toDomain as toEventDomain

/**
 * Rebuilding an award's derivation (#862).
 *
 * The claim under test is not "it renders something" but **"the explanation adds up to the amount"** — the
 * failure this whole issue guards against is a confident derivation whose numbers contradict the figure
 * beside it. So the assertions check the arithmetic against the shipped schedule, and the honest-gap paths
 * check that an unexplainable award says so instead of guessing.
 */
class AwardDerivationAssemblerTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val matches = MatchRepository()
    private val events = EventRepository()
    private val clubs = ClubRepository()
    private val assembler = AwardDerivationAssembler()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun player(uid: String): User =
        users
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        sex = "Male",
                        capabilities = setOf(element = Capability.PLAYER),
                    ),
            ).toDomain()

    /** A completed singles fixture with the given set scores, from [one]'s perspective first. */
    private fun completedMatch(
        one: UUID,
        two: UUID,
        sets: List<Pair<Int, Int>>,
    ) = run {
        val fixture =
            matches
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.of(2026, 1, 5),
                            team1UserIds = listOf(element = one),
                            team2UserIds = listOf(element = two),
                            team1Name = "T1",
                            team2Name = "T2",
                            createdBy = one,
                        ),
                ).toDomain()
        // team1 wins any set it leads; the fixture's overall winner is irrelevant to per-set scoring.
        val results =
            sets.mapIndexed { i, (a, b) ->
                MatchSetResult(
                    setNumber = i + 1,
                    team1Games = a,
                    team2Games = b,
                    winnerTeamId = if (a > b) fixture.team1.teamId else fixture.team2.teamId,
                )
            }
        matches.addResult(
            matchId = fixture.id,
            sets = results,
            winnerTeamId =
                if (results.count { it.winnerTeamId == fixture.team1.teamId } > results.size / 2) {
                    fixture.team1.teamId
                } else {
                    fixture.team2.teamId
                },
            recordedBy = one,
            completedAt = LocalDateTime.now(),
        )
        matches.findById(matchId = fixture.id).getOrNull()!!.toDomain()
    }

    private fun award(
        userId: UUID,
        matchId: UUID?,
        points: String,
        pointClass: PointClass = PointClass.OPEN_PLAY,
        teamBand: String? = "4.0",
        opponentBand: String? = "4.0",
        version: Int = 1,
    ) = RankingPointAward(
        id = UUID.randomUUID(),
        userId = userId,
        points = BigDecimal(points),
        pointClass = pointClass,
        sourceType = PointSourceType.INTERNAL,
        sourceId = null,
        band = "4.0",
        sex = "Male",
        reason = null,
        validFrom = LocalDateTime.now().minusDays(1),
        validUntil = LocalDateTime.now().plusMonths(3),
        status = AwardStatus.ACTIVE,
        revokesAwardId = null,
        grantedBy = null,
        awardedAt = LocalDateTime.now(),
        matchId = matchId,
        pointsScheduleVersion = version,
        teamBand = teamBand,
        opponentBand = opponentBand,
    )

    @Test
    fun `an equal-band straight-sets win explains every set, and the parts sum to the amount`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        // 6-4 (margin 2) and 6-1 (margin 5), both won, equal bands.
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(6 to 4, 6 to 1))

        // v1 EQUAL schedule: margin 2 pays 8, margin 5 pays 34 (Fibonacci base). Total 42.
        val derivation = assembler.derive(award = award(userId = ana.id, matchId = match.id, points = "42"))

        derivation.recorded.shouldBeTrue()
        derivation.sets.map { it.setNumber to it.margin } shouldBe listOf(1 to 2, 2 to 5)
        derivation.sets.map { it.relation } shouldBe listOf("EQUAL", "EQUAL")
        derivation.sets.map { it.pointsForThisPlayer } shouldBe listOf(8, 34)
        // THE assertion this issue exists for: the explanation adds up to what was paid.
        derivation.sets.sumOf { it.pointsForThisPlayer } shouldBe 42
        derivation.points shouldBe "42"
    }

    @Test
    fun `the loser's side is explained from their own perspective, consolation included`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        // Ana wins 6-5 (margin 1). Ben is the UNDERDOG at a lower band, so he is paid a consolation.
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(element = 6 to 5))

        // Ben's own bands: his 3.5 against Ana's 4.0. He lost as the underdog -> +1 at margin 1 (v1).
        val derivation =
            assembler.derive(
                award = award(userId = ben.id, matchId = match.id, points = "1", teamBand = "3.5", opponentBand = "4.0"),
            )

        derivation.recorded.shouldBeTrue()
        val set = derivation.sets.single()
        // From Ben's side: he did NOT win the set, and the relation is FAVORITE (the higher band won it).
        set.wonSet.shouldBeFalse()
        set.relation shouldBe "FAVORITE"
        set.margin shouldBe 1
        // Both amounts are carried, because "the loser is paid at all" is the least obvious part (#525).
        set.winnerPoints shouldBe 2
        set.loserPoints shouldBe 1
        set.pointsForThisPlayer shouldBe 1
        // And the score reads from Ben's side, not team1's.
        set.score shouldBe "5-6"
    }

    @Test
    fun `an upset is labelled as one, from the winner's side`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(element = 6 to 3))

        // Ana is the LOWER band and won -> UPSET. v1 pays 15 at margin 3.
        val derivation =
            assembler.derive(
                award = award(userId = ana.id, matchId = match.id, points = "15", teamBand = "3.5", opponentBand = "4.0"),
            )

        val set = derivation.sets.single()
        set.relation shouldBe "UPSET"
        set.wonSet.shouldBeTrue()
        set.pointsForThisPlayer shouldBe 15
        set.score shouldBe "6-3"
    }

    @Test
    fun `an award with no recorded bands says so rather than guessing`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(element = 6 to 4))

        // The shape of every award written before #862: a real amount, no inputs.
        val derivation =
            assembler.derive(
                award = award(userId = ana.id, matchId = match.id, points = "8", teamBand = null, opponentBand = null),
            )

        derivation.recorded.shouldBeFalse()
        derivation.sets.shouldBeEmpty()
        derivation.unavailableReason.shouldNotBeNull() shouldContain "predates"
        // The amount is still reported — the award is real, only its explanation is missing.
        derivation.points shouldBe "8"
    }

    @Test
    fun `an award whose schedule version is gone says so rather than using today's rates`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(element = 6 to 4))

        // Version 99 was never seeded. Substituting the CURRENT schedule is the specific failure mode
        // versioning exists to prevent: a derivation that does not add up to the amount beside it.
        val derivation =
            assembler.derive(award = award(userId = ana.id, matchId = match.id, points = "8", version = 99))

        derivation.recorded.shouldBeFalse()
        derivation.unavailableReason.shouldNotBeNull() shouldContain "no longer available"
        derivation.sets.shouldBeEmpty()
    }

    @Test
    fun `an award whose match is gone says so`() {
        val ana = player(uid = "ana")
        val derivation = assembler.derive(award = award(userId = ana.id, matchId = UUID.randomUUID(), points = "8"))

        derivation.recorded.shouldBeFalse()
        derivation.unavailableReason.shouldNotBeNull() shouldContain "fixture"
    }

    @Test
    fun `a manual grant's reason IS its derivation`() {
        val ana = player(uid = "ana")
        val manual =
            award(userId = ana.id, matchId = null, points = "50", pointClass = PointClass.EXTERNAL)
                .copy(reason = "Goodwill adjustment")

        val derivation = assembler.derive(award = manual)

        // Nothing was computed from a schedule, so there is no arithmetic to show — and saying "not
        // recorded" would be wrong, because the reason is the complete explanation.
        derivation.recorded.shouldBeTrue()
        derivation.reason shouldBe "Goodwill adjustment"
        derivation.sets.shouldBeEmpty()
        derivation.placement.shouldBeNull()
    }

    @Test
    fun `a manual grant with no reason still reports as explained`() {
        val ana = player(uid = "ana")
        val derivation =
            assembler.derive(award = award(userId = ana.id, matchId = null, points = "50", pointClass = PointClass.EXTERNAL))

        derivation.recorded.shouldBeTrue()
        derivation.reason shouldBe "Manual grant"
    }

    @Test
    fun `the schedule version is reported so a reader knows which rates applied`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(element = 6 to 4))

        assembler.derive(award = award(userId = ana.id, matchId = match.id, points = "8")).scheduleVersion shouldBe 1
    }

    /**
     * A completed singles placement fixture in a real event under a real club, since a placement
     * derivation reads the bracket off the match and the sanction status off the event's club.
     */
    private fun placementMatch(
        one: User,
        oneUid: String,
        two: User,
        bracket: PlacementBracket,
        sanctioned: Boolean,
        team1Wins: Boolean = true,
    ): Pair<UUID, Match> {
        val club = seedFixtureClub(ownerUids = arrayOf(oneUid))
        clubs.setSanction(id = club.id, sanctioned = sanctioned)
        val circuit = CircuitRepository().create(command = CreateCircuitCommand(name = "NORTH", createdBy = one.id))
        val event =
            events
                .create(
                    command =
                        CreateEventCommand(
                            name = "Spring Open",
                            startDate = LocalDate.of(2026, 1, 1),
                            endDate = LocalDate.of(2026, 1, 7),
                            participantIds = listOf(one.id, two.id),
                            createdBy = one.id,
                            clubId = club.id,
                            circuitId = circuit.id,
                            type = EventType.TOURNAMENT,
                        ),
                ).toEventDomain()
        val fixture =
            matches
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.TOURNAMENT,
                            matchDate = LocalDate.of(2026, 1, 5),
                            team1UserIds = listOf(element = one.id),
                            team2UserIds = listOf(element = two.id),
                            team1Name = "T1",
                            team2Name = "T2",
                            createdBy = one.id,
                            eventId = event.id,
                            isPlacementMatch = true,
                            placementBracket = bracket,
                        ),
                ).toDomain()
        val winner = if (team1Wins) fixture.team1.teamId else fixture.team2.teamId
        matches.addResult(
            matchId = fixture.id,
            sets =
                listOf(
                    element =
                        MatchSetResult(
                            setNumber = 1,
                            team1Games = if (team1Wins) 6 else 4,
                            team2Games = if (team1Wins) 4 else 6,
                            winnerTeamId = winner,
                        ),
                ),
            winnerTeamId = winner,
            recordedBy = one.id,
            completedAt = LocalDateTime.now(),
        )
        return event.id to matches.findById(matchId = fixture.id).getOrNull()!!.toDomain()
    }

    @Test
    fun `a sanctioned Super Finals win is explained as 1st place from the sanctioned column`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val (eventId, match) =
            placementMatch(one = ana, oneUid = "ana", two = ben, bracket = PlacementBracket.CHAMPIONSHIP_FINALS, sanctioned = true)

        val derivation =
            assembler.derive(
                award =
                    award(userId = ana.id, matchId = match.id, points = "1000", pointClass = PointClass.ANNUAL_TOURNAMENT)
                        .copy(eventId = eventId),
            )

        derivation.recorded.shouldBeTrue()
        val placement = derivation.placement.shouldNotBeNull()
        placement.place shouldBe 1
        placement.sanctioned.shouldBeTrue()
        // The sanctioned column pays 1000 for a title (#525) — and the amount beside it must be that.
        placement.scheduleAmount shouldBe 1000
        derivation.sets.shouldBeEmpty()
    }

    @Test
    fun `the beaten finalist is 2nd, and an unsanctioned club pays the lower column`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val (eventId, match) =
            placementMatch(one = ana, oneUid = "ana", two = ben, bracket = PlacementBracket.CHAMPIONSHIP_FINALS, sanctioned = false)

        val derivation =
            assembler.derive(
                award =
                    award(userId = ben.id, matchId = match.id, points = "300", pointClass = PointClass.ANNUAL_TOURNAMENT)
                        .copy(eventId = eventId),
            )

        val placement = derivation.placement.shouldNotBeNull()
        placement.place shouldBe 2
        placement.sanctioned.shouldBeFalse()
        // 300 vs 800 for the identical placing is inexplicable from the number alone; naming the column
        // is the whole reason `sanctioned` is in the payload.
        placement.scheduleAmount shouldBe 300
    }

    @Test
    fun `the plate final pays 3rd and 4th, not 1st and 2nd`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val (eventId, match) =
            placementMatch(
                one = ana,
                oneUid = "ana",
                two = ben,
                bracket = PlacementBracket.PLATE_FINALS,
                sanctioned = true,
                team1Wins = false,
            )

        val winner =
            assembler.derive(
                award =
                    award(userId = ben.id, matchId = match.id, points = "600", pointClass = PointClass.ANNUAL_TOURNAMENT)
                        .copy(eventId = eventId),
            )
        val loser =
            assembler.derive(
                award =
                    award(userId = ana.id, matchId = match.id, points = "500", pointClass = PointClass.ANNUAL_TOURNAMENT)
                        .copy(eventId = eventId),
            )

        // Ben won the plate, so he takes 3rd and Ana 4th — the bracket, not the fixture, decides which
        // pair of places is on offer (#837).
        winner.placement.shouldNotBeNull().place shouldBe 3
        loser.placement.shouldNotBeNull().place shouldBe 4
        winner.placement.shouldNotBeNull().scheduleAmount shouldBe 600
        loser.placement.shouldNotBeNull().scheduleAmount shouldBe 500
    }

    @Test
    fun `a placement award with no event falls back to the unsanctioned column rather than failing`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val (_, match) =
            placementMatch(one = ana, oneUid = "ana", two = ben, bracket = PlacementBracket.CHAMPIONSHIP_FINALS, sanctioned = true)

        // No eventId means the club cannot be consulted. Assuming *unsanctioned* is the conservative
        // choice: it understates rather than inventing a sanction that was never granted.
        val derivation =
            assembler.derive(
                award = award(userId = ana.id, matchId = match.id, points = "400", pointClass = PointClass.ANNUAL_TOURNAMENT),
            )

        derivation.placement.shouldNotBeNull().sanctioned.shouldBeFalse()
        derivation.placement.shouldNotBeNull().scheduleAmount shouldBe 400
    }

    @Test
    fun `a placement award whose schedule version is gone says so`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val (eventId, match) =
            placementMatch(one = ana, oneUid = "ana", two = ben, bracket = PlacementBracket.CHAMPIONSHIP_FINALS, sanctioned = true)

        val derivation =
            assembler.derive(
                award =
                    award(
                        userId = ana.id,
                        matchId = match.id,
                        points = "1000",
                        pointClass = PointClass.ANNUAL_TOURNAMENT,
                        version = 99,
                    ).copy(eventId = eventId),
            )

        derivation.recorded.shouldBeFalse()
        derivation.unavailableReason.shouldNotBeNull() shouldContain "no longer available"
        derivation.placement.shouldBeNull()
    }

    @Test
    fun `a tournament award on a non-placement fixture is explained per set, not as a placing`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        // A round-robin fixture inside a tournament (#836): same point class, different derivation.
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(element = 6 to 4))

        val derivation =
            assembler.derive(
                award = award(userId = ana.id, matchId = match.id, points = "8", pointClass = PointClass.ANNUAL_TOURNAMENT),
            )

        derivation.recorded.shouldBeTrue()
        derivation.placement.shouldBeNull()
        derivation.sets.single().pointsForThisPlayer shouldBe 8
    }

    @Test
    fun `a tournament award whose match is gone says so`() {
        val ana = player(uid = "ana")
        val derivation =
            assembler.derive(
                award =
                    award(userId = ana.id, matchId = UUID.randomUUID(), points = "1000", pointClass = PointClass.ANNUAL_TOURNAMENT),
            )

        // With no match there is nothing to say whether this was a placing or a per-set payout, so it
        // falls to the per-set path and reports the missing fixture.
        derivation.recorded.shouldBeFalse()
        derivation.unavailableReason.shouldNotBeNull() shouldContain "fixture"
    }

    @Test
    fun `an award whose inputs were backfilled explains itself, and the parts sum to what was paid (#892)`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(element = 6 to 3))

        // The shape a V49-backfilled row has: bands recovered from the sibling award, schedule version 1.
        // v1 pays 13 at margin 3 between equal bands, which is what was actually paid.
        val derivation = assembler.derive(award = award(userId = ana.id, matchId = match.id, points = "13"))

        derivation.recorded.shouldBeTrue()
        derivation.sets.single().pointsForThisPlayer shouldBe 13
        // The claim the guard now enforces rather than assumes.
        derivation.sets.sumOf { it.pointsForThisPlayer } shouldBe 13
    }

    @Test
    fun `an award whose arithmetic does not reproduce the amount paid is reported, never shown (#892)`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(element = 6 to 3))

        // Inputs present and a live match, but the paid figure disagrees with the schedule — the shape a
        // backfilled award takes when the rates it was really paid under were overwritten before V47.
        // Showing the arithmetic here would contradict the number beside it, which is the one outcome
        // #862 exists to prevent, so it must be reported instead.
        val derivation = assembler.derive(award = award(userId = ana.id, matchId = match.id, points = "999"))

        derivation.recorded.shouldBeFalse()
        derivation.sets.shouldBeEmpty()
        derivation.unavailableReason.shouldNotBeNull() shouldContain "no longer reproduce the amount paid"
        // The amount is still reported: the award is real, only its explanation is untrustworthy.
        derivation.points shouldBe "999"
    }

    @Test
    fun `a calibration-clamped zero still explains itself, since the clamp is documented (#881, #892)`() {
        val ana = player(uid = "ana")
        val ben = player(uid = "ben")
        // Ana is the higher band and loses, so v1 computes a NEGATIVE payout for her (-2 at UPSET_LOSS).
        val match = completedMatch(one = ana.id, two = ben.id, sets = listOf(element = 4 to 6))

        // Paid zero because #881 floors a negative amount when the match involved calibration. The guard
        // must accept this: paid 0 against a negative computation is the clamp, which is real, documented,
        // and explained on the row itself — refusing the derivation would hide an explanation that exists.
        val derivation =
            assembler.derive(
                award =
                    award(userId = ana.id, matchId = match.id, points = "0", teamBand = "4.0", opponentBand = "3.5")
                        .copy(reason = "Awarded on finalize of event X — clamped to zero because a player was in calibration"),
            )

        derivation.recorded.shouldBeTrue()
        derivation.sets.sumOf { it.pointsForThisPlayer } shouldBe -2
        derivation.reason.shouldNotBeNull() shouldContain "clamped"
    }
}
