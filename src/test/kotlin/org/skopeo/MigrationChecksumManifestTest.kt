// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.skopeo.testsupport.PostgresTestDatabase

/** Where the committed manifest lives, as a classpath resource. */
private const val MANIFEST_RESOURCE = "db/migration-checksums.txt"

/**
 * Guards the rule `CLAUDE.md` states and nothing enforced: **never edit a migration that has shipped**
 * (#854).
 *
 * Flyway detects a rewritten migration only at startup, against a database that already applied the old
 * version. CI migrates a fresh, empty container — which by definition has applied nothing — so an edited
 * migration validates perfectly here and fails only when it meets a real database. That is exactly how
 * `V44__events_require_club.sql` came to be rewritten after shipping, and how a local database ended up
 * refusing to boot on a checksum mismatch.
 *
 * This moves the detection to commit time by comparing Flyway's own checksums against a committed
 * manifest. Flyway's CRC32 is used rather than a hash of the file bytes deliberately: the manifest is then
 * the same number a database records, so `SELECT version, checksum FROM flyway_schema_history` against any
 * environment says directly whether it is stale.
 *
 * Reuses the shared container ([PostgresTestDatabase]) rather than starting another — the migrations have
 * already run there, so this test is a read.
 */
class MigrationChecksumManifestTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setUp() {
            PostgresTestDatabase.start()
        }
    }

    @Test
    fun `every shipped migration matches its committed checksum`() {
        val applied = appliedChecksums()
        val committed = committedManifest()

        // One assertion over the whole map, not per-entry: a diff of both sides is far more useful than
        // "expected 2112154510" with no indication of which versions drifted.
        withClue(applied = applied, committed = committed) {
            applied shouldBe committed
        }
    }

    /** Version → Flyway's CRC32, for every successfully applied versioned migration, in order. */
    private fun appliedChecksums(): Map<String, Int> =
        transaction {
            exec(
                stmt =
                    """
                    SELECT version, checksum
                      FROM flyway_schema_history
                     WHERE success AND version IS NOT NULL AND checksum IS NOT NULL
                     ORDER BY installed_rank
                    """.trimIndent(),
            ) { rs ->
                buildMap {
                    while (rs.next()) {
                        put(key = rs.getString(1), value = rs.getInt(2))
                    }
                }
            }
        }.orEmpty()

    private fun committedManifest(): Map<String, Int> {
        val text =
            requireNotNull(value = javaClass.classLoader.getResourceAsStream(MANIFEST_RESOURCE)) {
                "$MANIFEST_RESOURCE is missing. ${REGENERATE_HINT}"
            }.bufferedReader().readText()
        return text
            .lineSequence()
            .map { it.substringBefore(delimiter = '#').trim() }
            .filter { it.isNotEmpty() }
            .associate { line ->
                val parts = line.split(regex = Regex(pattern = "\\s+"))
                require(value = parts.size == 2) { "Malformed manifest line: '$line'. Expected '<version> <checksum>'." }
                parts[0] to (parts[1].toIntOrNull() ?: error(message = "Non-numeric checksum in manifest line: '$line'"))
            }
    }
}

private val REGENERATE_HINT =
    """
    To regenerate deliberately, run this test and copy the "applied" side of the diff into
    src/main/resources/db/migration-checksums.txt (one "<version> <checksum>" per line).
    """.trimIndent()

/**
 * Turn a bare map mismatch into an actionable message. Which way the diff points decides the advice:
 * a **changed** entry means a shipped migration was edited (add a new one instead); a **new** entry just
 * means the manifest needs the deliberate line.
 */
private inline fun withClue(
    applied: Map<String, Int>,
    committed: Map<String, Int>,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: AssertionError) {
        val changed = applied.filter { (version, checksum) -> committed[version]?.let { it != checksum } == true }
        val added = applied.keys - committed.keys
        val removed = committed.keys - applied.keys
        val detail =
            buildString {
                if (changed.isNotEmpty()) {
                    appendLine(
                        value =
                            "EDITED AFTER SHIPPING — ${changed.keys.sorted()}. A migration that has been applied " +
                                "anywhere must not change: every database holding the old checksum will refuse to boot. " +
                                "Add a NEW V<n> migration instead of editing these.",
                    )
                    changed.forEach { (version, checksum) ->
                        appendLine(value = "  V$version: committed ${committed[version]} -> now $checksum")
                    }
                }
                if (added.isNotEmpty()) {
                    appendLine(value = "NEW migrations not in the manifest — ${added.sorted()}. Adding one is fine; record it:")
                    added.sorted().forEach { appendLine(value = "  $it ${applied[it]}") }
                }
                if (removed.isNotEmpty()) {
                    appendLine(value = "MISSING from the migration set but present in the manifest — ${removed.sorted()}.")
                }
                appendLine()
                appendLine(value = REGENERATE_HINT)
                appendLine()
                appendLine(value = "Full applied set:")
                applied.forEach { (version, checksum) -> appendLine(value = "$version $checksum") }
            }
        // kotest's fail() rather than AssertionError(message, cause): the latter is a Java constructor, so
        // its arguments cannot be named and detekt's NamedArguments rule rejects the call. The cause adds
        // nothing here anyway — the original message is already the first line of what is thrown.
        fail(msg = "${e.message}\n\n$detail")
    }
}
