// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.match

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.match.MatchResultRequest
import org.skopeo.common.dto.match.SetScoreRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchCompletionReason
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.finalizeFixtureEvent
import org.skopeo.testsupport.fixtureEventId
import java.time.LocalDate
import java.util.UUID

/**
 * How a match ended, and what follows from it (#911 §10).
 *
 * The two endings deliberately differ, which is the thing most likely to be "simplified" later by
 * someone assuming symmetry:
 *
 * | | on the record | rated? |
 * |---|---|---|
 * | RETIRED | opponent wins | **yes**, on the real score — there was tennis |
 * | DEFAULTED | opponent wins | **no** — a no-show has no scoreline to compute dominance from |
 */
class MatchCompletionReasonTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val matches = MatchRepository()
    private val service = MatchService()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun user(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): UUID =
        UserRepository()
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            ).toDomain()
            .id

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    private data class Fixture(val matchId: UUID, val team1: String, val team2: String)

    private fun fixture(): Fixture {
        user(uid = "host", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val home = user(uid = "home")
        val away = user(uid = "away")
        val aggregate =
            matches.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = home),
                        team2UserIds = listOf(element = away),
                        team1Name = "home",
                        team2Name = "away",
                        createdBy = home,
                        eventId = fixtureEventId(home, away),
                    ),
            )
        val match = aggregate.toDomain()
        return Fixture(matchId = match.id, team1 = match.team1.teamId.toString(), team2 = match.team2.teamId.toString())
    }

    private fun retirementAt(
        fixture: Fixture,
        reason: String,
    ) = MatchResultRequest(
        // 1-3: below the games floor, which is why #931 had to lift it for a designated winner.
        sets = listOf(element = SetScoreRequest(team1Games = 1, team2Games = 3)),
        winnerTeamId = fixture.team2,
        completionReason = reason,
    )

    @Test
    fun `an ordinary result defaults to COMPLETED`() {
        val f = fixture()
        service
            .uploadResult(
                token = token(uid = "host"),
                matchId = f.matchId,
                request = MatchResultRequest(sets = listOf(element = SetScoreRequest(team1Games = 6, team2Games = 4))),
            ).shouldBeRight()

        matches.findById(matchId = f.matchId).shouldBeRight().toDomain().completionReason shouldBe
            MatchCompletionReason.COMPLETED
    }

    @Test
    fun `a retirement is recorded, and the opponent takes the match`() {
        val f = fixture()
        service
            .uploadResult(token = token(uid = "host"), matchId = f.matchId, request = retirementAt(fixture = f, reason = "RETIRED"))
            .shouldBeRight()

        val match = matches.findById(matchId = f.matchId).shouldBeRight().toDomain()
        match.completionReason shouldBe MatchCompletionReason.RETIRED
        match.winnerTeamId.toString() shouldBe f.team2
    }

    @Test
    fun `the conceding side is derived, never stored`() {
        // The reason #911 §10's "record which player retired" became one column rather than two: the
        // retiring side is whichever is not the winner, so the two can never disagree.
        val f = fixture()
        service
            .uploadResult(token = token(uid = "host"), matchId = f.matchId, request = retirementAt(fixture = f, reason = "RETIRED"))
            .shouldBeRight()

        val match = matches.findById(matchId = f.matchId).shouldBeRight().toDomain()
        val conceded = if (match.winnerTeamId == match.team1.teamId) match.team2.teamId else match.team1.teamId
        conceded.toString() shouldBe f.team1
    }

    @Test
    fun `a retirement still queues for rating, because there was tennis to rate`() {
        val f = fixture()
        service
            .uploadResult(token = token(uid = "host"), matchId = f.matchId, request = retirementAt(fixture = f, reason = "RETIRED"))
            .shouldBeRight()
        finalizeFixtureEvent()

        matches.listPendingCalculation().map { it.toDomain().id } shouldBe listOf(element = f.matchId)
    }

    @Test
    fun `a default never queues for rating, because there is no performance to rate`() {
        val f = fixture()
        service
            .uploadResult(token = token(uid = "host"), matchId = f.matchId, request = retirementAt(fixture = f, reason = "DEFAULTED"))
            .shouldBeRight()
        finalizeFixtureEvent()

        // Excluded from the QUEUE rather than skipped during processing: it is not pending calculation,
        // because it will never be calculated, and listing it as pending would be a standing lie.
        matches.listPendingCalculation().shouldHaveSize(size = 0)
    }

    @Test
    fun `a default is still recorded as a win on the record, so points follow it`() {
        // §10: points and awards follow the RECORD, not the rating. The two answers differ on purpose.
        val f = fixture()
        service
            .uploadResult(token = token(uid = "host"), matchId = f.matchId, request = retirementAt(fixture = f, reason = "DEFAULTED"))
            .shouldBeRight()

        val match = matches.findById(matchId = f.matchId).shouldBeRight().toDomain()
        match.completionReason shouldBe MatchCompletionReason.DEFAULTED
        match.winnerTeamId.toString() shouldBe f.team2
    }

    @Test
    fun `an unknown completion reason is a validation error naming the permitted values`() {
        val f = fixture()
        val error =
            service
                .uploadResult(
                    token = token(uid = "host"),
                    matchId = f.matchId,
                    request = retirementAt(fixture = f, reason = "WALKOVER"),
                ).shouldBeLeft()
                .shouldBeInstanceOf<ServiceError.Validation>()
        error.message.contains(other = "RETIRED") shouldBe true
    }

    @Test
    fun `an abnormal ending without a designated winner is rejected at the boundary`() {
        // One statement, not two rules: a match that did not play out has no scoreline to derive a
        // winner from, so it has to say who took it.
        shouldThrow<IllegalArgumentException> {
            MatchResultRequest(
                sets = listOf(element = SetScoreRequest(team1Games = 6, team2Games = 4)),
                completionReason = "RETIRED",
            )
        }
        shouldNotThrowAny {
            MatchResultRequest(
                sets = listOf(element = SetScoreRequest(team1Games = 6, team2Games = 4)),
                completionReason = "COMPLETED",
            )
        }
    }

    @Test
    fun `re-recording a result restates the ending rather than leaving the old one`() {
        // A correction from "retired" back to a played-out result must drop the (ret), or the scoreline
        // keeps a qualifier the score no longer supports.
        val f = fixture()
        service
            .uploadResult(token = token(uid = "host"), matchId = f.matchId, request = retirementAt(fixture = f, reason = "RETIRED"))
            .shouldBeRight()

        service
            .uploadResult(
                token = token(uid = "host"),
                matchId = f.matchId,
                request = MatchResultRequest(sets = listOf(element = SetScoreRequest(team1Games = 6, team2Games = 4))),
            ).shouldBeRight()

        matches.findById(matchId = f.matchId).shouldBeRight().toDomain().completionReason shouldBe
            MatchCompletionReason.COMPLETED
    }

    @Test
    fun `isRatable answers the rating question in one place`() {
        MatchCompletionReason.COMPLETED.isRatable shouldBe true
        MatchCompletionReason.RETIRED.isRatable shouldBe true
        MatchCompletionReason.DEFAULTED.isRatable shouldBe false
    }
}
