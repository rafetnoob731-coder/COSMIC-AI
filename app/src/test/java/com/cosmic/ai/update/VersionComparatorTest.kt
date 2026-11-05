package com.cosmic.ai.update

import com.cosmic.ai.core.update.UpdateDecision
import com.cosmic.ai.core.update.UpdateManifest
import com.cosmic.ai.core.update.VersionComparator
import org.junit.Assert.*
import org.junit.Test

class VersionComparatorTest {
    private fun m(
        code: Int = 40,
        min: Int = 30,
        required: Boolean = false,
        enabled: Boolean = true,
        appId: String = "com.cosmic.ai"
    ) = UpdateManifest(
        appId = appId,
        latestVersion = "0.4.0",
        versionCode = code,
        minimumSupportedVersion = min,
        updateRequired = required,
        releaseName = "r",
        releaseNotes = listOf("n"),
        downloadUrl = "https://example.com/a.apk",
        sha256 = "abc",
        publishedAt = "2026-01-01T00:00:00Z",
        enabled = enabled
    )

    @Test fun upToDate() {
        val e = VersionComparator.evaluate(m(code = 10, min = 0), 10, "0.1", "com.cosmic.ai")
        assertEquals(UpdateDecision.UP_TO_DATE, e.decision)
    }

    @Test fun optional() {
        val e = VersionComparator.evaluate(m(code = 40, required = false), 30, "0.3", "com.cosmic.ai")
        assertEquals(UpdateDecision.OPTIONAL, e.decision)
    }

    @Test fun mandatoryFlag() {
        val e = VersionComparator.evaluate(m(code = 40, required = true), 30, "0.3", "com.cosmic.ai")
        assertEquals(UpdateDecision.MANDATORY, e.decision)
    }

    @Test fun belowMinimum() {
        val e = VersionComparator.evaluate(m(code = 40, min = 35), 30, "0.3", "com.cosmic.ai")
        assertEquals(UpdateDecision.MANDATORY, e.decision)
    }

    @Test fun appIdMismatch() {
        val e = VersionComparator.evaluate(m(appId = "other"), 1, "0.1", "com.cosmic.ai")
        assertEquals(UpdateDecision.INVALID_MANIFEST, e.decision)
    }

    @Test fun disabledRelease() {
        val e = VersionComparator.evaluate(m(enabled = false), 1, "0.1", "com.cosmic.ai")
        assertEquals(UpdateDecision.BLOCKED_DISABLED_RELEASE, e.decision)
    }

    @Test fun networkError() {
        val e = VersionComparator.evaluate(null, 1, "0.1", "com.cosmic.ai", "timeout")
        assertEquals(UpdateDecision.NETWORK_ERROR, e.decision)
    }
}
