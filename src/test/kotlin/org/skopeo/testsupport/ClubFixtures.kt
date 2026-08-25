// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.testsupport

import org.skopeo.domain.mapper.entity.club.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.Club
import org.skopeo.domain.model.CreateClubCommand
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.UserRepository

/**
 * A club owned by each of [ownerUids], for fixtures that just need *a* club.
 *
 * Every event belongs to a club since #794, and its creator must own that club (#789) — so a fixture that
 * creates an event needs both facts arranged. Most tests do not care which club it is; they care about
 * finalizing, seeding, results, and so on. This keeps that setup to one line and, by making the caller an
 * owner, stops the #789 gate turning unrelated fixtures into 403s.
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
