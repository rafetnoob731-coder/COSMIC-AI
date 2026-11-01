package com.cosmic.ai.commands

import com.cosmic.ai.core.commands.CommandRegistry
import com.cosmic.ai.core.commands.CosmicCommand
import org.junit.Assert.*
import org.junit.Test

class CommandRegistryTest {
    @Test fun bugsRoot() {
        val c = CommandRegistry.parse("/bugs")
        assertTrue(c is CosmicCommand.OpenBugs)
    }

    @Test fun bugsNewCaseInsensitive() {
        val c = CommandRegistry.parse("  /BUGS New  ")
        assertTrue(c is CosmicCommand.OpenBugs)
        assertEquals("new", (c as CosmicCommand.OpenBugs).sub)
    }

    @Test fun notCommand() {
        assertNull(CommandRegistry.parse("fix a file"))
    }

    @Test fun unknown() {
        assertTrue(CommandRegistry.parse("/foobar") is CosmicCommand.Unknown)
    }
}
