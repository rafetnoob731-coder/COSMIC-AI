package com.cosmic.ai.ui.screens.projects

import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlPreviewSecurityTest {
    @Test fun previewAddsRestrictiveContentSecurityPolicyToExistingHead() {
        val output = securePreviewHtml("<html><head><title>Demo</title></head><body>ok</body></html>")
        assertTrue(output.contains("connect-src 'none'"))
        assertTrue(output.contains("object-src 'none'"))
        assertTrue(output.contains("form-action 'none'"))
        assertTrue(output.contains("name=\"viewport\""))
        assertTrue(output.contains("<title>Demo</title>"))
    }

    @Test fun previewWrapsHeadlessHtmlWithoutDroppingBodyContent() {
        val output = securePreviewHtml("<h1>Local preview</h1><script>console.log('ready')</script>")
        assertTrue(output.startsWith("<!doctype html>"))
        assertTrue(output.contains("Local preview"))
        assertTrue(output.contains("script-src 'self' 'unsafe-inline'"))
    }
}
