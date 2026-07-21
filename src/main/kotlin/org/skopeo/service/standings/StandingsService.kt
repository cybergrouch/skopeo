// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import org.skopeo.model.Capability
import org.skopeo.model.PlayerStanding
import org.skopeo.model.SnapshotSource
import org.skopeo.model.StandingEntry
import org.skopeo.model.StandingsBand
import org.skopeo.model.StandingsLocation
import org.skopeo.model.User
import org.skopeo.model.UserRating
import org.skopeo.model.ageInYears
import org.skopeo.model.displayName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.StandingsSnapshotRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.settings.SettingsService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDate
import java.util.UUID

private const val DEFAULT_PAGE_SIZE = 25
private const val MAX_PAGE_SIZE = 100

/**
 * The per-band "Ranking Race" standings (issue #113). The two sources served differ by design (#146):
 * the **RATING** source is computed **live** from each user's current rating (there is no rating
 * snapshot — snapshots are a POINTS-only concept), while the **POINTS** source is served from the
 * latest PUBLISHED `source=POINTS` snapshot produced by [StandingsCalculationService].
 *
 * [page]/[locateMe] resolve the source from the `standings_source` app-setting (RATING by default). For
 * RATING they rank the active rated players in memory (rating → confidence → matches → name) and page
 * one (band, sex) group at a time; for POINTS they page the latest published POINTS snapshot. POINTS never
 * silently falls back to the live RATING calculation (#428): when no POINTS snapshot exists the page is an
 * explicit **empty** view still tagged `source=POINTS`, so the UI shows a distinct "run a points
 * calculation" state rather than the rating leaderboard. Order is what's exposed (#64/#114); the precise
 * rating is revealed only to RATER/ADMINISTRATOR viewers (#186).
 */
