package com.cosmic.ai.update

import org.junit.Assert.*
import org.junit.Test

class UpdateRepositoryParseTest {
    @Test fun parsesManifest() {
        val json = """
        {
          "app_id": "com.cosmic.ai",
          "latest_version": "0.4.0",
          "version_code": 40,
          "minimum_supported_version": 30,
          "update_required": true,
          "release_name": "COSMIC AI v0.4",
          "release_notes": ["A", "B"],
          "download_url": "https://example.com/x.apk",
          "sha256": "DEADBEEF",
          "published_at": "2026-11-01T12:00:00Z"
        }
        """.trimIndent()
        val m = UpdateRepository.parse(json)
        assertEquals("com.cosmic.ai", m.appId)
        assertEquals(40, m.versionCode)
        assertTrue(m.updateRequired)
        assertEquals(2, m.releaseNotes.size)
        assertEquals("deadbeef", m.sha256)
    }
}
