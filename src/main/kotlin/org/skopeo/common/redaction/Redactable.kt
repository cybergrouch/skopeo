// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.redaction

import kotlinx.serialization.Serializable

/**
 * A value that must never appear in a log line, an error report, or anything else built from
 * `toString()` (#801).
 *
 * Covers personal data *and* credentials — `IssuedApiKey.plaintext` is a working API key rather than PII,
 * and the disclosure risk is identical, so the type is named for what it does (gets redacted) rather than
 * for one class of thing it happens to hold.
 *
 * **Why a wrapper type rather than a `toString()` override per model.** Kotlin generates a data class's
 * `toString()` from its primary constructor properties, and that generated method calls `toString()` on
 * each field. So making the *field's type* redact makes every containing model safe automatically — with
 * no per-model code, and no way to forget when a new model is added. Overriding `toString()` on each of
 * the ~29 PII-carrying classes would work too, but it is ongoing boilerplate that fails silently the
 * moment someone adds a class and doesn't know the convention.
 *
 * **What it deliberately does not do.** It stops `"$user"`. It does not stop `"${user.dateOfBirth.revealed}"`
 * — reading the value out and logging it directly. Nothing type-level can, and that is what #806's
 * clean-sources rule and `PiiLeakTest` are for. This is layer 2: it removes the *accidental* leak, which
 * is the one that actually happens.
 *
 * **No wire impact, and this is verified rather than assumed.** The class is `@Serializable`, and kotlinx
 * serialises a `@JvmInline value class` *transparently as its wrapped value* — so a wrapped field emits
 * exactly the JSON the raw field did, with no custom serializer and no `revealed` key. That was probed by
 * wrapping a real DTO field and asserting the emitted JSON was byte-identical (#822).
 *
 * The practical consequence: DTOs **can** be wrapped when there is a reason to. They are not, yet — no
 * DTO or entity is currently interpolated into any log line, so the exposure is prospective. The
 * annotation is here so that decision stays cheap rather than blocked.
 *
 * A `value class`, so there is no allocation: at runtime this is the underlying reference.
 */
@Serializable
@JvmInline
value class Redactable<out T : Any>(
    /**
     * The wrapped value.
     *
     * Named `revealed` rather than `value` for two reasons. It reads as an intent at the call site —
     * `contact.value.revealed` says "I am deliberately exposing this", where `contact.value.value` says
     * nothing. And it is greppable: `\.revealed` enumerates every place a protected value is exposed,
     * which is exactly the list a reviewer wants.
     */
    val revealed: T,
) {
    /**
     * Explicitly overridden, and it has to be: a `value class` otherwise inherits a generated
     * `toString()` that prints the wrapped value — which would silently defeat the entire point.
     */
    override fun toString(): String = REDACTED

    companion object {
        /** Deliberately carries no type or length hint: those are themselves information. */
        const val REDACTED: String = "***"
    }
}

/**
 * View a value as [Redactable]. **The value itself is unchanged** — this only tags it so that rendering
 * the *container* cannot print it.
 *
 * Named `as…`, following `asSequence`/`asIterable`, precisely because `redacted()` read like a
 * transformation: at a call site whose purpose is to hand the value to a caller, "redact this" is
 * exactly the wrong thing to suggest. Nothing is destroyed; [Redactable.revealed] returns the original.
 */
fun <T : Any> T.asRedactable(): Redactable<T> = Redactable(revealed = this)
