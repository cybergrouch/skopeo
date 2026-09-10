// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.User

// Derived-state helpers over the User aggregate. These are domain logic (not data), so they live in
// the service layer rather than on the `model` value type — callers that cannot reach `service`
// (mappers, repositories) are handed the computed value instead.

/** The user's single active display name, if any (names include disabled ones). */
fun User.displayName(): String? = names.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value

/**
 * True when this account was soft-deleted by an administrator (#518). `is_active` is overloaded — a
 * merged duplicate (#124) is also inactive but carries a [User.canonicalUserId], so "deleted" is
 * qualified as inactive AND canonical-less. Centralized so every "Deleted" flag/list uses the same rule.
 */
fun User.isDeleted(): Boolean = !isActive && canonicalUserId == null

/**
 * How to name this user **in a message a human reads** — display name, else the public code.
 *
 * Never the internal UUID. #907 established the rule after error text naming a raw id sent staff
 * hunting for who it meant; #921 found the points path still doing it. The fallback is the public code
 * rather than the id on purpose: it is the identifier that appears on the player's own page and in a QR
 * share, so it is something the reader can actually look up.
 *
 * Deliberately pure. The querying stays in each service, which is why this could be shared at all —
 * a helper that resolved an id would have dragged a `UserRepository` into callers that already hold
 * the user and need no second read.
 */
fun User.nameForMessage(): String = displayName() ?: publicCode
