// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.MatchRatingWrite
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.SnapshotSource
import org.skopeo.model.SnapshotStatus
import org.skopeo.model.StandingsBand
import org.skopeo.model.StandingsEntryWrite
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.StandingsSnapshotRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class StandingsServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val snapshots = StandingsSnapshotRepository()
    private val appSettings = AppSettingsRepository()
    private val service = StandingsService()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    /** Set the standings_source app-setting (the value the served source is read from). */
    private fun setSource(value: String) {
        val admin =
            provision(uid = "source-admin", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        appSettings.upsert(key = "standings_source", value = value, updatedBy = admin.id)
    }

    /** Persist a single-entry PUBLISHED snapshot of [source] holding [user] in [band]/Male (for source tests). */
    private fun publishSnapshot(
        source: SnapshotSource,
        user: User,
        band: StandingsBand,
        rating: String,
    ) {
        snapshots.create(
            computedAt = LocalDateTime.now(),
            asOf = LocalDate.now(),
            status = SnapshotStatus.PUBLISHED,
            entries =
                listOf(
                    element =
                        StandingsEntryWrite(
                            band = band,
                            sex = "Male",
                            rank = 1,
                            userId = user.id,
                            orderingValue = BigDecimal(rating),
                            tiebreakRating = BigDecimal(rating),
                            achievedAt = null,
                        ),
                ),
            source = source,
        )
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private val viewerToken = token(uid = "viewer")

    private fun provision(
        uid: String,
        sex: String? = null,
        capabilities: Set<Capability> = setOf(element = Capability.PLAYER),
        dateOfBirth: LocalDate? = LocalDate.of(2000, 1, 1),
        withName: Boolean = true,
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = if (withName) listOf(element = UserName(type = NameType.DISPLAY, value = uid)) else emptyList(),
                    dateOfBirth = dateOfBirth,
                    sex = sex,
                    capabilities = capabilities,
                ),
        )

    // Confidence is computed (#343): min(1, matches/5) when match-derived and fresh; 0 for a bare override.
    private fun rate(
        user: User,
        value: String,
        matches: Int = 0,
    ) {
        val level = value.toBigDecimal().let { "${it.toInt()}.${if (it.toDouble() % 1.0 >= 0.5) 5 else 0}" }
        ratings.setRating(userId = user.id, rating = BigDecimal(value), level = level)
        repeat(times = matches) {
            ratings.applyMatchRating(
                write =
                    MatchRatingWrite(
                        userId = user.id,
                        newRating = BigDecimal(value),
                        newLevel = level,
                        matchDate = LocalDate.now(),
                        ratedAt = LocalDateTime.now(),
                        bandJumped = false,
                    ),
            )
        }
    }

    private fun page(
        band: StandingsBand?,
        sex: String?,
        limit: Int? = null,
        offset: Int? = null,
        token: VerifiedFirebaseToken = viewerToken,
    ) = service.page(token = token, band = band, sex = sex, limit = limit, offset = offset)

    @Test
    fun `page with no rated players returns an empty view with no groups (#220)`() {
        val view = page(band = null, sex = null)
        view.band.shouldBeNull()
        view.entries shouldHaveSize 0
        view.total shouldBe 0
        view.groups shouldHaveSize 0
    }

    @Test
    fun `page with an unrated user still returns an empty view with no groups (#220)`() {
        // An active but unrated user contributes no rating, so the live leaderboard carries no groups and
        // a bare page (no band requested) resolves to no group — the empty (chosen == null) arm.
        provision(uid = "unrated", sex = "Male")

        val view = page(band = null, sex = null)
        view.band.shouldBeNull()
        view.entries shouldHaveSize 0
        view.total shouldBe 0
        view.groups shouldHaveSize 0
    }

    @Test
    fun `the RATING source ranks each (band, sex) group live from 1 and page serves it (#146)`() {
        // No snapshot at all — RATING serves live off the current ratings.
        val m1 = provision(uid = "m1", sex = "Male").also { rate(user = it, value = "4.3") }
        val m2 = provision(uid = "m2", sex = "Male").also { rate(user = it, value = "4.1") }
        provision(uid = "f1", sex = "Female").also { rate(user = it, value = "4.4") }

        val men = page(band = StandingsBand.FROM_4_0, sex = "Male")
        men.band shouldBe StandingsBand.FROM_4_0
        men.sex shouldBe "Male"
        men.source shouldBe SnapshotSource.RATING
        men.total shouldBe 2
        men.entries.map { it.userId } shouldContainExactly listOf(m1.id, m2.id) // 4.3 ahead of 4.1
        men.entries.map { it.rank } shouldContainExactly listOf(1, 2)

        // The selectors list both the Men and Women groups of the 4.0 band.
        men.groups.map { it.band to it.sex } shouldContainExactly
            listOf(StandingsBand.FROM_4_0 to "Male", StandingsBand.FROM_4_0 to "Female")
    }

    @Test
    fun `page defaults to the strongest available group when no band is requested (#220)`() {
        provision(uid = "lo", sex = "Male").also { rate(user = it, value = "4.0") } // FROM_4_0
        val hi = provision(uid = "hi", sex = "Male").also { rate(user = it, value = "6.5") } // SIX_PLUS

        val view = page(band = null, sex = null)
        view.band shouldBe StandingsBand.SIX_PLUS
        view.entries.single().userId shouldBe hi.id
    }

    @Test
    fun `page paginates within a group live, honoring limit and offset (#220)`() {
        val ranked = (1..3).map { provision(uid = "p$it", sex = "Male").also { u -> rate(user = u, value = "4.${4 - it}") } }

        val first = page(band = StandingsBand.FROM_4_0, sex = "Male", limit = 2, offset = 0)
        first.total shouldBe 3
        first.entries.map { it.userId } shouldContainExactly listOf(ranked[0].id, ranked[1].id)

        val second = page(band = StandingsBand.FROM_4_0, sex = "Male", limit = 2, offset = 2)
        second.entries.map { it.userId } shouldContainExactly listOf(element = ranked[2].id)
    }

    @Test
    fun `a rating change is reflected live on the next page read (#146)`() {
        val player = provision(uid = "p", sex = "Male").also { rate(user = it, value = "4.2") }
        page(band = StandingsBand.FROM_4_0, sex = "Male").entries.single().userId shouldBe player.id

        // A promotion into a higher band; the live read moves the player there with no rebuild.
        rate(user = player, value = "4.7")
        page(band = StandingsBand.FROM_4_0, sex = "Male").total shouldBe 0
        page(band = StandingsBand.FROM_4_5, sex = "Male").entries.single().userId shouldBe player.id
    }

    @Test
    fun `precise rating is revealed to a rater and hidden from a plain player (#186)`() {
        provision(uid = "rater", capabilities = setOf(Capability.PLAYER, Capability.RATER))
        val player = provision(uid = "p", sex = "Male").also { rate(user = it, value = "4.2") }

        page(band = StandingsBand.FROM_4_0, sex = "Male", token = token(uid = "rater"))
            .entries.single { it.userId == player.id }.currentRating shouldBe "4.200000"
        page(band = StandingsBand.FROM_4_0, sex = "Male", token = token(uid = "p"))
            .entries.single { it.userId == player.id }.currentRating.shouldBeNull()
    }

    @Test
    fun `disabled players are excluded from the live leaderboard (#220)`() {
        val active = provision(uid = "active", sex = "Male").also { rate(user = it, value = "4.0") }
        val disabled = provision(uid = "disabled", sex = "Male").also { rate(user = it, value = "4.0") }
        users.deactivate(id = disabled.id)

        page(band = StandingsBand.FROM_4_0, sex = "Male").entries.let {
            it shouldHaveSize 1
            it.single().userId shouldBe active.id
        }
    }

    @Test
    fun `locateMe returns the caller's live location and containing page offset (#220)`() {
        // 30 players all strictly above the caller (4.10–4.19, all in the 4.0 band), so the caller is
        // unambiguously last regardless of tie-break.
        (1..30).forEach { provision(uid = "p$it", sex = "Male").also { u -> rate(user = u, value = "4.1${it % 10}") } }
        // The caller is deliberately weakest so they land at the end (page 2 at 25/page).
        provision(uid = "me", sex = "Male").also { rate(user = it, value = "4.001") }

        val located = service.locateMe(token = token(uid = "me"), limit = 25).shouldNotBeNull()
        located.location.band shouldBe StandingsBand.FROM_4_0
        located.location.sex shouldBe "Male"
        // rank is 1-based; with 31 players the weakest is rank 31 → offset 25 (page 2).
        located.location.rank shouldBe 31
        located.offset shouldBe 25
        located.limit shouldBe 25
    }

    @Test
    fun `locateMe is null when the caller is unrated or unknown (#220)`() {
        provision(uid = "rated", sex = "Male").also { rate(user = it, value = "4.0") }
        val unrated = provision(uid = "unrated", sex = "Male")

        service.locateMe(token = token(uid = "unrated"), limit = 25).shouldBeNull()
        service.locateMe(token = token(uid = "ghost"), limit = 25).shouldBeNull()
        unrated.isActive shouldBe true // sanity: the user exists but simply isn't in the standings
    }

    @Test
    fun `locateMe is null when no one is rated yet (#220)`() {
        // The caller has a profile but nobody is rated, so the live leaderboard is empty.
        provision(uid = "me", sex = "Male")
        service.locateMe(token = token(uid = "me"), limit = 25).shouldBeNull()
    }

    @Test
    fun `page for a band, sex group with no players returns an empty view still carrying groups (#220)`() {
        // Only a Male 4.0 group exists; requesting Female for that band yields an empty page, not a fallback.
        provision(uid = "m", sex = "Male").also { rate(user = it, value = "4.0") }

        val view = page(band = StandingsBand.FROM_4_0, sex = "Female")
        view.entries shouldHaveSize 0
        view.total shouldBe 0
        // The selectors still advertise the group that does exist.
        view.groups.map { it.band to it.sex } shouldContainExactly listOf(element = StandingsBand.FROM_4_0 to "Male")
    }

    @Test
    fun `page fills age only for entries whose user has a date of birth (#220)`() {
        val withDob =
            provision(uid = "withdob", sex = "Male", dateOfBirth = LocalDate.of(1990, 6, 1))
                .also { rate(user = it, value = "4.3") }
        val noDob =
            provision(uid = "nodob", sex = "Male", dateOfBirth = null)
                .also { rate(user = it, value = "4.1") }

        val entries = page(band = StandingsBand.FROM_4_0, sex = "Male").entries.associateBy { it.userId }
        entries.getValue(key = withDob.id).age.shouldNotBeNull()
        entries.getValue(key = noDob.id).age.shouldBeNull()
    }

    @Test
    fun `tie-break falls back to public code when a tied player has no display name (#220)`() {
        // Two players tie on rating, confidence and matches, so the tie-break falls to the name comparison;
        // one has no display name and must sort by its public code rather than blow up.
        val named = provision(uid = "named", sex = "Male").also { rate(user = it, value = "4.2") }
        val unnamed = provision(uid = "unnamed", sex = "Male", withName = false).also { rate(user = it, value = "4.2") }

        val entries = page(band = StandingsBand.FROM_4_0, sex = "Male").entries
        entries shouldHaveSize 2
        entries.map { it.userId }.toSet() shouldBe setOf(named.id, unnamed.id)
        entries.single { it.userId == unnamed.id }.displayName.shouldBeNull()
    }

    @Test
    fun `with standings_source absent the RATING source is served live by default (#146)`() {
        // A POINTS snapshot exists but standings_source is unset (default RATING), so the live rating
        // leaderboard serves — NOT the POINTS snapshot's group.
        val u = provision(uid = "u", sex = "Male").also { rate(user = it, value = "4.2") } // FROM_4_0
        publishSnapshot(source = SnapshotSource.POINTS, user = u, band = StandingsBand.SIX_PLUS, rating = "6.5")

        val view = page(band = null, sex = null)
        view.band shouldBe StandingsBand.FROM_4_0 // the live rating band, not the POINTS snapshot's SIX_PLUS
        view.source shouldBe SnapshotSource.RATING
    }

    @Test
    fun `when standings_source is POINTS the POINTS snapshot is served (#146)`() {
        // The live rating would place the player in 4.0; the POINTS snapshot places them in 6.0+ — with
        // POINTS configured the snapshot wins.
        val u = provision(uid = "u", sex = "Male").also { rate(user = it, value = "4.2") }
        publishSnapshot(source = SnapshotSource.POINTS, user = u, band = StandingsBand.SIX_PLUS, rating = "6.5")
        setSource(value = "POINTS")

        val view = page(band = null, sex = null)
        view.band shouldBe StandingsBand.SIX_PLUS // the POINTS snapshot's group
        view.entries.single().userId shouldBe u.id
        view.source shouldBe SnapshotSource.POINTS
    }

    @Test
    fun `POINTS with no published snapshot returns an empty POINTS view, not the rating leaderboard (#428)`() {
        // Configured POINTS but no POINTS snapshot exists → an explicit empty view tagged source=POINTS.
        // It must NOT silently fall back to the live rating leaderboard (which would show this rated player).
        val u = provision(uid = "u", sex = "Male").also { rate(user = it, value = "4.2") }
        publishSnapshot(
            source = SnapshotSource.RATING,
            user = u,
            band = StandingsBand.SIX_PLUS,
            rating = "6.5",
        ) // dead RATING snapshot, ignored — only POINTS snapshots count under POINTS
        setSource(value = "POINTS")

        val view = page(band = null, sex = null)
        view.source shouldBe SnapshotSource.POINTS
        view.band.shouldBeNull() // no rating fallback: no live 4.0 band leaks through
        view.entries shouldHaveSize 0
        view.total shouldBe 0
        view.groups shouldHaveSize 0
        view.allBands shouldContainExactly StandingsBand.entries.reversed()
    }

    @Test
    fun `POINTS with a published but empty snapshot returns an empty view carrying every band (#146)`() {
        // A committed points run with no counting awards publishes a header with no entries; the POINTS
        // path then resolves to no group (the chosen == null arm) and returns an empty view.
        provision(uid = "admin", capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        snapshots.create(
            computedAt = LocalDateTime.now(),
            asOf = LocalDate.now(),
            status = SnapshotStatus.PUBLISHED,
            entries = emptyList(),
            source = SnapshotSource.POINTS,
        )
        setSource(value = "POINTS")

        val view = page(band = null, sex = null)
        view.band.shouldBeNull()
        view.entries shouldHaveSize 0
        view.total shouldBe 0
        view.groups shouldHaveSize 0
        view.allBands shouldContainExactly StandingsBand.entries.reversed()
        view.source shouldBe SnapshotSource.POINTS
    }

    @Test
    fun `locateMe is null under POINTS when no POINTS snapshot exists, no rating fallback (#428)`() {
        // POINTS configured but no POINTS snapshot: locate must be null, not the caller's live rating row.
        provision(uid = "me", sex = "Male").also { rate(user = it, value = "4.2") }
        setSource(value = "POINTS")

        service.locateMe(token = token(uid = "me"), limit = 25).shouldBeNull()
    }

    @Test
    fun `locateMe locates in the POINTS snapshot when POINTS is configured (#146)`() {
        val u = provision(uid = "u", sex = "Male").also { rate(user = it, value = "4.2") }
        publishSnapshot(source = SnapshotSource.POINTS, user = u, band = StandingsBand.SIX_PLUS, rating = "6.5")
        setSource(value = "POINTS")

        val located = service.locateMe(token = token(uid = "u"), limit = 25).shouldNotBeNull()
        located.location.band shouldBe StandingsBand.SIX_PLUS // the POINTS snapshot's group, not the live 4.0
        located.location.rank shouldBe 1
    }

    @Test
    fun `under POINTS a page exposes the points metric to every viewer and never the rating (#457, #186)`() {
        // A non-privileged viewer (plain player) still sees the public points total, but no rating leaks.
        provision(uid = "viewer", capabilities = setOf(element = Capability.PLAYER))
        val top = provision(uid = "top", sex = "Male").also { rate(user = it, value = "4.3") }
        val low = provision(uid = "low", sex = "Male").also { rate(user = it, value = "4.1") }
        snapshots.create(
            computedAt = LocalDateTime.now(),
            asOf = LocalDate.now(),
            status = SnapshotStatus.PUBLISHED,
            entries =
                listOf(
                    StandingsEntryWrite(
                        band = StandingsBand.FROM_4_0,
                        sex = "Male",
                        rank = 1,
                        userId = top.id,
                        orderingValue = BigDecimal("240"),
                        tiebreakRating = BigDecimal("4.3"),
                        achievedAt = null,
                    ),
                    StandingsEntryWrite(
                        band = StandingsBand.FROM_4_0,
                        sex = "Male",
                        rank = 2,
                        userId = low.id,
                        orderingValue = BigDecimal("180"),
                        tiebreakRating = BigDecimal("4.1"),
                        achievedAt = null,
                    ),
                ),
            source = SnapshotSource.POINTS,
        )
        setSource(value = "POINTS")

        val entries =
            page(band = StandingsBand.FROM_4_0, sex = "Male", token = token(uid = "viewer")).entries.associateBy { it.userId }
        // Points are the served metric and public (persisted at the snapshot's NUMERIC scale); the precise
        // rating is never on a POINTS row.
        entries.getValue(key = top.id).points shouldBe "240.0000"
        entries.getValue(key = top.id).currentRating.shouldBeNull()
        entries.getValue(key = low.id).points shouldBe "180.0000"
        entries.getValue(key = low.id).currentRating.shouldBeNull()
    }

    @Test
    fun `under POINTS the rating is not exposed even to a rater on a page row (#457, #186)`() {
        provision(uid = "rater", capabilities = setOf(Capability.PLAYER, Capability.RATER))
        val player = provision(uid = "p", sex = "Male").also { rate(user = it, value = "4.3") }
        snapshots.create(
            computedAt = LocalDateTime.now(),
            asOf = LocalDate.now(),
            status = SnapshotStatus.PUBLISHED,
            entries =
                listOf(
                    element =
                        StandingsEntryWrite(
                            band = StandingsBand.FROM_4_0,
                            sex = "Male",
                            rank = 1,
                            userId = player.id,
                            orderingValue = BigDecimal("240"),
                            tiebreakRating = BigDecimal("4.3"),
                            achievedAt = null,
                        ),
                ),
            source = SnapshotSource.POINTS,
        )
        setSource(value = "POINTS")

        val row = page(band = StandingsBand.FROM_4_0, sex = "Male", token = token(uid = "rater")).entries.single()
        row.points shouldBe "240.0000"
        // Under POINTS the metric is points; the rating is not the served value, so it is never shown.
        row.currentRating.shouldBeNull()
    }

    @Test
    fun `the page response lists every NTRP band strongest-first even when groups are a subset (#113)`() {
        // Only one Male 4.0 group has data, yet the full band list is advertised for the dropdown.
        provision(uid = "m", sex = "Male").also { rate(user = it, value = "4.0") }

        val view = page(band = StandingsBand.FROM_4_0, sex = "Male")
        view.allBands shouldContainExactly StandingsBand.entries.reversed()
        view.allBands.first() shouldBe StandingsBand.SIX_PLUS // strongest first
        view.groups shouldHaveSize 1 // only the populated group carries data
    }

    @Test
    fun `an empty leaderboard still advertises every NTRP band (#113)`() {
        val view = page(band = null, sex = null)
        view.groups shouldHaveSize 0
        view.allBands shouldContainExactly StandingsBand.entries.reversed()
    }

    @Test
    fun `locatePlayer returns the live band, sex, rank and the current rating as the RATING metric (#457)`() {
        val top = provision(uid = "top", sex = "Male").also { rate(user = it, value = "4.3") }
        val lower = provision(uid = "lower", sex = "Male").also { rate(user = it, value = "4.1") }

        val topStanding = service.locatePlayer(userId = top.id).shouldNotBeNull()
        topStanding.band shouldBe StandingsBand.FROM_4_0
        topStanding.sex shouldBe "Male"
        topStanding.rank shouldBe 1
        // Under RATING the metric is the current rating; the points field is not populated (#457).
        topStanding.rating shouldBe BigDecimal("4.300000")
        topStanding.points.shouldBeNull()
        topStanding.source shouldBe SnapshotSource.RATING

        service.locatePlayer(userId = lower.id).shouldNotBeNull().rank shouldBe 2
    }

    @Test
    fun `locatePlayer is null for an unrated player under the RATING source (#448)`() {
        val unrated = provision(uid = "unrated", sex = "Male")
        service.locatePlayer(userId = unrated.id).shouldBeNull()
    }

    @Test
    fun `locatePlayer reads the POINTS snapshot rank and points under the POINTS source (#448)`() {
        val player = provision(uid = "p", sex = "Male").also { rate(user = it, value = "4.2") }
        snapshots.create(
            computedAt = LocalDateTime.now(),
            asOf = LocalDate.now(),
            status = SnapshotStatus.PUBLISHED,
            entries =
                listOf(
                    element =
                        StandingsEntryWrite(
                            band = StandingsBand.FROM_4_0,
                            sex = "Male",
                            rank = 3,
                            userId = player.id,
                            orderingValue = BigDecimal("240"),
                            tiebreakRating = BigDecimal("4.2"),
                            achievedAt = null,
                        ),
                ),
            source = SnapshotSource.POINTS,
        )
        setSource(value = "POINTS")

        val standing = service.locatePlayer(userId = player.id).shouldNotBeNull()
        standing.band shouldBe StandingsBand.FROM_4_0
        standing.rank shouldBe 3
        // Under POINTS the metric is the snapshot points; the rating field is not populated (#457).
        standing.points shouldBe BigDecimal("240.0000")
        standing.rating.shouldBeNull()
        standing.source shouldBe SnapshotSource.POINTS
    }

    @Test
    fun `locatePlayer is null under POINTS when the player is absent from the snapshot (#448)`() {
        val absent = provision(uid = "absent", sex = "Male").also { rate(user = it, value = "4.2") }
        setSource(value = "POINTS")
        // No POINTS snapshot exists at all → null (never falls back to the live rating leaderboard, #428).
        service.locatePlayer(userId = absent.id).shouldBeNull()
    }
}
