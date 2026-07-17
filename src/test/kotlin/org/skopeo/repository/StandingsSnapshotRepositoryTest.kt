// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.SnapshotSource
import org.skopeo.model.SnapshotStatus
import org.skopeo.model.StandingsBand
import org.skopeo.model.StandingsEntryWrite
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class StandingsSnapshotRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val repo = StandingsSnapshotRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = emptySet(),
                ),
        ).id

    private fun write(
        band: StandingsBand,
        sex: String?,
        rank: Int,
        userId: UUID,
        rating: String,
    ) = StandingsEntryWrite(
        band = band,
        sex = sex,
        rank = rank,
        userId = userId,
        orderingValue = BigDecimal(rating),
        tiebreakRating = BigDecimal(rating),
        achievedAt = null,
    )

    @Test
    fun `latestPublished is null before any snapshot is created (#220)`() {
        repo.latestPublished().shouldBeNull()
    }

    @Test
    fun `create persists a generation and latestPublished finds it (#220)`() {
        val u = newUser(uid = "u1")
        val id =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.2")),
            )
        repo.latestPublished() shouldBe id
    }

    @Test
    fun `latestPublished prefers the newest published generation (#220)`() {
        val u = newUser(uid = "u1")
        repo.create(
            computedAt = LocalDateTime.now().minusHours(1),
            asOf = LocalDate.now(),
            status = SnapshotStatus.PUBLISHED,
            entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.0")),
        )
        val newer =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.3")),
            )
        repo.latestPublished() shouldBe newer
    }

    @Test
    fun `latestPublished ignores a DRAFT generation (#220)`() {
        val u = newUser(uid = "u1")
        repo.create(
            computedAt = LocalDateTime.now(),
            asOf = LocalDate.now(),
            status = SnapshotStatus.DRAFT,
            entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.0")),
        )
        repo.latestPublished().shouldBeNull()
    }

    @Test
    fun `page returns the group ordered by rank with the full total, honoring limit and offset (#220)`() {
        val ids = (1..3).map { newUser(uid = "u$it") }
        val id =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries =
                    ids.mapIndexed { index, userId ->
                        write(band = StandingsBand.FROM_4_0, sex = "Male", rank = index + 1, userId = userId, rating = "4.${3 - index}")
                    },
            )
        val firstPage = repo.page(snapshotId = id, band = StandingsBand.FROM_4_0, sex = "Male", limit = 2, offset = 0)
        firstPage.total shouldBe 3
        firstPage.entries.map { it.rank } shouldContainExactly listOf(1, 2)
        firstPage.entries.map { it.userId } shouldContainExactly listOf(ids[0], ids[1])

        val secondPage = repo.page(snapshotId = id, band = StandingsBand.FROM_4_0, sex = "Male", limit = 2, offset = 2)
        secondPage.total shouldBe 3
        secondPage.entries.map { it.userId } shouldContainExactly listOf(element = ids[2])
    }

    @Test
    fun `page isolates a (band, sex) group, and the Unspecified sentinel round-trips to null (#220)`() {
        val male = newUser(uid = "m")
        val female = newUser(uid = "f")
        val none = newUser(uid = "n")
        val id =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries =
                    listOf(
                        write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = male, rating = "4.2"),
                        write(band = StandingsBand.FROM_4_0, sex = "Female", rank = 1, userId = female, rating = "4.1"),
                        write(band = StandingsBand.FROM_4_0, sex = null, rank = 1, userId = none, rating = "4.0"),
                    ),
            )
        repo.page(snapshotId = id, band = StandingsBand.FROM_4_0, sex = "Male", limit = 25, offset = 0)
            .entries.single().userId shouldBe male
        val unspecified = repo.page(snapshotId = id, band = StandingsBand.FROM_4_0, sex = null, limit = 25, offset = 0)
        unspecified.entries.single().userId shouldBe none
        unspecified.entries.single().sex.shouldBeNull()
    }

    @Test
    fun `groups lists distinct (band, sex) selectors strongest-first then Men, Women, Unspecified (#220)`() {
        val a = newUser(uid = "a")
        val b = newUser(uid = "b")
        val c = newUser(uid = "c")
        val d = newUser(uid = "d")
        val id =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries =
                    listOf(
                        write(band = StandingsBand.FROM_4_0, sex = "Female", rank = 1, userId = a, rating = "4.4"),
                        write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = b, rating = "4.3"),
                        write(band = StandingsBand.FROM_4_0, sex = null, rank = 1, userId = c, rating = "4.2"),
                        write(band = StandingsBand.SIX_PLUS, sex = "Male", rank = 1, userId = d, rating = "6.5"),
                    ),
            )
        repo.groups(snapshotId = id) shouldContainExactly
            listOf(
                StandingsBand.SIX_PLUS to "Male",
                StandingsBand.FROM_4_0 to "Male",
                StandingsBand.FROM_4_0 to "Female",
                StandingsBand.FROM_4_0 to null,
            )
    }

    @Test
    fun `locateEntry finds a user's (band, sex, rank) and points, null when absent (#220, #448)`() {
        val present = newUser(uid = "here")
        val absent = newUser(uid = "gone")
        val id =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries =
                    listOf(
                        write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = newUser(uid = "top"), rating = "4.4"),
                        write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 2, userId = present, rating = "4.1"),
                    ),
            )
        val located = repo.locateEntry(snapshotId = id, userId = present).shouldNotBeNull()
        located.band shouldBe StandingsBand.FROM_4_0
        located.sex shouldBe "Male"
        located.rank shouldBe 2
        located.orderingValue shouldBe BigDecimal("4.1000")

        repo.locateEntry(snapshotId = id, userId = absent).shouldBeNull()
    }

    @Test
    fun `the source column round-trips a POINTS generation and defaults to RATING (#146)`() {
        val u = newUser(uid = "u1")
        // An explicit POINTS generation is the only PUBLISHED one → prefer-points returns it.
        val points =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.0")),
                source = SnapshotSource.POINTS,
            )
        repo.latestPublishedPreferringPoints() shouldBe points

        // The default source (RATING) is used when omitted; latestPublished still finds the newest overall.
        val rating =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.1")),
            )
        repo.latestPublished() shouldBe rating
    }

    @Test
    fun `latestPublishedPreferringPoints prefers POINTS over a newer RATING generation (#146)`() {
        val u = newUser(uid = "u1")
        val points =
            repo.create(
                computedAt = LocalDateTime.now().minusHours(1),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.0")),
                source = SnapshotSource.POINTS,
            )
        // A NEWER rating-derived generation lands after the points one.
        repo.create(
            computedAt = LocalDateTime.now(),
            asOf = LocalDate.now(),
            status = SnapshotStatus.PUBLISHED,
            entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.3")),
            source = SnapshotSource.RATING,
        )
        // Reads still prefer the committed POINTS generation (the flip is sticky, not time-based).
        repo.latestPublishedPreferringPoints() shouldBe points
    }

    @Test
    fun `latestPublishedPreferringPoints falls back to RATING when no POINTS generation exists (#146)`() {
        val u = newUser(uid = "u1")
        val rating =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.0")),
                source = SnapshotSource.RATING,
            )
        repo.latestPublishedPreferringPoints() shouldBe rating
    }

    @Test
    fun `latestPublishedPreferringPoints prefers the newest POINTS generation and ignores a DRAFT (#146)`() {
        val u = newUser(uid = "u1")
        repo.create(
            computedAt = LocalDateTime.now().minusHours(2),
            asOf = LocalDate.now(),
            status = SnapshotStatus.PUBLISHED,
            entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.0")),
            source = SnapshotSource.POINTS,
        )
        // A DRAFT points generation must not be served.
        repo.create(
            computedAt = LocalDateTime.now(),
            asOf = LocalDate.now(),
            status = SnapshotStatus.DRAFT,
            entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.5")),
            source = SnapshotSource.POINTS,
        )
        val newerPublished =
            repo.create(
                computedAt = LocalDateTime.now(),
                asOf = LocalDate.now(),
                status = SnapshotStatus.PUBLISHED,
                entries = listOf(element = write(band = StandingsBand.FROM_4_0, sex = "Male", rank = 1, userId = u, rating = "4.3")),
                source = SnapshotSource.POINTS,
            )
        repo.latestPublishedPreferringPoints() shouldBe newerPublished
    }
}
