// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import org.skopeo.common.dto.user.CreateUserRequest
import org.skopeo.common.dto.user.UserResponse
import org.skopeo.common.dto.user.UserSummaryPageResponse
import org.skopeo.common.dto.user.UserSummaryResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.ACCOUNT_MANAGEMENT_ROLES
import org.skopeo.common.security.Capability
import org.skopeo.common.security.PLAYER_SEARCH_ROLES
import org.skopeo.domain.mapper.dto.user.toResponse
import org.skopeo.domain.mapper.dto.user.toSummary
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AccountStatus
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NumericRange
import org.skopeo.domain.model.ProfilePatch
import org.skopeo.domain.model.SortDirection
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserRating
import org.skopeo.domain.model.UserSearchQuery
import org.skopeo.domain.model.UserSearchSort
import org.skopeo.domain.model.ageRangeToDob
import org.skopeo.domain.model.canSeeRawRatingOrFalse
import org.skopeo.domain.model.effectivePhotoUrl
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.rating.CalibrationService
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.repository.CapabilityRepository
import org.skopeo.repository.InviteRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.enums.enumEntries

private val logger = KotlinLogging.logger {}

// Player-search pagination. The default page size preserves the historical cap for callers that
// don't paginate (the typeahead picker); the ceiling guards against oversized client requests.
private const val DEFAULT_SEARCH_LIMIT = 20
private const val MAX_SEARCH_LIMIT = 100

/**
 * User-search facets resolved at the route boundary (#116): [sex] is already validated and the
 * [age]/[rating] intervals are already parsed. The service trims the free-text terms and AND-combines.
 */
data class UserSearchFilters(
    val name: String? = null,
    val code: String? = null,
    val q: String? = null,
    val sex: String? = null,
    // Raw `min-max` range strings (#116/#317), parsed + validated in [UserService.validatedQuery].
    val age: String? = null,
    val rating: String? = null,
    // Raw capability name to restrict to (#317).
    val capability: String? = null,
    // Raw `AccountStatus` name to restrict to (#1050), parsed in [UserService.validatedQuery].
    val status: String? = null,
)