class StandingsService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val snapshots: StandingsSnapshotRepository = StandingsSnapshotRepository(),
    private val settings: SettingsService = SettingsService(),
) {
    /** A page of one (band, sex) group plus its selectors: the group's total, the groups on offer, all bands. */
    data class StandingsView(
        val band: StandingsBand?,
        val sex: String?,
        val entries: List<StandingEntry>,
        val total: Int,
        val limit: Int,
        val offset: Int,
        val groups: List<GroupRef>,
        // Every NTRP band (strongest-first), so the UI dropdown lists empty bands too (#113); picking an
        // empty band yields an empty page still queryable by sex. Independent of which groups have data.
        val allBands: List<StandingsBand>,
        val revealRates: Boolean,
        // The effective serving source (#428): RATING = live calculation, POINTS = the POINTS snapshot (or an
        // explicit empty view when none exists). Lets the UI distinguish "POINTS, no data yet" from ratings.
        val source: SnapshotSource,
    )

    /** A selectable (band, sex) group present in the leaderboard — powers the UI band dropdown + sex toggle. */
    data class GroupRef(
        val band: StandingsBand,
        val sex: String?,
    )

    /** Jump-to-me (#220): the caller's (band, sex, rank) plus the page offset that contains their row. */
    data class LocateView(
        val location: StandingsLocation,
        val offset: Int,
        val limit: Int,
    )

    /**
     * One page of a (band, sex) group (#220). When no [band] is given, defaults to the first available
     * group (strongest band, Men first) so a bare call still returns a page. An empty leaderboard yields
     * an empty view. [limit] is clamped to a sane page size (default 25).
     *
     * The source is resolved from the `standings_source` app-setting (#146), read fresh per request: RATING
     * (default) is computed live from current ratings; POINTS is served from the latest published POINTS
     * snapshot. POINTS never falls back to the live RATING calculation (#428) — with no POINTS snapshot the
     * page is an explicit empty view still tagged `source=POINTS`.
     */
    fun page(
        token: VerifiedFirebaseToken,
        band: StandingsBand?,
        sex: String?,
        limit: Int?,
        offset: Int?,
    ): StandingsView {
        val revealRates = callerCanSeeRates(token = token)
        val pageSize = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE)
        val pageOffset = (offset ?: 0).coerceAtLeast(minimumValue = 0)
        val request = PageRequest(band = band, sex = sex, limit = pageSize, offset = pageOffset, revealRates = revealRates)
        val source = settings.standingsSource()
        return if (source == SnapshotSource.POINTS) {
            // POINTS serves the latest published snapshot only; no snapshot → an explicit empty POINTS view
            // (never the live rating leaderboard, #428).
            val pointsSnapshotId = snapshots.latestPublished(source = SnapshotSource.POINTS)
            if (pointsSnapshotId != null) {
                servePage(snapshotId = pointsSnapshotId, request = request)
            } else {
                emptyView(request = request, groups = emptyList(), source = SnapshotSource.POINTS)
            }
        } else {
            serveLive(request = request)
        }
    }

    /** The resolved page request + privacy flag — bundled so serving stays under the parameter limit. */
    private data class PageRequest(
        val band: StandingsBand?,
        val sex: String?,
        val limit: Int,
        val offset: Int,
        val revealRates: Boolean,
    )

    /**
     * Serve a RATING page by live calculation: rank every active rated player into (band, sex) groups
     * (the single source of truth for the comparator + banding, shared with [locateMe]) and page the
     * requested group in memory. A requested group is honored verbatim (absent → empty page), else the
     * first available group serves, else an empty view.
     */
    private fun serveLive(request: PageRequest): StandingsView {
        val leaderboard = rankLeaderboard()
        val groups = leaderboard.keys.sortedWith(comparator = groupComparator()).map { GroupRef(band = it.first, sex = it.second) }
        // The requested group is honored verbatim (absent → empty page), else the first available serves.
        val chosen = request.band?.let { GroupRef(band = it, sex = request.sex) } ?: groups.firstOrNull()
        return if (chosen == null) {
            emptyView(request = request, groups = groups, source = SnapshotSource.RATING)
        } else {
            val ranked = leaderboard[chosen.band to chosen.sex].orEmpty()
            val today = LocalDate.now()
            val entries =
                ranked
                    .drop(n = request.offset)
                    .take(n = request.limit)
                    .map { player ->
                        StandingEntry(
                            rank = player.rank,
                            userId = player.user.id,
                            displayName = player.user.displayName(),
                            publicCode = player.user.publicCode,
                            sex = player.user.sex,
                            age = player.user.dateOfBirth?.let { ageInYears(dateOfBirth = it, asOf = today) },
                            // Revealed only to privileged viewers (#186); read straight from the live rating,
                            // so there is no map miss and no dead null-rating branch.
                            currentRating = if (request.revealRates) player.rating.currentRating.toPlainString() else null,
                            placeholder = player.user.placeholder,
                        )
                    }
            StandingsView(
                band = chosen.band,
                sex = chosen.sex,
                entries = entries,
                total = ranked.size,
                limit = request.limit,
                offset = request.offset,
                groups = groups,
                allBands = StandingsBand.entries.reversed(),
                revealRates = request.revealRates,
                source = SnapshotSource.RATING,
            )
        }
    }

    /** A ranked player in the live leaderboard: the user, their 1-based rank, and their current rating. */
    private data class RankedPlayer(
        val user: User,
        val rank: Int,
        val rating: UserRating,
    )

    /**
     * The live rating-derived leaderboard: every active rated player grouped by (band, sex) and ranked
     * from 1 within each group by the [standingsComparator] (rating → confidence → matches → name). The
     * single source of truth for banding + the comparator, shared by [serveLive] and [locateMe].
     */
    private fun rankLeaderboard(): Map<Pair<StandingsBand, String?>, List<RankedPlayer>> {
        val current = ratings.allCurrentRatings().associateBy { it.userId }
        val active = users.findAllByIds(ids = current.keys.toList()).filter { it.isActive }
        return active
            .groupBy { StandingsBand.of(rating = current.getValue(key = it.id).currentRating) to it.sex }
            .mapValues { (_, group) ->
                group
                    .sortedWith(comparator = standingsComparator(current = current))
                    .mapIndexed { index, user -> RankedPlayer(user = user, rank = index + 1, rating = current.getValue(key = user.id)) }
            }
    }

    /** An empty view for a request that resolved to no group — still carries the groups + every NTRP band (#113). */
    private fun emptyView(
        request: PageRequest,
        groups: List<GroupRef>,
        source: SnapshotSource,
    ): StandingsView =
        StandingsView(
            band = null,
            sex = null,
            entries = emptyList(),
            total = 0,
            limit = request.limit,
            offset = request.offset,
            groups = groups,
            allBands = StandingsBand.entries.reversed(),
            revealRates = request.revealRates,
            source = source,
        )

    /**
     * Serve a POINTS page from [snapshotId] (#146). When no [band] is requested, defaults to the first
     * available group; a requested group is honored verbatim (absent → empty page). An empty snapshot
     * yields an empty view still carrying every NTRP band (#113).
     */
    private fun servePage(
        snapshotId: UUID,
        request: PageRequest,
    ): StandingsView {
        val groups = snapshots.groups(snapshotId = snapshotId).map { GroupRef(band = it.first, sex = it.second) }
        // The requested group is honored verbatim (absent → empty page), else the first available serves.
        val chosen = request.band?.let { GroupRef(band = it, sex = request.sex) } ?: groups.firstOrNull()
        if (chosen == null) return emptyView(request = request, groups = groups, source = SnapshotSource.POINTS)

        val result =
            snapshots.page(snapshotId = snapshotId, band = chosen.band, sex = chosen.sex, limit = request.limit, offset = request.offset)
        val today = LocalDate.now()
        val shownIds = result.entries.map { it.userId }
        val byId = users.findAllByIds(ids = shownIds).associateBy { it.id }
        val entries =
            result.entries.map { entry ->
                // The snapshot's user_id FK is ON DELETE CASCADE, so every ranked entry has a live user.
                val user = byId.getValue(key = entry.userId)
                StandingEntry(
                    rank = entry.rank,
                    userId = user.id,
                    displayName = user.displayName(),
                    publicCode = user.publicCode,
                    sex = user.sex,
                    age = user.dateOfBirth?.let { ageInYears(dateOfBirth = it, asOf = today) },
                    // The POINTS metric shown is the snapshot's ordering value (points), public per #64/#114
                    // (#457). The rating is NOT the served metric here, so it stays null — never leaked.
                    points = entry.orderingValue.toPlainString(),
                    placeholder = user.placeholder,
                )
            }
        return StandingsView(
            band = chosen.band,
            sex = chosen.sex,
            entries = entries,
            total = result.total,
            limit = request.limit,
            offset = request.offset,
            groups = groups,
            allBands = StandingsBand.entries.reversed(),
            revealRates = request.revealRates,
            source = SnapshotSource.POINTS,
        )
    }

    /**
     * Jump-to-me (#220): the caller's (band, sex, rank) plus the page offset that contains their row (so
     * the client can load exactly that page). Null when the caller has no profile or isn't in the current
     * standings (e.g. unrated). RATING is located live; POINTS locates in the latest published POINTS
     * snapshot and is null when no POINTS snapshot exists (never the live calculation, #428). [limit] is the
     * page size used for the offset.
     */
    fun locateMe(
        token: VerifiedFirebaseToken,
        limit: Int?,
    ): LocateView? {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid) ?: return null
        val location =
            if (settings.standingsSource() == SnapshotSource.POINTS) {
                // POINTS: locate only in the latest published POINTS snapshot; no snapshot → null (#428).
                snapshots.latestPublished(source = SnapshotSource.POINTS)?.let { snapshotId ->
                    snapshots.locateEntry(snapshotId = snapshotId, userId = caller.id)?.let {
                        StandingsLocation(band = it.band, sex = it.sex, rank = it.rank)
                    }
                }
            } else {
                // Live: find the caller's (band, sex, rank) in the rating-derived leaderboard.
                rankLeaderboard().firstNotNullOfOrNull { (group, ranked) ->
                    ranked.firstOrNull { it.user.id == caller.id }?.let {
                        StandingsLocation(band = group.first, sex = group.second, rank = it.rank)
                    }
                }
            }
        return location?.let {
            val pageSize = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE)
            // rank is 1-based; the containing page starts at floor((rank - 1) / pageSize) * pageSize.
            val pageOffset = ((it.rank - 1) / pageSize) * pageSize
            LocateView(location = it, offset = pageOffset, limit = pageSize)
        }
    }

    /**
     * A single player's competitive standing (#448) for their profile: their (band, sex, rank) plus the
     * source-appropriate metric (#457), under the **active** `standings_source` (read fresh per request).
     * RATING is computed live from current ratings ([PlayerStanding.rating] = the current rating); POINTS
     * reads the latest published POINTS snapshot ([PlayerStanding.points] = the snapshot's ordering value).
     * Only one metric is set per source. Null when the player isn't in the current standings — unrated for
     * RATING, or absent / no POINTS snapshot for POINTS — so the UI shows "unranked". This read is
     * viewer-agnostic; the caller-aware reveal of the RATING value (#186) is applied one layer up by the
     * profile service, where the optional caller is known.
     */
    fun locatePlayer(userId: UUID): PlayerStanding? =
        if (settings.standingsSource() == SnapshotSource.POINTS) {
            // POINTS: only the latest published POINTS snapshot; no snapshot → null (never live, #428).
            snapshots.latestPublished(source = SnapshotSource.POINTS)?.let { snapshotId ->
                snapshots.locateEntry(snapshotId = snapshotId, userId = userId)?.let { entry ->
                    PlayerStanding(
                        band = entry.band,
                        sex = entry.sex,
                        rank = entry.rank,
                        // POINTS metric is public (#64/#114); no rating here (#457).
                        points = entry.orderingValue,
                        source = SnapshotSource.POINTS,
                    )
                }
            }
        } else {
            // Live: find the player in the rating-derived leaderboard; the RATING metric is the current
            // rating, revealed by the caller-aware read (#186/#457) — the model carries the raw value.
            rankLeaderboard().firstNotNullOfOrNull { (group, ranked) ->
                ranked.firstOrNull { it.user.id == userId }?.let {
                    PlayerStanding(
                        band = group.first,
                        sex = group.second,
                        rank = it.rank,
                        rating = it.rating.currentRating,
                        source = SnapshotSource.RATING,
                    )
                }
            }
        }

    /** Whether the viewer may see precise ratings (RATER or ADMINISTRATOR), mirroring the match page (#136/#186). */
    private fun callerCanSeeRates(token: VerifiedFirebaseToken): Boolean {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid) ?: return false
        return caller.capabilities.any { it == Capability.RATER || it == Capability.ADMINISTRATOR }
    }

    private fun standingsComparator(current: Map<UUID, UserRating>): Comparator<User> =
        Comparator { left, right ->
            // Final tie-break on a stable display label (display name, else public code).
            fun standingName(user: User): String = user.displayName() ?: user.publicCode
            val leftRating = current.getValue(key = left.id)
            val rightRating = current.getValue(key = right.id)
            var order = rightRating.currentRating.compareTo(other = leftRating.currentRating)
            if (order == 0) order = rightRating.confidence.compareTo(other = leftRating.confidence)
            if (order == 0) order = rightRating.matchesPlayed.compareTo(other = leftRating.matchesPlayed)
            if (order == 0) order = standingName(user = left).compareTo(other = standingName(user = right))
            order
        }

    /** Strongest band first (enum ordinal descending), then Men → Women → Unspecified — mirrors the read UI. */
    private fun groupComparator(): Comparator<Pair<StandingsBand, String?>> =
        compareByDescending<Pair<StandingsBand, String?>> { it.first.ordinal }
            .thenBy {
                when (it.second) {
                    "Male" -> 0
                    "Female" -> 1
                    else -> 2
                }
            }
}
