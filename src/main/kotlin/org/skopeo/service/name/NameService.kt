// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.name

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.Name
import org.skopeo.model.NameType
import org.skopeo.model.ServiceError
import org.skopeo.repository.NameRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDateTime
import java.util.UUID

/**
 * Manage a user's names. Names are append-only — added, then disabled rather than edited or
 * deleted — and every operation is self-or-ADMINISTRATOR. A user may hold many active names;
 * the display name is the single active name of type DISPLAY. Posting a new DISPLAY name
 * replaces the current one; the display name cannot be disabled (only replaced).
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class NameService(
    private val names: NameRepository = NameRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    fun list(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): Either<ServiceError, List<Name>> =
        either {
            requireUserExists(userId = userId).bind()
            requireUserAccess(token = token, userId = userId).bind()
            names.listByUser(userId = userId)
        }

    fun get(
        token: VerifiedFirebaseToken,
        userId: UUID,
        nameId: UUID,
    ): Either<ServiceError, Name> =
        either {
            val name = locate(userId = userId, nameId = nameId).bind()
            requireUserAccess(token = token, userId = userId).bind()
            name
        }

    /** Add a name. A DISPLAY name replaces the current display name (the old becomes history). */
    fun create(
        token: VerifiedFirebaseToken,
        userId: UUID,
        type: NameType,
        value: String,
    ): Either<ServiceError, Name> =
        either {
            requireUserExists(userId = userId).bind()
            val actor = requireUserAccess(token = token, userId = userId).bind()
            val name = names.create(userId = userId, type = type, value = value)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actor,
                        action = AuditAction.NAME_ADDED,
                        entityType = AuditEntityType.USER,
                        entityId = userId,
                        summary = "Added ${type.name} name '$value'",
                        details = mapOf("nameType" to type.name, "value" to value),
                    ),
            )
            name
        }

    /**
     * Enable or disable a name. The display name cannot be disabled (replace it by posting a
     * new DISPLAY name); re-enabling a former display name conflicts with the current one.
     */
    fun setActive(
        token: VerifiedFirebaseToken,
        userId: UUID,
        nameId: UUID,
        active: Boolean,
    ): Either<ServiceError, Name> =
        either {
            val target = locate(userId = userId, nameId = nameId).bind()
            val actor = requireUserAccess(token = token, userId = userId).bind()
            ensure(condition = active || target.type != NameType.DISPLAY) {
                ServiceError.Validation(message = "Cannot disable the display name; add a new display name to replace it")
            }
            val disabledAt = if (active) null else LocalDateTime.now()
            // locate() already proved the name exists; the repository still surfaces the display-name
            // uniqueness conflict when re-enabling a former display name.
            names.setActive(id = nameId, active = active, disabledAt = disabledAt).bind()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actor,
                        action = AuditAction.NAME_UPDATED,
                        entityType = AuditEntityType.USER,
                        entityId = userId,
                        summary = "${if (active) "Enabled" else "Disabled"} ${target.type.name} name '${target.value}'",
                        details = mapOf("nameId" to nameId.toString(), "active" to active.toString()),
                    ),
            )
            target.copy(isActive = active, disabledAt = disabledAt)
        }

    private fun locate(
        userId: UUID,
        nameId: UUID,
    ): Either<ServiceError, Name> =
        either {
            val name = names.findById(id = nameId).bind()
            ensure(condition = name.userId == userId) { ServiceError.NotFound(message = "Name $nameId not found") }
            name
        }

    private fun requireUserExists(userId: UUID): Either<ServiceError, Unit> = users.findById(id = userId).map { }

    /** Self-or-ADMINISTRATOR access; returns the caller's id (the audit actor). */
    private fun requireUserAccess(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isSelf = caller?.id == userId
        val isAdmin = caller?.capabilities?.contains(element = Capability.ADMINISTRATOR) == true
        return if (caller == null || (!isSelf && !isAdmin)) ServiceError.Forbidden().left() else caller.id.right()
    }
}
