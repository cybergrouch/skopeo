// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

// Entity→domain mappers for the user aggregate (#633): builds the domain User from the raw
// UserAggregateEntity graph the repository returns, plus the per-row IdentityEntity conversion. Lives in
// mapper.entity (which may depend on persistence + model + common); the service calls it, since
// repository ↛ mapper. The child names/contacts reuse their own feature mappers.

package org.skopeo.domain.mapper.entity.user

import org.skopeo.common.redaction.asRedactable
import org.skopeo.domain.mapper.entity.contact.toDomain
import org.skopeo.domain.mapper.entity.name.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.effectivePhotoUrl
import org.skopeo.repository.persistence.IdentityEntity
import org.skopeo.repository.persistence.UserAggregateEntity

/**
 * Convert a raw [IdentityEntity] to the domain [UserIdentity]: the single boundary where the stored
 * `provider` string is parsed into the [AuthProvider] enum.
 */
fun IdentityEntity.toDomain(): UserIdentity =
    UserIdentity(
        provider = AuthProvider.valueOf(value = provider),
        providerUid = providerUid,
        isPrimary = isPrimary,
    )

/**
 * Build the domain [User] from the raw [UserAggregateEntity] graph the repository returns (the `users`
 * row plus its loaded name/contact/identity rows and capability grants). This is where the derived
 * `photoUrl` is computed (via [effectivePhotoUrl]) and the child sub-objects are attached.
 */
fun UserAggregateEntity.toDomain(): User =
    User(
        id = user.id,
        publicCode = user.publicCode,
        firebaseUid = user.firebaseUid?.asRedactable(),
        photoUrl =
            effectivePhotoUrl(
                providerPhotoUrl = user.providerPhotoUrl,
                customPhotoUrl = user.customPhotoUrl,
                photoHidden = user.photoHidden,
            ),
        providerPhotoUrl = user.providerPhotoUrl,
        customPhotoUrl = user.customPhotoUrl,
        photoHidden = user.photoHidden,
        matchHistoryHidden = user.matchHistoryHidden,
        dateOfBirth = user.dateOfBirth?.asRedactable(),
        sex = user.sex,
        city = user.city,
        country = user.country,
        kycVerified = user.kycVerified,
        isActive = user.isActive,
        proposedRating = user.proposedRating,
        canonicalUserId = user.canonicalUserId,
        placeholder = user.placeholder,
        claimedAt = user.claimedAt,
        claimedBy = user.claimedBy,
        previewRatingsAsNonAdmin = user.previewRatingsAsNonAdmin,
        names = names.map { it.toDomain() },
        contacts = contacts.map { it.toDomain() },
        identities = identities.map { it.toDomain() },
        capabilities = capabilities,
    )
