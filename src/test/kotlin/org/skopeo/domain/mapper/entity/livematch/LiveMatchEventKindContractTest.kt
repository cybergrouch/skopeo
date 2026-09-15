// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.livematch

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.livematch.LiveScoreEventRequest
import org.skopeo.domain.model.LoggedAction
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.TeamSide
import org.skopeo.domain.service.livematch.ScoreEventParser
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.seedLiveMatchFixture
import org.skopeo.testsupport.seedLiveMatchUser
import java.util.UUID
import kotlin.reflect.KClass

/**
 * A live-match event kind is declared in four places, and this is what makes them agree (#989).
 *
 * The four:
 *
 * 1. [ScoreEventParser]'s `SIDED_KINDS` / `BARE_KINDS` — **request → event**
 * 2. [kindOf] / [sideOf] — **event → stored row**
 * 3. `toScoreEvent` behind [toLoggedAction] — **stored row → event**
 * 4. `chk_live_match_events_kind` + `chk_live_match_events_payload` (V55/V56/V59) — **what the database
 *    will accept**
 *
 * Only (2) is defended by the compiler: both are exhaustive `when`s over the sealed hierarchy, so a new
 * subtype breaks the build there and nowhere else. (1) and (3) are `when`s over *strings*, and (4) is not
 * Kotlin at all. Adding `SET_STARTED` in #988 updated the first two, compiled, passed the parser's unit
 * tests, and was still broken twice over: a row the app wrote could not be read back, and — worse — the
 * CHECK rejected the insert, which `append` flattened into "another scorer is writing" after three
 * pointless retries. Nothing pointed at the missing kind.
 *
 * So the enumeration is the point. [leavesOf] walks the sealed hierarchy instead of trusting a list, and
 * each event is driven along the whole chain — request → event → row → event — against a **real
 * database**, because a CHECK constraint cannot be asserted about, only run into. Add a `ScoreEvent`
 * subtype and forget any one of the four and something here fails, naming which one.
 *
 * Deliberately not `LiveMatchMapperTest`: that name appeared in [LiveMatchEventKinds]' KDoc, claiming
 * this coverage already existed, and the file never did. That false reassurance is part of how the
 * omission got through.
 */
class LiveMatchEventKindContractTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() {
            PostgresTestDatabase.start()
        }

        /**
         * Every concrete [ScoreEvent], found by walking the hierarchy rather than by listing it.
         *
         * The recursion is load-bearing: `ScoreEvent.Ending` is itself a sealed interface, so a flat
         * `sealedSubclasses` yields that intermediate node and never reaches `Retired`/`Defaulted`/
         * `MatchAwarded` beneath it. A walk that silently covers nine kinds of twelve while reading as
         * though it covers all of them is the failure mode this whole suite exists to prevent.
         */
        private fun leavesOf(type: KClass<out ScoreEvent>): Set<KClass<out ScoreEvent>> =
            type.sealedSubclasses
                .flatMap { subtype -> if (subtype.isSealed) leavesOf(type = subtype) else listOf(element = subtype) }
                .toSet()
    }

    private val repository = LiveMatchRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    /**
     * One instance of every [ScoreEvent], with [server] naming a provisioned user because `player_id`
     * is a real foreign key.
     *
     * The sides alternate on purpose. Every sided event carrying `TEAM1` would let a branch that
     * hardcoded a side, or read `side` from the wrong column, pass unnoticed.
     */
    private fun samples(server: UUID): List<ScoreEvent> =
        listOf(
            ScoreEvent.PointWon(side = TeamSide.TEAM1),
            ScoreEvent.GameAwarded(side = TeamSide.TEAM2),
            ScoreEvent.SetAwarded(side = TeamSide.TEAM1),
            ScoreEvent.SetStarted,
            ScoreEvent.TiebreakStarted,
            ScoreEvent.ServerAssigned(playerId = server),
            ScoreEvent.Retired(side = TeamSide.TEAM2),
            ScoreEvent.Defaulted(side = TeamSide.TEAM1),
            ScoreEvent.MatchAwarded(side = TeamSide.TEAM2),
            ScoreEvent.MatchStarted,
            ScoreEvent.Paused,
            ScoreEvent.Resumed,
        )

    /**
     * The request a client would POST for [event].
     *
     * Built from the production mappers rather than from a second hand-written table: the parser's job
     * is to be their inverse, and a table maintained here could agree with itself while disagreeing with
     * what the wire actually carries.
     */
    private fun requestFor(event: ScoreEvent): LiveScoreEventRequest =
        LiveScoreEventRequest(
            kind = kindOf(event = event),
            side = sideOf(event = event),
            playerId = (event as? ScoreEvent.ServerAssigned)?.playerId?.toString(),
        )

    @Test
    fun `every ScoreEvent subtype is sampled, so a new one cannot slip past this suite`() {
        // The guard on the guard. Every other test here iterates `samples`, so a subtype missing from it
        // would be checked nowhere while the suite still went green — exactly the shape of reassurance
        // that #989 is about. This is the one assertion that asks the type system what the set really is.
        val sampled = samples(server = UUID.randomUUID())
        val leaves = leavesOf(type = ScoreEvent::class)

        sampled.map { it::class }.toSet() shouldBe leaves
        // Compared by size as well, because a set comparison alone would accept one subtype sampled
        // twice standing in for a neighbour that was dropped.
        sampled shouldHaveSize leaves.size
    }

    @Test
    fun `every ScoreEvent round-trips from request through the database and back`() {
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")
        val events = samples(server = seedLiveMatchUser(uid = "server"))

        events.forEachIndexed { index, event ->
            // Request → event. A kind absent from SIDED_KINDS/BARE_KINDS falls through the parser's
            // `else`, so the failure here is a validation error where an event was expected.
            ScoreEventParser.parse(request = requestFor(event = event)).shouldBeRight() shouldBe event

            // Event → row. The INSERT is the assertion: chk_live_match_events_kind refuses a kind it
            // does not list, chk_live_match_events_payload refuses one whose columns it does not expect,
            // and since #994 `append` no longer flattens either into a lost-race `false` — it throws.
            repository.append(
                matchId = matchId,
                sequence = index + 1L,
                kind = kindOf(event = event),
                side = sideOf(event = event),
                playerId = (event as? ScoreEvent.ServerAssigned)?.playerId,
                recordedBy = umpire,
            ) shouldBe true
        }

        // Row → event. `toScoreEvent` is a `when` over strings with no exhaustiveness check behind it,
        // so a kind it has not been taught reaches its `error(...)` and fails right here.
        repository.log(matchId = matchId).map { it.toLoggedAction() } shouldBe
            events.mapIndexed { index, event -> LoggedAction.Scored(sequence = index + 1L, event = event) }
    }

    @Test
    fun `UNDONE is a log marker rather than a ScoreEvent, and the database takes it too`() {
        // The one kind the sealed walk can never reach, and that is correct rather than a gap: undo has
        // its own endpoint because the server picks the target (the last surviving action) instead of
        // trusting a client to name it, so UNDONE is deliberately absent from RECORDABLE_KINDS. It still
        // has to satisfy the CHECK and still has to read back, which nothing above would notice.
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")
        samples(server = umpire).map { kindOf(event = it) } shouldNotContain LiveMatchEventKinds.UNDONE

        repository.append(
            matchId = matchId,
            sequence = 1,
            kind = LiveMatchEventKinds.POINT_WON,
            side = TeamSide.TEAM1.name,
            recordedBy = umpire,
        ) shouldBe true
        repository.append(
            matchId = matchId,
            sequence = 2,
            kind = LiveMatchEventKinds.UNDONE,
            targetSequence = 1,
            recordedBy = umpire,
        ) shouldBe true

        repository.log(matchId = matchId).map { it.toLoggedAction() } shouldBe
            listOf(
                LoggedAction.Scored(sequence = 1, event = ScoreEvent.PointWon(side = TeamSide.TEAM1)),
                LoggedAction.Undone(sequence = 2, targetSequence = 1),
            )
    }

    @Test
    fun `every declared kind constant is one an event produces, plus UNDONE`() {
        // Read off the object by reflection rather than retyped as a literal list, so the assertion is
        // about the constants themselves. It catches the two ways the object can rot: a constant left
        // behind for a kind nothing writes any more, and a kind written as a bare string literal
        // somewhere instead of being declared here.
        //
        // Java reflection, not `memberProperties`: a `const val` has no getter on the JVM — it is a
        // static final field, and callers inline it — so kotlin-reflect's `KProperty1.get(instance)`
        // rejects the object as a receiver for a static field. Reading the fields directly is what
        // actually works, and filtering by type drops the synthetic `INSTANCE`.
        val declared =
            LiveMatchEventKinds::class.java.declaredFields
                .filter { field -> field.type == String::class.java }
                .mapNotNull { field -> field.get(null) as? String }
                .toSet()

        val produced = samples(server = UUID.randomUUID()).map { kindOf(event = it) }.toSet()
        declared shouldBe produced + LiveMatchEventKinds.UNDONE
    }
}