/**
 * Orchestrates user provisioning and CRUD, enforcing the self-or-ADMINISTRATOR
 * access policy. All mutations funnel through here (rather than the routes) so a
 * DB audit trail can be layered in later without touching the transport layer.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class UserService(
    private val repository: UserRepository = UserRepository(),
    private val capabilities: CapabilityRepository = CapabilityRepository(),
    private val ratings: RatingAssembler = RatingAssembler(),
    private val invites: InviteRepository = InviteRepository(),
    private val audit: AuditService = AuditService(),
    // The verdict is derived, never stored (#881); the count behind it is (#1051). The search page needs
    // it per row, so it is still asked in one batch — now a single read of the rating rows.
    private val calibration: CalibrationService = CalibrationService(),
    // Verified-email allowlist for the ADMINISTRATOR bootstrap (from ADMIN_EMAILS); empty = none.
    private val adminEmails: Set<String> = emptySet(),
) {
    /** Current ratings for the given users, keyed by id — enriches search summaries (issue #64). */
    fun currentRatings(ids: List<UUID>): Map<UUID, UserRating> = ratings.findCurrentRatings(userIds = ids)

    /**
     * Whether the caller may see raw NTRP values (#583): ADMINISTRATOR only, honoring the per-admin
     * "preview as non-admin" toggle. Anonymous/unresolved ⇒ false. Callers pass the result as
     * `showRawRating` into [User.toSummary] so the raw `value` is band-only for everyone else.
     */
    fun callerCanSeeRawRating(token: VerifiedFirebaseToken): Boolean =
        repository.findByFirebaseUid(firebaseUid = token.uid)?.toDomain().canSeeRawRatingOrFalse()

    /**
     * Set the caller's own per-admin "preview as non-admin" toggle (#583) — ADMINISTRATOR-only, since it
     * only affects raw-rating visibility which only admins have. When on, this admin sees the non-admin
     * (band-only) view of ratings on LIVE, without affecting anyone else. Audited.
     */
    fun setRatingPreview(
        token: VerifiedFirebaseToken,
        previewAsNonAdmin: Boolean,
    ): Either<ServiceError, Boolean> =
        either {
            val actorId = requireAdmin(token = token).bind()
            val value = repository.setPreviewRatingsAsNonAdmin(id = actorId, preview = previewAsNonAdmin).bind()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actorId,
                        action = AuditAction.SETTINGS_RATING_PREVIEW_CHANGED,
                        entityType = AuditEntityType.USER,
                        entityId = actorId,
                        summary = "Set own rating preview to ${if (value) "non-admin (band only)" else "admin (raw)"}",
                        details = buildMap { put(key = "previewAsNonAdmin", value = value.toString()) },
                    ),
            )
            value
        }

    /**
     * Player search backing the player-picker, role-grants, and the Research tab — RESEARCHER or staff
     * (#107). Any combination of facets is allowed and AND-combined; at least one is required.
     * [UserSearchFilters.name] is a fuzzy match; [UserSearchFilters.code] is a shareable player-code
     * prefix (case-insensitive, #86); [UserSearchFilters.q] is the unified picker term matching a
     * fuzzy name OR a code prefix (#86). Sex and the age/rating intervals are validated at the route;
     * age maps to a date-of-birth window, rating filters NTRP.
     */
    fun search(
        token: VerifiedFirebaseToken,
        filters: UserSearchFilters,
        limit: Int = DEFAULT_SEARCH_LIMIT,
        offset: Int = 0,
        // Include soft-deleted/inactive accounts (Research; #518). Default false keeps pickers active-only.
        includeInactive: Boolean = false,
    ): Either<ServiceError, List<UserSummaryResponse>> =
        either {
            requirePlayerSearchAccess(repository = repository, token = token).bind()
            val query = validatedQuery(filters = filters).bind()
            ensureStatusIsReachable(status = query.status, includeInactive = includeInactive)
            val users =
                repository.search(
                    query = query,
                    limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_SEARCH_LIMIT),
                    offset = offset.coerceAtLeast(minimumValue = 0),
                    includeInactive = includeInactive,
                ).map { it.toDomain() }
            // Enrich each summary with the current rating + the raw-reveal flag here (was assembled in the
            // route), so the route stays thin and never touches the mapper.
            val ratingsById = currentRatings(ids = users.map { it.id })
            val showRaw = callerCanSeeRawRating(token = token)
            users.map { it.toSummary(rating = ratingsById[it.id], showRawRating = showRaw, isDeleted = it.isDeleted()) }
        }

    /**
     * Like [search] but returns a page with the total match count, for numbered pagination (#232), plus
     * the column ordering the Research table sorts by (#1050).
     *
     * [sort] and [direction] are raw enum names (unknown values are a 400); absent means the historical
     * `id ASC`. Sorting is applied in the database, not to the returned page — sorting a page would
     * order 25 arbitrary rows rather than choosing which 25 they are.
     */
    fun searchPage(
        token: VerifiedFirebaseToken,
        filters: UserSearchFilters,
        limit: Int,
        offset: Int,
        // Include soft-deleted/inactive accounts (Research; #518). Default false keeps pickers active-only.
        includeInactive: Boolean = false,
        sort: String? = null,
        direction: String? = null,
    ): Either<ServiceError, UserSummaryPageResponse> =
        either {
            requirePlayerSearchAccess(repository = repository, token = token).bind()
            val query = validatedQuery(filters = filters).bind()
            ensureStatusIsReachable(status = query.status, includeInactive = includeInactive)
            val sortKey = sort?.let { raw -> enumByName<UserSearchSort>(raw = raw, field = "sort") }
            val sortOrder = direction?.let { raw -> enumByName<SortDirection>(raw = raw, field = "direction") }
            val items =
                repository.search(
                    query = query,
                    limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_SEARCH_LIMIT),
                    offset = offset.coerceAtLeast(minimumValue = 0),
                    includeInactive = includeInactive,
                    sort = sortKey,
                    direction = sortOrder ?: SortDirection.ASC,
                ).map { it.toDomain() }
            val total = repository.countSearch(query = query, includeInactive = includeInactive)
            // Enrich with current ratings + calibration (#881) + the raw-reveal flag here (was in the
            // route), returning the finished page DTO so the route stays thin.
            //
            // NOT the win–loss record (#342) any more: #1050's column spec excluded it and #1053 removed
            // it from the table, but this call was left behind — so every Research, Ratings-search and
            // Deleted-accounts page ran a `team_users` scan plus match aggregation across 25 users and
            // threw the answer away (#1062). `record` stays nullable on the DTO, already documented as
            // "populated for research results, else null", so dropping it changes no wire shape.
            val ids = items.map { it.id }
            val ratingsById = currentRatings(ids = ids)
            val calibrating = calibration.statusesFor(userIds = ids)
            val showRaw = callerCanSeeRawRating(token = token)
            UserSummaryPageResponse(
                items =
                    items.map {
                        it.toSummary(
                            rating = ratingsById[it.id],
                            showRawRating = showRaw,
                            isDeleted = it.isDeleted(),
                            inCalibration = calibrating[it.id]?.inCalibration,
                        )
                    },
                total = total.toInt(),
            )
        }

    /**
     * Refuse a status filter that the `includeInactive` flag has already excluded (#1050).
     *
     * Merging and deleting both clear `is_active`, so `status=MERGED` or `status=DELETED` against an
     * active-only search is a predicate that cannot match a row — the caller would get an empty page
     * and no reason for it. Widening the search silently instead would be worse: `includeInactive=false`
     * is an explicit instruction, and answering it with deleted accounts breaks the pickers that rely on
     * it. So say which of the two to change.
     */
    private fun Raise<ServiceError>.ensureStatusIsReachable(
        status: AccountStatus?,
        includeInactive: Boolean,
    ) {
        if (includeInactive) return
        if (status != AccountStatus.MERGED && status != AccountStatus.DELETED) return
        raise(
            r =
                ServiceError.Validation(
                    message =
                        "status=$status only exists among inactive accounts, which this search excludes; " +
                            "pass includeInactive=true, or filter on ACTIVE or UNCLAIMED",
                ),
        )
    }

    /** Build the repository query from request filters, requiring at least one filter (#116). */
    private fun validatedQuery(filters: UserSearchFilters): Either<ServiceError, UserSearchQuery> =
        either {
            val nameTerm = blankToNull(raw = filters.name)
            val codeTerm = blankToNull(raw = filters.code)?.uppercase()
            val qTerm = blankToNull(raw = filters.q)
            val age = filters.age?.let { NumericRange.parse(raw = it) }
            val rating = filters.rating?.let { NumericRange.parse(raw = it) }
            val capability = filters.capability?.let { raw -> enumByName<Capability>(raw = raw, field = "capability") }
            val status = filters.status?.let { raw -> enumByName<AccountStatus>(raw = raw, field = "status") }
            ensure(
                condition =
                    nameTerm != null || codeTerm != null || qTerm != null || filters.sex != null ||
                        age != null || rating != null || capability != null || status != null,
            ) {
                ServiceError.Validation(message = "at least one filter (name, code, q, sex, age, rating, capability, status) is required")
            }
            val dob = age?.let { ageRangeToDob(range = it, today = LocalDate.now()) }
            UserSearchQuery(
                name = nameTerm,
                code = codeTerm,
                q = qTerm,
                sex = filters.sex,
                dobMin = dob?.min,
                dobMax = dob?.max,
                rating = rating,
                capability = capability,
                status = status,
            )
        }

    /**
     * Resolve known user ids to their profiles — same gate as [search] (#867). Used to turn the bare
     * UUIDs the UI holds (match rosters, rating history, calculation previews) into display names.
     * Unknown ids are simply omitted from the result.
     */
    fun findByIds(
        token: VerifiedFirebaseToken,
        ids: List<UUID>,
    ): Either<ServiceError, List<UserSummaryResponse>> =
        either {
            requirePlayerSearchAccess(repository = repository, token = token).bind()
            ensure(condition = ids.isNotEmpty()) { ServiceError.Validation(message = "ids must not be empty") }
            val users = repository.findAllByIds(ids = ids).map { it.toDomain() }
            val ratingsById = currentRatings(ids = users.map { it.id })
            val showRaw = callerCanSeeRawRating(token = token)
            users.map { it.toSummary(rating = ratingsById[it.id], showRawRating = showRaw, isDeleted = it.isDeleted()) }
        }

    /** Outcome of provisioning: [created] distinguishes a fresh user (201) from an idempotent hit (200). */
    data class Provisioned(
        val user: UserResponse,
        val created: Boolean,
    )

    /**
     * The single final step of every sign-up flow. Identity comes from the verified
     * token; re-posting with an already-known uid returns the existing user (idempotent).
     */
    fun provision(
        token: VerifiedFirebaseToken,
        request: CreateUserRequest,
    ): Either<ServiceError, Provisioned> =
        either {
            val existing = repository.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
            if (existing != null) {
                provisionExisting(token = token, existing = existing).bind()
            } else {
                provisionNew(token = token, request = request).bind()
            }
        }

    /**
     * Idempotent re-provision of an already-known uid: reject a merged duplicate (#124) or an
     * admin-deleted account (#518), else promote-and-return.
     */
    private fun provisionExisting(
        token: VerifiedFirebaseToken,
        existing: User,
    ): Either<ServiceError, Provisioned> {
        // A disabled duplicate (#124) cannot sign back in; point them at the canonical account.
        val canonical = existing.canonicalUserId?.let { repository.findById(id = it).getOrNull()?.toDomain() }
        return when {
            canonical != null -> ServiceError.AccountMerged(canonicalPublicCode = canonical.publicCode).left()
            // An admin-deleted account (#518) — inactive with no canonical pointer (a merged duplicate was
            // ruled out above) — cannot sign back in until an admin re-allows login.
            existing.isDeleted() -> ServiceError.AccountDeleted.left()
            else -> {
                val promoted =
                    promoteIfBootstrapAdmin(token = token, user = existing, adminEmails = adminEmails, capabilities = capabilities)
                Provisioned(user = refreshPhoto(token = token, user = promoted).toResponse(), created = false).right()
            }
        }
    }

    /**
     * Keep the OAuth-provider photo in sync on login (#219): when the token carries a picture that
     * differs from the stored provider photo, persist it. A null/absent picture leaves it intact (we
     * never wipe it). This only ever touches the provider photo — a user's custom URL or hide choice
     * (#303) is honored: the effective photo is recomputed but their override is never overwritten.
     */
    private fun refreshPhoto(
        token: VerifiedFirebaseToken,
        user: User,
    ): User {
        val picture = token.picture
        if (picture == null || picture == user.providerPhotoUrl) return user
        repository.updateProviderPhotoUrl(userId = user.id, providerPhotoUrl = picture)
        return user.copy(
            providerPhotoUrl = picture,
            photoUrl = effectivePhotoUrl(providerPhotoUrl = picture, customPhotoUrl = user.customPhotoUrl, photoHidden = user.photoHidden),
        )
    }

    /**
     * Set the caller's (or, for an admin, a player's) photo controls (#303): a custom image URL and
     * the hide flag. Authorized self-or-ADMINISTRATOR, like the rest of profile editing.
     */
    fun updatePhotoSettings(
        token: VerifiedFirebaseToken,
        id: UUID,
        customPhotoUrl: String?,
        photoHidden: Boolean,
    ): Either<ServiceError, UserResponse> =
        either {
            val target = repository.findById(id = id).bind().toDomain()
            requireAccess(token = token, target = target).bind()
            repository.updatePhotoSettings(id = id, customPhotoUrl = customPhotoUrl, photoHidden = photoHidden)
                .bind().toDomain().toResponse()
        }

    /**
     * Set the caller's "hide match history from other players" flag (#622). Authorized self-or-
     * ADMINISTRATOR, like the rest of profile editing.
     */
    fun setMatchHistoryHidden(
        token: VerifiedFirebaseToken,
        id: UUID,
        hidden: Boolean,
    ): Either<ServiceError, UserResponse> =
        either {
            val target = repository.findById(id = id).bind().toDomain()
            requireAccess(token = token, target = target).bind()
            repository.setMatchHistoryHidden(id = id, hidden = hidden).bind().toDomain().toResponse()
        }

    /** First-time sign-up: enforce the invite gate, write the aggregate, and audit the creation. */
    private fun provisionNew(
        token: VerifiedFirebaseToken,
        request: CreateUserRequest,
    ): Either<ServiceError, Provisioned> =
        either {
            // Manual (password/email-link) sign-ups are invite-only; OAuth is exempt. Returns the gated
            // email so we can mark its invite accepted once the profile is created.
            val invitedEmail = requireInviteForManualSignup(invites = invites, token = token).bind()
            val command = buildProvisionCommand(token = token, request = request, adminEmails = adminEmails)
            val user = repository.provision(command = command).toDomain()
            if (invitedEmail != null) invites.markAccepted(email = invitedEmail, acceptedAt = LocalDateTime.now())
            audit.record(
                // Self sign-up: the new user is the actor.
                write =
                    AuditWrite(
                        actorUserId = user.id,
                        action = AuditAction.USER_CREATED,
                        entityType = AuditEntityType.USER,
                        entityId = user.id,
                        summary = "Signed up",
                    ),
            )
            Provisioned(user = user.toResponse(), created = true)
        }

    /** The caller's own profile, or null if they have not been provisioned yet. Refreshes the photo (#219). */
    fun currentUser(token: VerifiedFirebaseToken): UserResponse? {
        val user = repository.findByFirebaseUid(firebaseUid = token.uid)?.toDomain() ?: return null
        val promoted = promoteIfBootstrapAdmin(token = token, user = user, adminEmails = adminEmails, capabilities = capabilities)
        return refreshPhoto(token = token, user = promoted).toResponse()
    }

    fun getById(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, UserResponse> =
        either {
            val target = repository.findById(id = id).bind().toDomain()
            requireAccess(token = token, target = target).bind()
            target.toResponse()
        }

    fun patchProfile(
        token: VerifiedFirebaseToken,
        id: UUID,
        patch: ProfilePatch,
    ): Either<ServiceError, UserResponse> =
        either {
            val target = repository.findById(id = id).bind().toDomain()
            requireAccess(token = token, target = target).bind()
            repository.updateProfile(id = id, patch = patch).bind().toDomain().toResponse()
        }

    fun replaceProfile(
        token: VerifiedFirebaseToken,
        id: UUID,
        patch: ProfilePatch,
    ): Either<ServiceError, UserResponse> =
        either {
            val target = repository.findById(id = id).bind().toDomain()
            requireAccess(token = token, target = target).bind()
            repository.replaceProfile(id = id, patch = patch).bind().toDomain().toResponse()
        }

    /**
     * Admin-only soft-delete of an account (#518): flip `is_active = false`, retaining the row and all
     * history. Tightened from the self-or-admin [requireAccess] to ADMINISTRATOR-only — there is no
     * owner self-delete. Guards against deleting the last active ADMINISTRATOR (avoids an admin lockout).
     * Audited (target = the deleted user).
     */
    fun deactivate(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val actorId = requireAdmin(token = token).bind()
            val target = repository.findById(id = id).bind().toDomain()
            // Don't let a delete drop the system to zero active admins.
            ensure(
                condition = Capability.ADMINISTRATOR !in target.capabilities || capabilities.countActiveAdministrators() > 1,
            ) {
                ServiceError.Validation(message = "Cannot delete the last active ADMINISTRATOR")
            }
            repository.deactivate(id = id).bind()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actorId,
                        action = AuditAction.ACCOUNT_DELETED,
                        entityType = AuditEntityType.USER,
                        entityId = target.id,
                        summary = "Deleted account ${target.publicCode}",
                    ),
            )
        }

    /**
     * Re-allow login on a soft-deleted account (#518). A deleted account already has a null canonical
     * pointer, so this is a pure reactivation. Audited (target = user).
     *
     * **[ACCOUNT_MANAGEMENT_ROLES], not administrator-only, since #1002** — restoring an account is the
     * Account Management tab's job. Note the asymmetry with [deactivate] one function above, which stays
     * ADMINISTRATOR-only: it lives on `ManagePlayerSection`, the one card an account manager never sees.
     * Deleting an account and undoing that deletion are deliberately not the same permission — the
     * destructive half stays with administrators.
     */
    fun reactivate(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val actorId = requireAnyOf(users = repository, token = token, allowed = ACCOUNT_MANAGEMENT_ROLES).bind()
            val target = repository.findById(id = id).bind().toDomain()
            repository.reactivate(id = id).bind()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actorId,
                        action = AuditAction.ACCOUNT_REACTIVATED,
                        entityType = AuditEntityType.USER,
                        entityId = target.id,
                        summary = "Re-allowed login for account ${target.publicCode}",
                    ),
            )
        }

    /** Allow only the target user themselves or an ADMINISTRATOR. */
    private fun requireAccess(
        token: VerifiedFirebaseToken,
        target: User,
    ): Either<ServiceError, Unit> {
        val caller = repository.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        val isSelf = caller?.id == target.id
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (!isSelf && !isAdmin) ServiceError.Forbidden().left() else Unit.right()
    }

    /** Allow only an ADMINISTRATOR caller; returns their id for audit attribution. */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = repository.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller == null || Capability.ADMINISTRATOR !in caller.capabilities) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}

