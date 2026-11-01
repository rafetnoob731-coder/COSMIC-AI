// language: kotlin, file: CommandRegistry.kt, runtime: pure Kotlin, target: slash commands
package com.cosmic.ai.core.commands

sealed class CosmicCommand {
    data class OpenBugs(val sub: String? = null) : CosmicCommand()
    data class Help(val topic: String? = null) : CosmicCommand()
    data object Unknown : CosmicCommand()
}

object CommandRegistry {
    /**
     * Parse chat input. Returns null if not a command (send to model).
     * Case-insensitive. Trims whitespace.
     */
    fun parse(raw: String): CosmicCommand? {
        val t = raw.trim()
        if (!t.startsWith("/")) return null
        val parts = t.substring(1).trim().split(Regex("\\s+"))
        val head = parts.firstOrNull()?.lowercase() ?: return CosmicCommand.Unknown
        val rest = parts.drop(1).joinToString(" ").ifBlank { null }
        return when (head) {
            "bugs" -> CosmicCommand.OpenBugs(rest?.lowercase())
            "help" -> CosmicCommand.Help(rest?.lowercase())
            else -> CosmicCommand.Unknown
        }
    }

    fun bugsHelpText(): String = """
        /bugs — open Bug Center
        /bugs new — new bug report
        /bugs my — my reports
        /bugs help — this list
    """.trimIndent()
}
