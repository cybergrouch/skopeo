// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.event

import org.skopeo.domain.model.EventTeam
import org.skopeo.domain.model.EventTeamMember
import org.skopeo.repository.persistence.EventTeamAggregateEntity

/**
 * Entity→domain mapper (#720): builds the domain [EventTeam] from the raw [EventTeamAggregateEntity]
 * graph the repository returns (the `event_teams` row plus its loaded ordered members). Members are
 * sorted by slot [EventTeamMember.position] so P1/P2 order is stable. Lives in `mapper.entity` (which
 * may depend on both `persistence` and `model`); the service calls it, since `repository ↛ mapper`.
 */
fun EventTeamAggregateEntity.toDomain(): EventTeam =
    EventTeam(
        id = team.id,
        eventId = team.eventId,
        name = team.name,
        members =
            members
                .sortedBy { it.position }
                .map { EventTeamMember(userId = it.userId, position = it.position) },
    )