/** Trim a free-text search term, collapsing a null/blank value to null. */
private fun blankToNull(raw: String?): String? = raw?.trim()?.ifEmpty { null }

/**
 * Resolve [raw] to a [T] by name, case-insensitively, or raise a 400 naming every accepted value.
 *
 * Every enum-valued facet and sort key arrives as a query string, so the same "unknown value" answer is
 * owed for each; spelling the accepted values into the message is what makes a typo self-correcting
 * instead of an empty page.
 */
private inline fun <reified T : Enum<T>> Raise<ServiceError>.enumByName(
    raw: String,
    field: String,
): T =
    enumEntries<T>().find { it.name == raw.uppercase() }
        ?: raise(
            r =
                ServiceError.Validation(
                    message = "Unknown $field '$raw'; expected one of ${enumEntries<T>().joinToString { it.name }}",
                ),
        )

/**
 * Idempotently grant ADMINISTRATOR to an already-provisioned user whose verified email is on the
 * bootstrap allowlist — covering emails added to the list after the user first signed up. Grant-only
 * (removing an email never revokes); the grant is recorded with a null grantedBy (a system source).
 * See docs/engineering/architecture/ADMIN_BOOTSTRAP.md.
 */
private fun promoteIfBootstrapAdmin(
    token: VerifiedFirebaseToken,
    user: User,
    adminEmails: Set<String>,
    capabilities: CapabilityRepository,
): User {
    if (Capability.ADMINISTRATOR in user.capabilities || !isBootstrapAdmin(token = token, adminEmails = adminEmails)) {
        return user
    }
    // Grant ADMINISTRATOR and RESEARCHER together (#622): RESEARCHER is no longer a default sign-up
    // grant, so the bootstrap admin gets it explicitly here — matching the sign-up bootstrap path and
    // keeping a promoted operator's capabilities identical to a seeded one (PLAYER+RESEARCHER+ADMIN).
    capabilities.grant(userId = user.id, capability = Capability.ADMINISTRATOR)
    val granted = mutableSetOf(Capability.ADMINISTRATOR)
    if (Capability.RESEARCHER !in user.capabilities) {
        capabilities.grant(userId = user.id, capability = Capability.RESEARCHER)
        granted += Capability.RESEARCHER
    }
    logger.info { "Bootstrap allowlist: granted $granted to user ${user.id}" }
    return user.copy(capabilities = user.capabilities + granted)
}

