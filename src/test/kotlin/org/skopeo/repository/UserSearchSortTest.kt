// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AccountStatus
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreatePlaceholderCommand
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.SortDirection
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.model.UserSearchQuery
import org.skopeo.domain.model.UserSearchSort
import org.skopeo.domain.model.accountStatus
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Column ordering and the lifecycle-state filter on [UserRepository.search] (#1050).
 *
 * Against a real PostgreSQL, because the interesting parts are SQL: the correlated subqueries that
 * order by a value in another table, the `CASE` that ranks a derived status, and the tie-break that
 * keeps paging stable. None of that is observable from a stub.
 */
class UserSearchSortTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun migrateAndConnect() {
            PostgresTestDatabase.start()
        }
    }

    private val repository = UserRepository()
    private val ratings = RatingRepository()

    @BeforeEach
    fun resetTables() {
        PostgresTestDatabase.truncate()
    }

    /** Every search below filters on sex, the one facet every fixture here shares. */
    private val allMale =
        UserSearchQuery(
            name = null,
            code = null,
            q = null,
            sex = "Male",
            dobMin = null,
            dobMax = null,
            rating = null,
        )

    private var subCounter = 0

    /**
     * Provision a claimed account with a DISPLAY/FIRST/LAST name trio and an optional date of birth.
     * `providerUid` is uniquely constrained, so each call gets its own.
     */
    private fun player(
        display: String,
        first: String,
        last: String,
        sex: String = "Male",
        dateOfBirth: LocalDate? = null,
    ): UUID =
        repository.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "firebase-${UUID.randomUUID()}".asRedactable(),
                    identity =
                        UserIdentity(
                            provider = AuthProvider.GOOGLE,
                            providerUid = "sub-${subCounter++}",
                            isPrimary = true,
                        ),
                    names =
                        listOf(
                            UserName(type = NameType.DISPLAY, value = display),
                            UserName(type = NameType.FIRST, value = first),
                            UserName(type = NameType.LAST, value = last),
                        ),
                    photoUrl = null,
                    email = null,
                    sex = sex,
                    dateOfBirth = dateOfBirth?.asRedactable(),
                ),
        ).toDomain().id

    /** The DISPLAY names of a search result, in the order the database returned them. */
    private fun displayNames(
        sort: UserSearchSort?,
        direction: SortDirection = SortDirection.ASC,
        query: UserSearchQuery = allMale,
        includeInactive: Boolean = false,
        required: Int? = null,
    ): List<String?> =
        repository
            .search(
                query = query,
                sort = sort,
                direction = direction,
                includeInactive = includeInactive,
                calibrationRequired = required,
            )
            .map { entity ->
                entity.toDomain().names.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value
            }

    /**
     * Open a calibration window and park the stored count at [rated], without seeding matches.
     *
     * `setRating` stamps the window and zeroes the count (#1051); the count is then written directly
     * because what these tests exercise is the SQL that reads it, not the recompute that maintains it —
     * `CalibrationCountsTest` owns that, and going through matches here would test it twice while making
     * the arrangement unreadable.
     */
    private fun calibrating(
        userId: UUID,
        rated: Int,
    ) {
        ratings.setRating(userId = userId, rating = BigDecimal("4.0"), level = "4.0")
        transaction {
            UserRatingsTable.update(where = { UserRatingsTable.userId eq userId }) {
                it[calibrationMatchesRated] = rated
            }
        }
    }

    /**
     * A rating row with NO window: `calibration_started_at` null and the count 0 — the pre-#881 shape,
     * and the trap this filter has to survive. A predicate of `count < N` alone would match it, because
     * 0 is below every N.
     */
    private fun ratedButNeverDesignated(userId: UUID) {
        ratings.setRating(userId = userId, rating = BigDecimal("4.0"), level = "4.0")
        transaction {
            UserRatingsTable.update(where = { UserRatingsTable.userId eq userId }) {
                it[calibrationStartedAt] = null
                it[calibrationMatchesRated] = 0
            }
        }
    }

    @Test
    fun `filters on calibration without sweeping in players who were never designated (#1065)`() {
        val mid = player(display = "Midway", first = "M", last = "M")
        val nearlyDone = player(display = "NearlyDone", first = "N", last = "N")
        val finished = player(display = "Finished", first = "F", last = "F")
        val neverDesignated = player(display = "NeverDesignated", first = "X", last = "X")
        player(display = "NoRatingRow", first = "Z", last = "Z")
        calibrating(userId = mid, rated = 2)
        calibrating(userId = nearlyDone, rated = 9)
        calibrating(userId = finished, rated = 10)
        ratedButNeverDesignated(userId = neverDesignated)

        // N = 10. "Finished" has exactly 10, and the rule is `rated < required`, so 10 is settled.
        val calibrating = displayNames(sort = null, query = allMale.copy(inCalibration = true), required = 10).toSet()
        calibrating shouldBe setOf("Midway", "NearlyDone")

        // The negation must include BOTH the player with a null window and the one with no rating row —
        // neither is calibrating, and a filter that dropped them from both sides would lose rows.
        displayNames(sort = null, query = allMale.copy(inCalibration = false), required = 10).toSet() shouldBe
            setOf("Finished", "NeverDesignated", "NoRatingRow")
    }

    @Test
    fun `the calibration filter narrows countSearch identically to search (#1065)`() {
        val mid = player(display = "Midway", first = "M", last = "M")
        player(display = "Bystander", first = "B", last = "B")
        calibrating(userId = mid, rated = 3)

        val query = allMale.copy(inCalibration = true)
        // One predicate feeds both, so the pager cannot claim more rows than the filter matches.
        repository.search(query = query, calibrationRequired = 10) shouldHaveSize 1
        repository.countSearch(query = query, calibrationRequired = 10) shouldBe 1L
    }

    @Test
    fun `lowering N ends in-flight calibrations immediately, with no sweep (#881, #1065)`() {
        val mid = player(display = "Midway", first = "M", last = "M")
        calibrating(userId = mid, rated = 7)

        // THE property the stored-count design exists to preserve: the verdict is derived at read time
        // from a stored count against a live N, so changing N re-answers for everyone at once. A stored
        // boolean would still say "calibrating" here until something swept it.
        displayNames(sort = null, query = allMale.copy(inCalibration = true), required = 10).toSet() shouldBe
            setOf(element = "Midway")
        displayNames(sort = null, query = allMale.copy(inCalibration = true), required = 5).shouldBeEmpty()
        displayNames(sort = null, query = allMale.copy(inCalibration = false), required = 5).toSet() shouldBe
            setOf(element = "Midway")
    }

    @Test
    fun `sorts calibrating players first, furthest through the window leading (#1065)`() {
        val mid = player(display = "Midway", first = "M", last = "M")
        val nearlyDone = player(display = "NearlyDone", first = "N", last = "N")
        val settled = player(display = "Settled", first = "S", last = "S")
        calibrating(userId = mid, rated = 2)
        calibrating(userId = nearlyDone, rated = 9)
        ratedButNeverDesignated(userId = settled)

        // ASC = calibrating block first; within it the secondary is the count DESC, so the player nearest
        // the end of their window leads. Sorting on the boolean alone would have left these two in id
        // order, which is what the fixed secondary exists to avoid.
        displayNames(sort = UserSearchSort.CALIBRATION, required = 10) shouldContainExactly
            listOf("NearlyDone", "Midway", "Settled")

        // DESC flips the BLOCKS but not the secondary: "nearly finished first" is the interesting end
        // whichever way round the blocks sit.
        displayNames(sort = UserSearchSort.CALIBRATION, direction = SortDirection.DESC, required = 10) shouldContainExactly
            listOf("Settled", "NearlyDone", "Midway")
    }

    @Test
    fun `sorts by the display name in both directions (#1050)`() {
        player(display = "Charlie", first = "Charlie", last = "Cruz")
        player(display = "Alice", first = "Alice", last = "Abad")
        player(display = "Bob", first = "Bob", last = "Bautista")

        displayNames(sort = UserSearchSort.DISPLAY_NAME) shouldContainExactly
            listOf("Alice", "Bob", "Charlie")
        displayNames(sort = UserSearchSort.DISPLAY_NAME, direction = SortDirection.DESC) shouldContainExactly
            listOf("Charlie", "Bob", "Alice")
    }

    @Test
    fun `sorts by last and first name independently of the display name (#1050)`() {
        // Display order, last-name order and first-name order are all different, so a test that passed
        // by accident on one of them cannot pass on the others.
        player(display = "Aaa", first = "Zoe", last = "Mendoza")
        player(display = "Bbb", first = "Yuri", last = "Lopez")
        player(display = "Ccc", first = "Xena", last = "Navarro")

        displayNames(sort = UserSearchSort.LAST_NAME) shouldContainExactly listOf("Bbb", "Aaa", "Ccc")
        displayNames(sort = UserSearchSort.FIRST_NAME) shouldContainExactly listOf("Ccc", "Bbb", "Aaa")
    }

    @Test
    fun `sorts by age, which is the inverse of date of birth (#1050)`() {
        player(display = "Oldest", first = "O", last = "O", dateOfBirth = LocalDate.of(1960, 1, 1))
        player(display = "Youngest", first = "Y", last = "Y", dateOfBirth = LocalDate.of(2005, 1, 1))
        player(display = "Middle", first = "M", last = "M", dateOfBirth = LocalDate.of(1985, 1, 1))

        // Ascending AGE is the youngest first. Ordering by the raw `date_of_birth` column would have
        // produced exactly the reverse, which is the bug this asserts against.
        displayNames(sort = UserSearchSort.AGE) shouldContainExactly
            listOf("Youngest", "Middle", "Oldest")
        displayNames(sort = UserSearchSort.AGE, direction = SortDirection.DESC) shouldContainExactly
            listOf("Oldest", "Middle", "Youngest")
    }

    @Test
    fun `sorts by the current rating, which lives in another table (#1050)`() {
        val low = player(display = "Low", first = "L", last = "L")
        val high = player(display = "High", first = "H", last = "H")
        player(display = "Unrated", first = "U", last = "U")
        ratings.setRating(userId = low, rating = BigDecimal("3.0"), level = "3.0")
        ratings.setRating(userId = high, rating = BigDecimal("5.5"), level = "5.5")

        // Three rows out, not four: ordering by a correlated subquery cannot multiply rows the way a
        // join to `user_ratings` would — which is what would break `limit` and the paging total.
        val ascending = displayNames(sort = UserSearchSort.RATING)
        ascending shouldHaveSize 3
        ascending.filterNotNull().filter { it != "Unrated" } shouldContainExactly listOf("Low", "High")
    }

    @Test
    fun `sorts by status in the domain's precedence, not the enum ordinal (#1050)`() {
        val canonical = player(display = "Canonical", first = "C", last = "C")
        val merged = player(display = "Merged", first = "M", last = "M")
        val deleted = player(display = "Deleted", first = "D", last = "D")
        player(display = "Active", first = "A", last = "A")
        repository.createPlaceholder(command = CreatePlaceholderCommand(displayName = "Unclaimed", sex = "Male"))
        repository.markDuplicates(canonicalId = canonical, duplicateIds = listOf(element = merged))
        repository.deactivate(id = deleted)

        // MERGED, DELETED, UNCLAIMED, ACTIVE — the same order `User.accountStatus()` resolves in.
        // "Active" and "Canonical" are both ACTIVE and tie-break on id, so only their set is asserted.
        val ordered = displayNames(sort = UserSearchSort.STATUS, includeInactive = true)
        ordered.take(n = 3) shouldContainExactly listOf("Merged", "Deleted", "Unclaimed")
        ordered.drop(n = 3).toSet() shouldBe setOf("Active", "Canonical")
    }

    @Test
    fun `keeps paging stable when every row shares the sort value (#1050)`() {
        // Ten players with the same sex, sorted by sex: without the `id` tie-break the database is free
        // to return them in any order per query, and a page boundary can then drop or repeat a row.
        repeat(times = 10) { n -> player(display = "P$n", first = "F", last = "L") }

        fun pageOf(offset: Int) =
            repository
                .search(query = allMale, limit = 5, offset = offset, sort = UserSearchSort.SEX)
                .map { it.toDomain().id }

        val firstPage = pageOf(offset = 0)
        val secondPage = pageOf(offset = 5)

        firstPage shouldHaveSize 5
        secondPage shouldHaveSize 5
        // Disjoint and complete: ten distinct ids across two pages of five.
        (firstPage + secondPage).toSet() shouldHaveSize 10
        // And repeatable — the same offset gives the same page, which is what the tie-break buys.
        pageOf(offset = 0) shouldContainExactly firstPage
    }

    @Test
    fun `filters on each lifecycle state, with the higher-precedence ones excluded (#1050)`() {
        val canonical = player(display = "Canonical", first = "C", last = "C")
        val merged = player(display = "Merged", first = "M", last = "M")
        val deleted = player(display = "Deleted", first = "D", last = "D")
        player(display = "Active", first = "A", last = "A")
        repository.createPlaceholder(command = CreatePlaceholderCommand(displayName = "Unclaimed", sex = "Male"))
        repository.markDuplicates(canonicalId = canonical, duplicateIds = listOf(element = merged))
        repository.deactivate(id = deleted)

        fun matching(status: AccountStatus) =
            displayNames(
                sort = null,
                query = allMale.copy(status = status),
                includeInactive = true,
            ).toSet()

        matching(status = AccountStatus.MERGED) shouldBe setOf(element = "Merged")
        // DELETED must NOT include the merged account, even though merging also clears `is_active`.
        matching(status = AccountStatus.DELETED) shouldBe setOf(element = "Deleted")
        matching(status = AccountStatus.UNCLAIMED) shouldBe setOf(element = "Unclaimed")
        // ACTIVE must NOT include the unclaimed placeholder, which is active but not yet a person's.
        matching(status = AccountStatus.ACTIVE) shouldBe setOf("Active", "Canonical")

        // The four filters partition the rows: every account appears under exactly one status, and the
        // union is everything. Mutually-exclusive states are the whole premise of a Status column.
        val everyone = displayNames(sort = null, includeInactive = true).toSet()
        val partitioned = AccountStatus.entries.map { matching(status = it) }
        partitioned.flatten().toSet() shouldBe everyone
        partitioned.sumOf { it.size } shouldBe everyone.size
    }

    @Test
    fun `the SQL status filter agrees with the Kotlin derivation on every row (#1050)`() {
        val canonical = player(display = "Canonical", first = "C", last = "C")
        val merged = player(display = "Merged", first = "M", last = "M")
        val deleted = player(display = "Deleted", first = "D", last = "D")
        player(display = "Active", first = "A", last = "A")
        repository.createPlaceholder(command = CreatePlaceholderCommand(displayName = "Unclaimed", sex = "Male"))
        repository.markDuplicates(canonicalId = canonical, duplicateIds = listOf(element = merged))
        repository.deactivate(id = deleted)

        // `statusMatches` is SQL and `User.accountStatus()` is Kotlin, written twice by hand. A table
        // that filtered by a different rule than the one it displays would be silently wrong, and only
        // a reconciliation across both catches a drift in either.
        repository.search(query = allMale, includeInactive = true).forEach { entity ->
            val user = entity.toDomain()
            val viaSql =
                repository
                    .search(query = allMale.copy(status = user.accountStatus()), includeInactive = true)
                    .map { it.toDomain().id }
            viaSql shouldContain user.id
        }
    }

    @Test
    fun `counts the same rows it returns when filtering on status (#1050)`() {
        val canonical = player(display = "Canonical", first = "C", last = "C")
        val merged = player(display = "Merged", first = "M", last = "M")
        repository.markDuplicates(canonicalId = canonical, duplicateIds = listOf(element = merged))

        val query = allMale.copy(status = AccountStatus.MERGED)
        // The page and the total must come from one predicate; a count that ignored `status` would show
        // "1 of 2" under a filter that matches one row.
        repository.search(query = query, includeInactive = true) shouldHaveSize 1
        repository.countSearch(query = query, includeInactive = true) shouldBe 1L
    }

    @Test
    fun `sorting changes only the order, never which rows match (#1050)`() {
        val rated = player(display = "Rated", first = "R", last = "R", dateOfBirth = LocalDate.of(1990, 1, 1))
        player(display = "Unrated", first = "U", last = "U")
        repository.createPlaceholder(command = CreatePlaceholderCommand(displayName = "NoNames", sex = "Male"))
        ratings.setRating(userId = rated, rating = BigDecimal("4.0"), level = "4.0")

        // Each ordering term is a correlated subquery over a table many of these players have no row in
        // (no rating, no FIRST name, no date of birth). If one were ever rewritten as a join, the rows
        // without a match would silently disappear — a sort that quietly filters.
        val unsorted = displayNames(sort = null, includeInactive = true).toSet()
        unsorted shouldHaveSize 3
        UserSearchSort.entries.forEach { sort ->
            // N is supplied for every sort, not just CALIBRATION: the parameter is ignored by the others,
            // and iterating the enum is the point — a new sort key joins this guarantee automatically.
            displayNames(sort = sort, includeInactive = true, required = 10).toSet() shouldBe unsorted
        }
    }
}
