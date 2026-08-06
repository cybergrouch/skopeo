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
