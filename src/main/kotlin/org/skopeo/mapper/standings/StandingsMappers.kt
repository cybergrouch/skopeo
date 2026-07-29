// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.standings

import org.skopeo.dto.standings.StandingEntryResponse
import org.skopeo.dto.standings.StandingsBandResponse
import org.skopeo.dto.standings.StandingsGroupResponse
import org.skopeo.dto.standings.StandingsLocateResponse
import org.skopeo.dto.standings.StandingsPageResponse
import org.skopeo.model.LocateView
import org.skopeo.model.StandingEntry
import org.skopeo.model.StandingsView

fun StandingsView.toResponse(): StandingsPageResponse =
    StandingsPageResponse(
        band = band?.code,
        label = band?.label,
        sex = sex,
        limit = limit,
        offset = offset,
        total = total,
        entries = entries.map { it.toResponse() },
        groups = groups.map { StandingsGroupResponse(band = it.band.code, label = it.band.label, sex = it.sex) },
        bands = allBands.map { StandingsBandResponse(code = it.code, label = it.label) },
        source = source.name,
    )

fun LocateView.toResponse(): StandingsLocateResponse =
    StandingsLocateResponse(
        band = location.band.code,
        label = location.band.label,
        sex = location.sex,
        rank = location.rank,
        limit = limit,
        offset = offset,
    )

fun StandingEntry.toResponse(): StandingEntryResponse =
    StandingEntryResponse(
        rank = rank,
        userId = userId.toString(),
        displayName = displayName,
        publicCode = publicCode,
        sex = sex,
        age = age,
        currentRating = currentRating,
        points = points,
        isPlaceholder = placeholder,
        isDeleted = deleted,
    )
