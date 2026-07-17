// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.club

import kotlinx.serialization.Serializable
import org.skopeo.model.ClubPublicEvent
import org.skopeo.model.ClubPublicView
import org.skopeo.model.ClubView

/** Body for `POST /api/v1/clubs` — an administrator creates a club. */
@Serializable
data class CreateClubRequest(
    val name: String,
)

/** Body for `PATCH /api/v1/clubs/{id}` — rename a club (#325). */
@Serializable
data class UpdateClubRequest(
    val name: String,
)

/** Body for `POST /api/v1/clubs/{id}/owners` — assign an existing user as an owner of the club. */
@Serializable
data class AssignOwnerRequest(
    val userId: String,
)

@Serializable
data class ClubOwnerDto(
    val userId: String,
    val displayName: String? = null,
    val publicCode: String,
)

@Serializable
data class ClubResponse(
    val id: String,
    val name: String,
    // The shareable code for the club's public-by-code page (#327).
    val publicCode: String,
    val isActive: Boolean,
    val owners: List<ClubOwnerDto>,
)

fun ClubView.toResponse(): ClubResponse =
    ClubResponse(
        id = id.toString(),
        name = name,
        publicCode = publicCode,
        isActive = isActive,
        owners =
            owners.map {
                ClubOwnerDto(userId = it.userId.toString(), displayName = it.displayName, publicCode = it.publicCode)
            },
    )

/**
 * One of a club's events on its public page (#327): the shareable code, name, and date range, plus
 * the public per-event points (#403 Phase E) — [eventType] and both [designatedPoints] (planned) and
 * [awardedPoints] (finalized). The UI shows awarded once the event is finalized, else designated.
 * Club utilization is deliberately NOT here — it is served only by the gated points-summary.
 */
@Serializable
data class ClubPublicEventDto(
    val publicCode: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val eventType: String,
    val designatedPoints: Int,
    val awardedPoints: Int,
)

/**
 * Read-only public summary of a club (#327): its name plus the events it organizes, split into
 * [upcoming] and [past]. No owner/roster PII is exposed. [isActive] is false once the club has been
 * soft-deleted, so the public page can flag it while the link stays honored.
 */
@Serializable
data class ClubPublicResponse(
    val publicCode: String,
    val name: String,
    val isActive: Boolean = true,
    val upcoming: List<ClubPublicEventDto>,
    val past: List<ClubPublicEventDto>,
)

private fun ClubPublicEvent.toDto(): ClubPublicEventDto =
    ClubPublicEventDto(
        publicCode = publicCode,
        name = name,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        eventType = eventType.name,
        designatedPoints = designatedPoints,
        awardedPoints = awardedPoints,
    )

fun ClubPublicView.toResponse(): ClubPublicResponse =
    ClubPublicResponse(
        publicCode = publicCode,
        name = name,
        isActive = isActive,
        upcoming = upcoming.map { it.toDto() },
        past = past.map { it.toDto() },
    )
