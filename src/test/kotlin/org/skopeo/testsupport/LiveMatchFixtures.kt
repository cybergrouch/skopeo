// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.testsupport

import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDate
import java.util.UUID

/**
 * A provisioned user for a live-scoring suite, identified only by [uid].
 *
 * The person is scaffolding here, not the subject: these suites need a foreign key that resolves —
 * `live_match_events.recorded_by` and `player_id` both point at `users` — and nothing more. Names and
 * contacts would be noise.
 */
fun seedLiveMatchUser(uid: String): UUID =
    UserRepository()
        .provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                ),
        ).toDomain()
        .id

/**
 * A singles fixture to hang a live-scoring log off, with both players provisioned.
 *
 * Built through [MatchRepository] rather than `MatchService` on purpose: the log's content is what these
 * suites are about, and routing the fixture through the #789 club gate would put authorization — which
 * none of them are testing — between the test and its subject. [fixtureEventId] supplies the event every
 * `matches` row has needed since #898.
 */
fun seedLiveMatchFixture(
    one: String = "home",
    two: String = "away",
): UUID {
    val home = seedLiveMatchUser(uid = one)
    val away = seedLiveMatchUser(uid = two)
    return MatchRepository()
        .createFixture(
            command =
                CreateFixtureCommand(
                    matchFormat = TeamType.SINGLES,
                    matchType = MatchType.OPEN_PLAY,
                    matchDate = LocalDate.now(),
                    team1UserIds = listOf(element = home),
                    team2UserIds = listOf(element = away),
                    team1Name = one,
                    team2Name = two,
                    createdBy = home,
                    eventId = fixtureEventId(home, away),
                ),
        ).toDomain()
        .id
}
