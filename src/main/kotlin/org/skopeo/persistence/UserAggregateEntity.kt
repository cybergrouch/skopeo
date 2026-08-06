// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import org.skopeo.common.security.Capability

/**
 * Raw persistence graph of a user (#633): the [user] row plus its separately-loaded children — the
 * [names]/[contacts]/[identities] rows and the granted [capabilities]. This is the shape `UserRepository`
 * returns — only the repository can run the extra child queries, so it bundles them here and the
 * `mapper.entity` conversion builds the domain `User` (computing the derived `photoUrl` and attaching the
 * children) with no further DB access. Kept **model-free** (raw entities + `common`'s [Capability]) so
 * `persistence` stays a leaf.
 */
data class UserAggregateEntity(
    val user: UserEntity,
    val names: List<NameEntity>,
    val contacts: List<ContactEntity>,
    val identities: List<IdentityEntity>,
    val capabilities: Set<Capability>,
)
