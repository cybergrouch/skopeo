// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.seeding

import org.skopeo.domain.model.Seeding
import org.skopeo.domain.model.SeedingEntry
import org.skopeo.repository.persistence.SeedingAggregateEntity
import org.skopeo.repository.persistence.SeedingEntryEntity

/**
 * Entity→domain mappers (#633): build the domain [Seeding] / [SeedingEntry] from the raw
 * [SeedingAggregateEntity] graph the repository returns (the `seedings` row plus its loaded,
 * status-resolved entries). Live in `mapper.entity` (which may depend on both `persistence` and `model`);
 * the service calls them, since `repository ↛ mapper`.
 */
fun SeedingEntryEntity.toDomain(): SeedingEntry =
    SeedingEntry(
        seed = seed,
        position = position,
        userId = userId,
        displayName = displayName,
        publicCode = publicCode,
        ntrpBand = ntrpBand,
        rating = rating,
        sex = sex,
        age = age,
        placeholder = placeholder,
        deleted = deleted,
    )

fun SeedingAggregateEntity.toDomain(): Seeding =
    Seeding(
        id = seeding.id,
        listId = seeding.listId,
        eventId = seeding.eventId,
        generatedAt = seeding.generatedAt,
        manuallyEdited = seeding.manuallyEdited,
        entries = entries.map { it.toDomain() },
    )
