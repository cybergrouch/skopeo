// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.security

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The role sets, and the drift that made #866 necessary.
 *
 * `{HOST, CLUB_OWNER, ADMINISTRATOR}` had been declared **seven times under six names**, and `STAFF_ROLES`
 * named two *different* sets in different services — same word, different authorization, every copy
 * `private` so nothing forced them to agree. Reviewing for that is exactly the kind of thing reviewers do
 * not catch, so it is asserted here instead.
 */
class CapabilityRolesTest {
    @Test
    fun `match management is host, club owner and administrator`() {
        MATCH_MANAGEMENT_ROLES shouldContainExactlyInAnyOrder
            listOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)
    }

    @Test
    fun `club owner or admin excludes host`() {
        // A host is not exempt from the event-expiry gate (#310); that is the point of this set being
        // narrower than match management.
        CLUB_OWNER_OR_ADMIN shouldContainExactlyInAnyOrder listOf(Capability.CLUB_OWNER, Capability.ADMINISTRATOR)
    }

    @Test
    fun `email view is match management plus raters, and never points managers`() {
        EMAIL_VIEW_ROLES shouldContainExactlyInAnyOrder
            listOf(Capability.HOST, Capability.CLUB_OWNER, Capability.RATER, Capability.ADMINISTRATOR)
        // Managing points is no reason to see somebody's email address (#630/#865).
        EMAIL_VIEW_ROLES.contains(element = Capability.POINTS_MANAGER) shouldBe false
    }

    @Test
    fun `points view is email view plus points managers`() {
        PLAYER_POINTS_VIEW_ROLES shouldContainExactlyInAnyOrder
            listOf(
                Capability.HOST,
                Capability.CLUB_OWNER,
                Capability.RATER,
                Capability.POINTS_MANAGER,
                Capability.ADMINISTRATOR,
            )
    }

    @Test
    fun `the two view sets differ by exactly points manager`() {
        // Pins the relationship rather than the members: if match management gains a role, both sets gain
        // it and this still holds — which is the reason they are composed rather than re-listed.
        (PLAYER_POINTS_VIEW_ROLES - EMAIL_VIEW_ROLES) shouldBe setOf(element = Capability.POINTS_MANAGER)
        (EMAIL_VIEW_ROLES - PLAYER_POINTS_VIEW_ROLES).isEmpty() shouldBe true
    }

    @Test
    fun `both view sets are supersets of match management`() {
        MATCH_MANAGEMENT_ROLES.all { it in EMAIL_VIEW_ROLES } shouldBe true
        MATCH_MANAGEMENT_ROLES.all { it in PLAYER_POINTS_VIEW_ROLES } shouldBe true
    }

    @Test
    fun `host-or-admin deliberately omits club owner, pending #867`() {
        // NOT the same set as match management, and not quietly made so: a CLUB_OWNER without HOST can
        // reach the New Event form (#789) but gets 403 from the player search this gates. Whether that is
        // deliberate is tracked in #867; until it is answered the discrepancy stays visible here.
        HOST_OR_ADMIN shouldContainExactlyInAnyOrder listOf(Capability.HOST, Capability.ADMINISTRATOR)
        HOST_OR_ADMIN.contains(element = Capability.CLUB_OWNER) shouldBe false
        (MATCH_MANAGEMENT_ROLES - HOST_OR_ADMIN) shouldBe setOf(element = Capability.CLUB_OWNER)
    }

    @Test
    fun `no service declares its own capability set instead of reusing these`() {
        // The drift came from named private copies, so that is precisely what this catches: a
        // SCREAMING_CASE `val` built from a fresh `setOf(Capability…, …)` inside a service.
        //
        // An alias would pass this check, but there are none — aliasing was tried and reverted, because
        // `private val STAFF_ROLES = MATCH_MANAGEMENT_ROLES` in one service beside
        // `private val STAFF_ROLES = HOST_OR_ADMIN` in another preserved the very collision being removed.
        //
        // Deliberately NOT a ban on every inline `setOf(Capability…)`. `UserService`'s function-local
        // `searchRoles = setOf(RESEARCHER, RATER)` is used once and duplicated nowhere, and hoisting it to
        // a shared constant would make it look like a policy shared across services when it is not. The
        // failure mode being guarded is six names for one set, not the existence of set literals.
        val offenders =
            serviceSources()
                .filter { (_, source) -> DECLARES_OWN_SET.containsMatchIn(input = source) }
                .map { (name, _) -> name }

        offenders shouldBe emptyList()
    }

    @Test
    fun `the guard above would actually catch a fresh declaration`() {
        // A guard that cannot fail is worse than none. This is the shape it is looking for.
        DECLARES_OWN_SET.containsMatchIn(
            input = "private val SOME_ROLES = setOf(Capability.HOST, Capability.ADMINISTRATOR)",
        ) shouldBe true
        // ...and an alias must NOT trip it.
        DECLARES_OWN_SET.containsMatchIn(input = "private val X_ROLES = MATCH_MANAGEMENT_ROLES") shouldBe false
    }

    /** Every Kotlin source under `domain/service`, as (file name, contents). */
    private fun servicesRoot() = java.io.File("src/main/kotlin/org/skopeo/domain/service")

    private fun serviceSources(): List<Pair<String, String>> {
        val root = servicesRoot()
        // Guard the guard: a wrong path would silently find nothing and pass.
        root.isDirectory shouldBe true
        val sources =
            root
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(suffix = ".kt") }
                .map { it.name to it.readText() }
                .toList()
        // A wrong path would find nothing and pass silently, so assert there is something to scan.
        sources.isNotEmpty() shouldBe true
        return sources
    }

    private companion object {
        /**
         * A service declaring its own multi-capability set, e.g.
         * `private val X = setOf(Capability.HOST, Capability.ADMINISTRATOR)`.
         *
         * Two or more `Capability.` members is the signal — a single-capability `setOf(Capability.X)` is a
         * one-role check, not a role *set*, and inlining that is clearer than naming it.
         */
        val DECLARES_OWN_SET =
            Regex(pattern = """val\s+[A-Z_]+\s*=\s*setOf\((?:\s*element\s*=\s*)?Capability\.[A-Z_]+\s*,""")
    }
}
