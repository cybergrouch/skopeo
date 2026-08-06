// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

/**
 * Raw persistence graph of a seeding (#633): the [seeding] root row plus its separately-loaded,
 * status-resolved [entries]. This is the shape `SeedingRepository` returns — only the repository can run
 * the child + live-status queries, so it bundles the children here and the `mapper.entity` conversion
 * builds the domain `Seeding` with no further DB access. Kept **model-free** so `persistence` stays a leaf.
 */
data class SeedingAggregateEntity(
    val seeding: SeedingEntity,
    val entries: List<SeedingEntryEntity>,
)
