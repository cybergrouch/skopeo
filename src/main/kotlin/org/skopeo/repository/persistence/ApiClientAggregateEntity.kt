// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

/**
 * Raw persistence graph of a partner API client (#633): the [client] row plus its separately-loaded
 * [keys] (from the `api_keys` table). This is the shape `ApiClientRepository` returns — only the
 * repository can run the extra query, so it bundles the children here and the `mapper.entity`
 * conversion builds the domain `ApiClient` (parsing statuses/scopes) with no further DB access. Kept
 * **model-free** (a raw [ApiClientEntity] + raw [ApiKeyEntity]s) so `persistence` stays a leaf.
 */
data class ApiClientAggregateEntity(
    val client: ApiClientEntity,
    val keys: List<ApiKeyEntity>,
)
