// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.user

import org.skopeo.domain.model.StaleAccount
import org.skopeo.repository.persistence.StaleAccountEntity

/** [StaleAccountEntity] → [StaleAccount] (#1122). */
fun StaleAccountEntity.toDomain(): StaleAccount = StaleAccount(userId = userId, publicCode = publicCode, createdAt = createdAt)
