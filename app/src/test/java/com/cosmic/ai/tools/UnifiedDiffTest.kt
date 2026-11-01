package com.cosmic.ai.tools

import com.cosmic.ai.tools.filesystem.unifiedDiff
import org.junit.Assert.*
import org.junit.Test

class UnifiedDiffTest {
    @Test
    fun noChange() {
        val d = unifiedDiff("a.kt", "hello", "hello")
        assertTrue(d.contains("No changes"))
    }

    @Test
    fun detectsAddition() {
        val d = unifiedDiff("a.kt", "a\n", "a\nb\n")
        assertTrue(d.contains("+"))
    }
}
