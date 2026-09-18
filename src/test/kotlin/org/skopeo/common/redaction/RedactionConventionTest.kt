// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.redaction

import dev.zacsweers.redacted.annotations.Redacted
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Fails the build when a sensitive-looking field is not marked `@Redacted` (#822/#825).
 *
 * The redacted compiler plugin (#825) rewrites a data class's generated `toString()` so an annotated
 * property renders as `***`. Nothing makes anyone *apply* the annotation — so without this test the
 * build stays green when someone adds `val phoneNumber: String` to a domain model, and the protection
 * quietly becomes a snapshot of whatever happened to be annotated once.
 *
 * Retargeted from the wrapper it replaced: the rule is unchanged, only the thing it looks for. Where it
 * used to require the declared TYPE to be `Redactable<…>`, it now requires the ANNOTATION — which is
 * the whole point of the swap, since the field keeps its raw type and every call site loses its
 * `.asRedactable()` / `.revealed` ceremony.
 *
 * **This is a name-based scan, and that limitation is real:** `val e: String` slips through. It catches
 * carelessness, not evasion. The stronger alternative — instantiating every data class reflectively and
 * asserting `toString()` hides sentinel values — was rejected for now because `User` alone needs ~20
 * synthesised constructor arguments and every new required field breaks the synthesiser. See #822.
 *
 * The [ALLOWED] escape hatch is deliberate: adding an entry forces a written reason, the same property
 * that makes #806's MDC allowlist work.
 */
class RedactionConventionTest {
    private companion object {
        /**
         * Field names that should hold a credential or personal data. Deliberately conservative — a name
         * that is *sometimes* innocuous (`name`, `value`, `uid`) is excluded, because a scan that cries
         * wolf gets suppressed and then protects nothing.
         */
        val SENSITIVE_NAMES =
            setOf(
                "email", "emailAddress", "phone", "phoneNumber", "dateOfBirth", "dob",
                "plaintext", "secret", "password", "apiKey", "keyHash", "token", "accessToken",
                "refreshToken", "providerUid", "firebaseUid", "ssn", "taxId",
            )

        /**
         * Packages in scope: the domain model and the services.
         *
         * `common/dto` and `repository/persistence` are deliberately **out** of scope, and that is a
         * decision rather than an oversight. Both are boundary record types whose whole purpose is to
         * carry the value across a boundary — DTOs are `@Serializable`, so wrapping a field there needs a
         * custom serializer, and entities are raw row shapes, so wrapping needs mapper changes on both
         * sides. Neither question is settled (see #822), and allowlisting ~19 individual fields would
         * bury the real signal in noise.
         *
         * The consequence, stated plainly: a new DTO or entity field holding an email is NOT caught by
         * this test. The domain model and services are where a whole object actually gets interpolated
         * into a log line, which is why they are the ones guarded.
         */
        val SCANNED_PACKAGES = listOf("domain/model", "domain/service")

        /**
         * Deliberate exclusions, each with the reason it is not a defect. Keyed `Type.field`.
         *
         * Adding an entry is the point: it costs a sentence, which is exactly the friction that makes an
         * allowlist better than a blocklist.
         */
        val ALLOWED: Map<String, String> =
            mapOf(
                // The opaque provider subject IS the repository's join key, looked up by value.
                "UserIdentity.providerUid" to "join key the repository looks up by (see #801)",
                // Sign-up input, wrapped one hop later when it becomes a ContactInfo.
                "ProvisionUserCommand.phone" to "request input; becomes a wrapped ContactInfo (see #801)",
                // The field's TYPE is ContactInfo, whose `value` is already Redactable — protected
                // transitively, so the raw-type match here is a false positive.
                "ProvisionUserCommand.email" to "type is ContactInfo, whose value is already wrapped",
                // A SHA-256 hash is not a credential — that is the point of storing the hash. Knowing it
                // does not authenticate anything, and the guard should not cry wolf over it.
                "InsertApiKeyCommand.keyHash" to "a hash, not the secret; not usable to authenticate",
            )

        /** `val <name>: <Type>` — enough to spot a raw declaration without parsing Kotlin. */
        val FIELD = Regex(pattern = """^\s*(?:@\w+\s+)*val\s+(\w+)\s*:\s*([\w<>?.]+)""")
        val DATA_CLASS = Regex(pattern = """^\s*(?:internal\s+|private\s+)?data class (\w+)""")
    }

    private data class Finding(val type: String, val field: String, val declared: String, val file: String)

    private fun sourceRoot(): File {
        // Resolved by walking up rather than assuming a working directory, so this passes whether Gradle
        // runs it from the project root or a module directory.
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "src/main/kotlin").isDirectory) {
            dir = dir.parentFile
        }
        return File(dir, "src/main/kotlin/org/skopeo")
    }

    private fun scan(): List<Finding> =
        SCANNED_PACKAGES.flatMap { pkg ->
            File(sourceRoot(), pkg)
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { scanFile(file = it) }
                .toList()
        }

    /** One file: track which data class each line belongs to, and test each field declaration. */
    private fun scanFile(file: File): List<Finding> {
        val findings = mutableListOf<Finding>()
        var current: String? = null
        // `@Redacted` sits on its own line above the property far more often than inline, so the scan
        // has to carry it forward one line. Reset on every line that is not an annotation, or an
        // annotation three fields up would vouch for a field that has none.
        var annotated = false
        file.readLines().forEach { line ->
            DATA_CLASS.find(input = line)?.let { current = it.groupValues[1] }
            val trimmed = line.trim()
            if (trimmed == "@Redacted") {
                annotated = true
                return@forEach
            }
            val type = current
            if (type != null && !annotated) {
                violationIn(line = line, type = type, fileName = file.name)?.let { findings.add(element = it) }
            }
            if (trimmed.isNotEmpty() && !trimmed.startsWith(prefix = "@") && !trimmed.startsWith(prefix = "//")) {
                annotated = false
            }
        }
        return findings
    }

    /**
     * The whole rule, in one place: a sensitive-looking name, declared with a raw type, not allowlisted.
     */
    private fun violationIn(
        line: String,
        type: String,
        fileName: String,
    ): Finding? {
        val match = FIELD.find(input = line) ?: return null
        val name = match.groupValues[1]
        val declared = match.groupValues[2]
        // Inline `@Redacted val email: String` is handled here; the on-its-own-line form is handled by
        // the caller, which is the shape the migration in #825 actually produced.
        val exempt =
            name !in SENSITIVE_NAMES ||
                line.contains(other = "@Redacted") ||
                "$type.$name" in ALLOWED
        return if (exempt) null else Finding(type = type, field = name, declared = declared, file = fileName)
    }

    @Test
    fun `the scanner actually matches declarations, so a clean result means something`() {
        // A scan that matches nothing proves nothing. Assert it can see a field it is meant to see —
        // here, one of the known allowlisted ones, found by disabling the allowlist check.
        val root = sourceRoot()
        val seen =
            File(root, "domain/model").walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { f -> f.readLines().asSequence().mapNotNull { FIELD.find(input = it) } }
                .map { it.groupValues[1] }
                .toSet()

        seen shouldContain "dateOfBirth"
        (seen.size > 50) shouldBe true
    }

    @Test
    fun `no sensitive field is declared with a raw type outside the allowlist`() {
        val findings = scan()

        // The message has to name the fix, or whoever hits this has to go archaeology-hunting.
        if (findings.isNotEmpty()) {
            val detail =
                findings.joinToString(separator = "\n") {
                    "  ${it.type}.${it.field}: ${it.declared}   (${it.file})"
                }
            println(
                message =
                    "Sensitive fields declared with a raw type (#822):\n$detail\n\n" +
                        "Either annotate the field @Redacted (see LOGGING_AND_METRICS.md), or add an " +
                        "entry to RedactionConventionTest.ALLOWED with the reason it is not a defect.",
            )
        }

        findings.shouldBeEmpty()
    }

    // ---- The usage scan is GONE, and that is the headline of #825 ------------------------------
    //
    // It used to fail the build on `.toString()` applied to a wrapped field, because on a Redactable
    // that yielded "***" — two DTO mappers had exactly that bug and would have shipped a redacted
    // placeholder to clients as a date of birth. It compiled cleanly, since `toString()` exists on
    // everything.
    //
    // That hazard cannot recur. The field now keeps its raw type and only the ENCLOSING data class's
    // generated `toString()` is rewritten, so `dateOfBirth?.toString()` returns the date, exactly as a
    // reader expects. A guard for a bug the design no longer permits is noise, so it is deleted rather
    // than retargeted.
    //
    // The trade runs the other way, and is recorded honestly: `"${user.dateOfBirth}"` in a log line
    // used to print "***" by accident, because the wrapper's own toString redacted. It now prints the
    // date. Field-level interpolation was never in scope — Redactable's own KDoc said so — but the
    // wrapper did cover it incidentally, and that cover is gone. `PiiLeakTest` (#806) is the layer that
    // catches it, behaviourally: it drives real flows with canary values and asserts no log line
    // contains them, which is independent of how redaction is implemented.

    @Test
    fun `the plugin is wired, and it masks with the same string the wrapper used`() {
        // Cheap, but it is the assertion that would catch the plugin silently not applying — a compiler
        // plugin that fails to run leaves code that compiles and tests that pass, with the protection
        // simply absent. Asserting the MASK as well as the masking pins `replacementString = "***"` in
        // build.gradle.kts, so a default-valued upgrade to "██" fails here rather than in a log review.
        val probe = Probe(harmless = "visible", secret = "s3cret-canary")

        probe.toString() shouldContain "visible"
        probe.toString() shouldNotContain "s3cret-canary"
        probe.toString() shouldContain "***"
        // The field itself is untouched: this is what makes the old stringification bug impossible.
        probe.secret shouldBe "s3cret-canary"
    }

    private data class Probe(
        val harmless: String,
        @Redacted val secret: String,
    )

    @Test
    fun `every allowlist entry carries a reason`() {
        // An allowlist whose entries have no justification is a blocklist with extra steps.
        ALLOWED.forEach { (key, reason) ->
            withClue(clue = key) { (reason.length > 20) shouldBe true }
        }
    }
}
