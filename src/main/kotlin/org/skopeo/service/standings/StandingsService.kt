// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import org.skopeo.model.Capability
import org.skopeo.model.SnapshotSource
import org.skopeo.model.SnapshotStatus
import org.skopeo.model.StandingEntry
import org.skopeo.model.StandingsBand
import org.skopeo.model.StandingsEntryWrite
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
import java.time.LocalDateTime
import java.util.UUID

private const val DEFAULT_PAGE_SIZE = 25
private const val MAX_PAGE_SIZE = 100

/**
 * The per-band "Ranking Race" standings (issue #113), served from a persisted, paged snapshot (#220).
 *
 * [rebuild] recomputes the rating-derived leaderboard and persists it as a new PUBLISHED snapshot; it
 * is triggered whenever ratings change (a calculation commit or a manual set). Reads ([page], [locateMe])
 * serve the latest published snapshot, paged one (band, sex) group at a time.
 *
 * The snapshot schema is deliberately **source-agnostic**: the entry's `orderingValue` is whatever the
 * ranking sorts by (the current rating today), so #146 can swap the source rating→points without
 * touching this read path. Order is what's exposed (#64/#114); the precise rating is revealed only to
 * RATER/ADMINISTRATOR viewers (#186), computed on read from the ordering value.
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
    )

    /** A selectable (band, sex) group present in the latest snapshot — powers the UI band dropdown + sex toggle. */
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
     * Recompute the rating-derived standings and persist them as a new PUBLISHED snapshot (#220). Within
     * each (band, sex) group, active rated players are ordered by rating (D8: higher rating first, then
     * the deterministic confidence → matches → name tie-break) and ranked from 1. For the rating-derived
     * source, `orderingValue` and `tiebreakRating` are the rating; `achievedAt` is left null (deriving the
     * earliest-at-rating date from history isn't cheap at rebuild scale). Called after any rating change.
     */
    fun rebuild() {
        val current = ratings.allCurrentRatings().associateBy { it.userId }
        val active = users.findAllByIds(ids = current.keys.toList()).filter { it.isActive }
        val byBand = active.groupBy { StandingsBand.of(rating = current.getValue(key = it.id).currentRating) }

        val writes =
            StandingsBand.entries.flatMap { band ->
                byBand[band]
                    .orEmpty()
                    .groupBy { it.sex }
                    .flatMap { (sex, group) -> rankGroup(band = band, sex = sex, players = group, current = current) }
            }
        snapshots.create(
            computedAt = LocalDateTime.now(),
            asOf = LocalDate.now(),
            status = SnapshotStatus.PUBLISHED,
            entries = writes,
            // Tag this generation RATING (#146); reads prefer a committed POINTS snapshot over it.
            source = SnapshotSource.RATING,
        )
    }

    /** One (band, sex) group ranked from 1 into persist-ready writes. */
    private fun rankGroup(
        band: StandingsBand,
        sex: String?,
        players: List<User>,
        current: Map<UUID, UserRating>,
    ): List<StandingsEntryWrite> =
        players
            .sortedWith(comparator = standingsComparator(current = current))
            .mapIndexed { index, user ->
                val rating = current.getValue(key = user.id).currentRating
                StandingsEntryWrite(
                    band = band,
                    sex = sex,
                    rank = index + 1,
                    userId = user.id,
                    // Source-agnostic: the ordering value is the rating today; points (#146) slot in here.
                    orderingValue = rating,
                    tiebreakRating = rating,
                    achievedAt = null,
                )
            }

    /**
     * One page of a (band, sex) group from the latest published snapshot (#220). When no [band] is given,
     * defaults to the first available group (strongest band, Men first) so a bare call still returns a page.
     * An empty snapshot yields an empty view. [limit] is clamped to a sane page size (default 25).
     */
    fun page(
        token: VerifiedFirebaseToken,
        band: StandingsBand?,
        sex: String?,
        limit: Int?,
        offset: Int?,
    ): StandingsView {
        val revealRates = callerCanSeeRates(token = token)
        // Serve the source the standings_source app-setting selects (#146): RATING by default, flip-able to
        // POINTS via a data change (no deploy). The read path is source-agnostic below this line.
        val snapshotId = configuredSnapshotId()
        val groups =
            snapshotId?.let { id -> snapshots.groups(snapshotId = id).map { GroupRef(band = it.first, sex = it.second) } }.orEmpty()
        val requested = band?.let { GroupRef(band = it, sex = sex) }
        val chosen = snapshotId?.let { chooseGroup(requested = requested, groups = groups) }
        val pageSize = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE)
        val pageOffset = (offset ?: 0).coerceAtLeast(minimumValue = 0)
        // Empty snapshot (no id) or no group to serve → an empty view, still carrying the available groups
        // and (always) every NTRP band so the dropdown stays fully populated (#113).
        return if (snapshotId == null || chosen == null) {
            StandingsView(
                band = null,
                sex = null,
                entries = emptyList(),
                total = 0,
                limit = pageSize,
                offset = pageOffset,
                groups = groups,
                allBands = StandingsBand.entries.reversed(),
                revealRates = revealRates,
            )
        } else {
            servePage(
                snapshotId = snapshotId,
                chosen = chosen,
                groups = groups,
                request = PageRequest(limit = pageSize, offset = pageOffset, revealRates = revealRates),
            )
        }
    }

    /** The resolved page window + privacy flag — bundled so [servePage] stays under the parameter limit. */
    private data class PageRequest(
        val limit: Int,
        val offset: Int,
        val revealRates: Boolean,
    )

    private fun servePage(
        snapshotId: UUID,
        chosen: GroupRef,
        groups: List<GroupRef>,
        request: PageRequest,
    ): StandingsView {
        val pageSize = request.limit
        val pageOffset = request.offset
        val revealRates = request.revealRates
        val result =
            snapshots.page(snapshotId = snapshotId, band = chosen.band, sex = chosen.sex, limit = pageSize, offset = pageOffset)
        val today = LocalDate.now()
        val shownIds = result.entries.map { it.userId }
        val byId = users.findAllByIds(ids = shownIds).associateBy { it.id }
        // For privileged viewers, reveal the precise current rating (full 6-dp scale, #186) read live —
        // the snapshot's ordering_value carries a coarser scale and is source-agnostic (points later, #146).
        val ratingsById = if (revealRates) ratings.findCurrentRatings(userIds = shownIds) else emptyMap()
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
                    // currentRating is a non-null BigDecimal, so only the map-miss arm is meaningful.
                    currentRating = ratingsById[user.id]?.let { it.currentRating.toPlainString() },
                )
            }
        return StandingsView(
            band = chosen.band,
            sex = chosen.sex,
            entries = entries,
            total = result.total,
            limit = pageSize,
            offset = pageOffset,
            groups = groups,
            allBands = StandingsBand.entries.reversed(),
            revealRates = revealRates,
        )
    }

    /**
     * Jump-to-me (#220): the caller's (band, sex, rank) in the latest published snapshot plus the page
     * offset that contains their row (so the client can load exactly that page). Null when the caller has
     * no profile or isn't in the snapshot (e.g. unrated). [limit] is the page size used to compute the offset.
     */
    fun locateMe(
        token: VerifiedFirebaseToken,
        limit: Int?,
    ): LocateView? {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val location =
            caller?.let {
                configuredSnapshotId()?.let { id -> snapshots.locate(snapshotId = id, userId = caller.id) }
            }
        return location?.let {
            val pageSize = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE)
            // rank is 1-based; the containing page starts at floor((rank - 1) / pageSize) * pageSize.
            val pageOffset = ((it.rank - 1) / pageSize) * pageSize
            LocateView(location = it, offset = pageOffset, limit = pageSize)
        }
    }

    /**
     * The snapshot the reads serve (#146): the latest PUBLISHED generation of the configured
     * [SettingsService.standingsSource] (RATING by default). Defensive fallback — if that source has no
     * published generation yet, serve the other source's latest so the tab is never blank when only one
     * source has ever been built.
     */
    private fun configuredSnapshotId(): UUID? {
        val configured = settings.standingsSource()
        val other = if (configured == SnapshotSource.RATING) SnapshotSource.POINTS else SnapshotSource.RATING
        return snapshots.latestPublished(source = configured) ?: snapshots.latestPublished(source = other)
    }

    /**
     * The group to serve: the explicitly-[requested] one honored verbatim (so an absent requested group
     * returns an empty page for THAT band, not a silent fallback to another), else the first available
     * group (strongest band, Men first), else null when the snapshot has no groups at all.
     */
    private fun chooseGroup(
        requested: GroupRef?,
        groups: List<GroupRef>,
    ): GroupRef? = requested ?: groups.firstOrNull()

    /** Whether the viewer may see precise ratings (RATER or ADMINISTRATOR), mirroring the match page (#136/#186). */
    private fun callerCanSeeRates(token: VerifiedFirebaseToken): Boolean {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid) ?: return false
        return caller.capabilities.any { it == Capability.RATER || it == Capability.ADMINISTRATOR }
    }

    private fun standingsComparator(current: Map<UUID, UserRating>): Comparator<User> =
        Comparator { left, right ->
            val leftRating = current.getValue(key = left.id)
            val rightRating = current.getValue(key = right.id)
            var order = rightRating.currentRating.compareTo(other = leftRating.currentRating)
            if (order == 0) order = rightRating.confidence.compareTo(other = leftRating.confidence)
            if (order == 0) order = rightRating.matchesPlayed.compareTo(other = leftRating.matchesPlayed)
            if (order == 0) order = standingName(user = left).compareTo(other = standingName(user = right))
            order
        }

    private fun standingName(user: User): String = user.displayName() ?: user.publicCode
}
