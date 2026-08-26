// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.testsupport

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.domain.mapper.entity.club.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.Club
import org.skopeo.domain.model.CreateClubCommand
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.UserRepository
import org.skopeo.repository.UsersTable
import java.util.UUID

/**
 * A club that merely *exists*, with **no named owner** — for repository-level fixtures.
 *
 * Since #794 every `events` row needs a club, so suites that drive [org.skopeo.repository.EventRepository]
 * directly need one to exist. Those suites bypass authorization entirely, so an owner would be
 * meaningless there: #789 reads `club_owners`, and this helper deliberately writes no row into it.
 * `clubs.created_by` is provenance, not ownership, which is why filling it from whatever user happens to
 * be around grants nobody anything.
 *
 * Use [seedFixtureClub] instead for anything that goes through `mayFileUnder`/`mayOrganize`.
 */
fun seedClub(name: String = "Fixture TC"): Club =
    ClubRepository().create(command = CreateClubCommand(name = name, createdBy = anyUserId())).toDomain()

/**
 * Any user id at all, purely to satisfy the non-null `clubs.created_by` column. Prefers a user the suite
 * has already provisioned so [seedClub] adds no row a test might count, and provisions a throwaway only
 * when the table is still empty.
 */
private fun anyUserId(): UUID =
    transaction { UsersTable.selectAll().map { it[UsersTable.id].value }.firstOrNull() }
        ?: UserRepository()
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "fixture-club-creator",
                        identity =
                            UserIdentity(
                                provider = AuthProvider.PASSWORD,
                                providerUid = "fixture-club-creator",
                                isPrimary = true,
                            ),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = "Fixture Club Creator")),
                    ),
            ).toDomain()
            .id

/**
 * A club owned by each of [ownerUids], for fixtures that just need *a* club.
 *
 * Every event belongs to a club since #794, and its creator must own that club (#789) — so a fixture that
 * creates an event needs both facts arranged. Most tests do not care which club it is; they care about
 * finalizing, seeding, results, and so on. This keeps that setup to one line and, by making the caller an
 * owner, stops the #789 gate turning unrelated fixtures into 403s.
 *
 * Pass **only** the uid that will create the event. Handing every provisioned uid to one club would make
 * the authz-refusal tests ("a non-owner host cannot seed another host's event") pass for the wrong reason.
 *
 * Tests that exercise club scoping itself should build their clubs explicitly instead, so the ownership
 * under test is visible in the test rather than hidden in a helper.
 */
fun seedFixtureClub(
    vararg ownerUids: String,
    name: String = "Fixture TC",
): Club {
    val users = UserRepository()
    val clubs = ClubRepository()
    val owners =
        ownerUids.map { uid ->
            requireNotNull(value = users.findByFirebaseUid(firebaseUid = uid)) {
                "seedFixtureClub: no provisioned user for uid '$uid'"
            }.toDomain()
        }
    require(value = owners.isNotEmpty()) { "seedFixtureClub needs at least one owner uid" }
    val club = clubs.create(command = CreateClubCommand(name = name, createdBy = owners.first().id)).toDomain()
    owners.forEach { clubs.addOwner(clubId = club.id, userId = it.id) }
    return club
}
