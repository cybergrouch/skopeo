// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

/**
 * How long a match has actually been played (#937).
 *
 * Lives in `model` rather than beside the fold that produces it because the **dto mapper** needs it, and
 * `mapper` may depend on `dto` and `model` only — never on `service`. The fold itself stays in
 * `service`, where it can read persistence entities.
 *
 * @property elapsedSeconds playing time so far, **excluding** every suspension.
 * @property isRunning whether the clock is currently advancing — false before the start, while paused,
 *  and once the match has ended. A client ticks locally while this is true and stops when it is not.
 */
data class MatchTiming(
    val elapsedSeconds: Long,
    val isRunning: Boolean,
)
