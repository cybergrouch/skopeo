// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.dto.user

import org.skopeo.dto.user.ContactDto
import org.skopeo.dto.user.IdentityDto
import org.skopeo.dto.user.NameDto
import org.skopeo.dto.user.PublicRatingDto
import org.skopeo.dto.user.UserResponse
import org.skopeo.dto.user.UserSummaryResponse
import org.skopeo.dto.user.WinLossDto
import org.skopeo.model.NameType
import org.skopeo.model.User
import org.skopeo.model.UserRating
import org.skopeo.model.WinLossRecord
import org.skopeo.model.ageInYears
import java.time.LocalDate

fun User.toResponse(): UserResponse =
    UserResponse(
        id = id.toString(),
        publicCode = publicCode,
        firebaseUid = firebaseUid,
        photoUrl = photoUrl,
        customPhotoUrl = customPhotoUrl,
        photoHidden = photoHidden,
        matchHistoryHidden = matchHistoryHidden,
        dateOfBirth = dateOfBirth?.toString(),
        sex = sex,
        city = city,
        country = country,
        kycVerified = kycVerified,
        isActive = isActive,
        canonicalUserId = canonicalUserId?.toString(),
        names =
            names.map {
                NameDto(
                    id = it.id.toString(),
                    type = it.type.name,
                    value = it.value,
                    isActive = it.isActive,
                )
            },
        contacts =
            contacts.map {
                ContactDto(
                    id = it.id.toString(),
                    type = it.type.name,
                    value = it.value,
                    source = it.source.name,
                    status = it.status.name,
                    method = it.method?.name,
                    isPrimary = it.isPrimary,
                    isActive = it.isActive,
                )
            },
        identities =
            identities.map {
                IdentityDto(provider = it.provider.name, providerUid = it.providerUid, isPrimary = it.isPrimary)
            },
        capabilities = capabilities.map { it.name }.sorted(),
        previewRatingsAsNonAdmin = previewRatingsAsNonAdmin,
    )

fun User.toSummary(
    rating: UserRating? = null,
    record: WinLossRecord? = null,
    // The raw NTRP value is included only for an ADMINISTRATOR viewer (#583); everyone else gets the
    // band + confidence. Defaults false (the safe/privacy-preserving default) so callers must opt in.
    showRawRating: Boolean = false,
    // Soft-deleted flag, computed by the caller (service-side User.isDeleted()); mappers can't reach the
    // service layer, so the value is passed in rather than derived here.
    isDeleted: Boolean,
): UserSummaryResponse =
    UserSummaryResponse(
        id = id.toString(),
        publicCode = publicCode,
        displayName = names.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value,
        photoUrl = photoUrl,
        sex = sex,
        age = dateOfBirth?.let { ageInYears(dateOfBirth = it, asOf = LocalDate.now()) },
        rating =
            rating?.let {
                PublicRatingDto(
                    value = if (showRawRating) it.currentRating.toPlainString() else null,
                    level = it.currentLevel,
                    confidence = it.confidence.toPlainString(),
                )
            },
        capabilities = capabilities.map { it.name }.sorted(),
        record = record?.let { WinLossDto(wins = it.wins, losses = it.losses, total = it.total) },
        isPlaceholder = placeholder,
        isDeleted = isDeleted,
    )
