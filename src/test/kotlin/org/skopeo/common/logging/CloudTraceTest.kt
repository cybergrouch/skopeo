// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CloudTraceTest {
    private val traceId = "105445aa7843bc8bf206b12000100000"

    @Test
    fun `builds the fully-qualified resource name Cloud Logging resolves`() {
        cloudTraceField(header = "$traceId/1;o=1", projectId = "skopeo-prod") shouldBe
            "projects/skopeo-prod/traces/$traceId"
    }

    @Test
    fun `a header with no span suffix still yields the trace`() {
        cloudTraceField(header = traceId, projectId = "skopeo-prod") shouldBe
            "projects/skopeo-prod/traces/$traceId"
    }

    @Test
    fun `no project means no field, since Cloud Logging ignores a bare trace id`() {
        // Local dev and tests: emitting an unqualified value would look like a link that goes nowhere.
        cloudTraceField(header = "$traceId/1", projectId = null).shouldBeNull()
        cloudTraceField(header = "$traceId/1", projectId = "").shouldBeNull()
    }

    @Test
    fun `no header means no field`() {
        cloudTraceField(header = null, projectId = "skopeo-prod").shouldBeNull()
        cloudTraceField(header = "", projectId = "skopeo-prod").shouldBeNull()
    }

    @Test
    fun `a malformed trace id is dropped rather than propagated`() {
        // Caller-supplied, so it can be anything. Too short, non-hex, and an empty leading segment.
        cloudTraceField(header = "abc/1", projectId = "skopeo-prod").shouldBeNull()
        cloudTraceField(header = "zzzz5aa7843bc8bf206b12000100000z/1", projectId = "skopeo-prod").shouldBeNull()
        cloudTraceField(header = "/1;o=1", projectId = "skopeo-prod").shouldBeNull()
    }
}