/**
 * Allow looking a player up: [PLAYER_SEARCH_ROLES] (#867).
 *
 * **One gate for both search and id-resolution.** They were two functions over two different sets —
 * `requireResearchAccess` admitting RESEARCHER/RATER/HOST/ADMINISTRATOR and `requireStaff` admitting only
 * HOST/ADMINISTRATOR — which meant `findByIds` was strictly narrower than `search` for no stated reason,
 * even though a caller who can search a player by name can already read the same summary back. Merged
 * rather than kept in step by hand.
 */
private fun requirePlayerSearchAccess(
    repository: UserRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, Unit> {
    val caller = repository.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
    return if (caller == null || caller.capabilities.none { it in PLAYER_SEARCH_ROLES }) {
        ServiceError.Forbidden().left()
    } else {
        Unit.right()
    }
}

/**
 * Enforce invite-only manual onboarding (issue #74): a password/email-link token must have an open
 * invite for its email; OAuth (and the unrealistic no-email password token) is exempt. Returns the
 * gated, normalized email (null when the gate doesn't apply) so the caller can mark it accepted.
 */
private fun requireInviteForManualSignup(
    invites: InviteRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, String?> {
    val isManual = authProviderOf(signInProvider = token.signInProvider) == AuthProvider.PASSWORD
    val email = token.email?.revealed?.trim()?.lowercase()
    if (!isManual || email == null) return null.right()
    return if (invites.findOpenByEmail(email = email, asOf = LocalDateTime.now()) == null) {
        ServiceError.Forbidden(message = "An invitation is required to register $email").left()
    } else {
        email.right()
    }
}
