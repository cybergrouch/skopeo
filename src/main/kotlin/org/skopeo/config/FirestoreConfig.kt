// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.skopeo.domain.service.livematch.LiveScoreBroadcaster
import org.skopeo.domain.service.livematch.LiveScorePayload
import org.skopeo.domain.service.livematch.NoOpLiveScoreBroadcaster

/**
 * The Firestore collection holding one live-score document per match (#911).
 *
 * Documents are keyed by the match's **public code**, not its internal id: the collection is
 * world-readable, and the internal id is withheld from ordinary viewers precisely so it is not an
 * alternative public identifier. The code is also the only identifier a spectator has.
 */
const val LIVE_SCORES_COLLECTION = "liveScores"

/**
 * Wire up the spectator broadcast, or don't (#911 §3).
 *
 * **Absent credentials is a supported state, not a failure.** Local development and CI have no Firestore
 * project, and live scoring must work fully without one — spectators simply have nothing to watch. So
 * this returns [NoOpLiveScoreBroadcaster] rather than throwing, and says so once at startup.
 *
 * On Cloud Run the credentials come from the runtime service account via Application Default
 * Credentials, so there is no key file to mount or rotate. That is the main reason ADC is used here in
 * preference to an explicit service-account JSON: a key that never exists cannot leak.
 */
fun liveScoreBroadcaster(projectId: String?): LiveScoreBroadcaster = LiveScoreBroadcasters.forProject(projectId = projectId)

/** Holds the logger as a member rather than a top-level property, which the naming rule reserves for constants. */
private object LiveScoreBroadcasters {
    private val logger = KotlinLogging.logger {}

    fun forProject(projectId: String?): LiveScoreBroadcaster {
        if (projectId.isNullOrBlank()) {
            logger.info { "Firestore project id not configured — live scores will not be broadcast" }
            return NoOpLiveScoreBroadcaster
        }
        return runCatching { FirestoreLiveScoreBroadcaster(firestore = firestoreFor(projectId = projectId)) }
            .getOrElse { error ->
                // A deployment without usable credentials should still serve every other request. Umpires
                // can score; only the spectator projection is missing, and it is loudly missing rather
                // than silently half-working.
                logger.warn(throwable = error) { "Firestore unavailable — live scores will not be broadcast" }
                NoOpLiveScoreBroadcaster
            }
    }
}

private fun firestoreFor(projectId: String): Firestore {
    val existing = FirebaseApp.getApps().firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
    val app =
        existing ?: FirebaseApp.initializeApp(
            FirebaseOptions
                .builder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setProjectId(projectId)
                .build(),
        )
    return FirestoreClient.getFirestore(app)
}

/**
 * Writes the current score to `liveScores/{matchId}`, overwriting whatever was there.
 *
 * **Overwrite, not append.** Spectators receive state rather than keystrokes (§6), so there is exactly
 * one document per match and reconnecting is a plain read with no gap to fill.
 *
 * **Never throws.** The umpire's write has already been committed to Postgres by the time this runs, and
 * failing the request because a projection could not be pushed would trade a recorded point for a stale
 * scoreboard — the wrong way round. Failures are logged and swallowed, which is the contract
 * [LiveScoreBroadcaster] states.
 */
internal class FirestoreLiveScoreBroadcaster(private val firestore: Firestore) : LiveScoreBroadcaster {
    private val logger = KotlinLogging.logger {}

    override fun publish(payload: LiveScorePayload) {
        runCatching {
            firestore
                .collection(LIVE_SCORES_COLLECTION)
                .document(payload.publicCode)
                .set(payload.asDocument())
        }.onFailure { error ->
            logger.warn(throwable = error) { "Could not broadcast the live score for match ${payload.publicCode}" }
        }
    }
}
