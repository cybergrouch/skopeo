// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.name

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.skopeo.dto.name.NameCreateRequest
import org.skopeo.model.Capability
import org.skopeo.model.Name
import org.skopeo.model.NameType
import org.skopeo.repository.NameRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.UserNotFoundException
import org.skopeo.service.user.VerifiedFirebaseToken
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.UUID

private const val PG_UNIQUE_VIOLATION = "23505"

/**
 * Manage a user's names. Names are append-only — added, then disabled rather than edited or
 * deleted — and every operation is self-or-ADMINISTRATOR. A user may hold many active names;
 * the display name is the single active name of type DISPLAY. Posting a new DISPLAY name
 * replaces the current one; the display name cannot be disabled (only replaced).
 */
class NameService(
    private val names: NameRepository = NameRepository(),
    private val users: UserRepository = UserRepository(),
) {
    fun list(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): List<Name> {
        requireUserExists(userId = userId)
        requireUserAccess(token = token, userId = userId)
        return names.listByUser(userId = userId)
    }

    fun get(
        token: VerifiedFirebaseToken,
        userId: UUID,
        nameId: UUID,
    ): Name {
        val name = locate(userId = userId, nameId = nameId)
        requireUserAccess(token = token, userId = userId)
        return name
    }

    /** Add a name. A DISPLAY name replaces the current display name (the old becomes history). */
    fun create(
        token: VerifiedFirebaseToken,
        userId: UUID,
        request: NameCreateRequest,
    ): Name {
        requireUserExists(userId = userId)
        requireUserAccess(token = token, userId = userId)
        val type = parseType(value = request.type)
        return names.create(userId = userId, type = type, value = request.value)
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
    ): Name {
        val target = locate(userId = userId, nameId = nameId)
        requireUserAccess(token = token, userId = userId)
        require(value = active || target.type != NameType.DISPLAY) {
            "Cannot disable the display name; add a new display name to replace it"
        }
        val disabledAt = if (active) null else LocalDateTime.now()
        return conflictAware {
            names.setActive(id = nameId, active = active, disabledAt = disabledAt)
        } ?: throw NameNotFoundException(id = nameId)
    }

    private fun locate(
        userId: UUID,
        nameId: UUID,
    ): Name {
        val name = names.findById(id = nameId)
        if (name == null || name.userId != userId) throw NameNotFoundException(id = nameId)
        return name
    }

    private fun requireUserExists(userId: UUID) {
        users.findById(id = userId) ?: throw UserNotFoundException(id = userId)
    }

    private fun requireUserAccess(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ) {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isSelf = caller?.id == userId
        val isAdmin = caller?.capabilities?.contains(element = Capability.ADMINISTRATOR) == true
        if (!isSelf && !isAdmin) throw ForbiddenException()
    }

    private fun parseType(value: String): NameType =
        try {
            NameType.valueOf(value = value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid name type '$value'", e)
        }

    private fun <T> conflictAware(block: () -> T): T =
        try {
            block()
        } catch (e: ExposedSQLException) {
            if (isUniqueViolation(e = e)) {
                throw NameConflictException(message = "A different active display name already exists")
            } else {
                throw e
            }
        }

    private fun isUniqueViolation(e: ExposedSQLException): Boolean =
        generateSequence<Throwable>(seed = e) { it.cause }.any { (it as? SQLException)?.sqlState == PG_UNIQUE_VIOLATION }
}
