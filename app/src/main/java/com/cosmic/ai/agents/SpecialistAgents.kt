// language: kotlin, file: SpecialistAgents.kt, runtime: pure Kotlin, target: COSMIC AGENTS
package com.cosmic.ai.agents

enum class AgentRole {
    ORCHESTRATOR,
    ARCHITECT,
    BUILDER,
    DEBUGGER,
    TESTER,
    REVIEWER,
    RESEARCHER
}

data class AgentProfile(
    val role: AgentRole,
    val displayName: String,
    val systemPrompt: String,
    val allowedTools: Set<String>,
    val preferredModelHint: String? = null
)

object SpecialistAgents {
    val all: List<AgentProfile> = listOf(
        AgentProfile(
            AgentRole.ORCHESTRATOR,
            "Orchestrator",
            "You coordinate specialist agents. Decompose work, assign roles, verify results. Never implement code yourself unless no specialist is available.",
            setOf("list_files", "read_file"),
            "gpt-4o"
        ),
        AgentProfile(
            AgentRole.ARCHITECT,
            "Architect",
            "You analyze requirements and produce technical plans, module boundaries, and risk notes. Output structured plans. Do not write large code dumps.",
            setOf("list_files", "read_file"),
            "claude-3-5-sonnet-20241022"
        ),
        AgentProfile(
            AgentRole.BUILDER,
            "Builder",
            "You create and modify project files. Prefer minimal diffs. Use write_file only after understanding existing structure.",
            setOf("list_files", "read_file", "write_file", "run_command"),
            "gpt-4o"
        ),
        AgentProfile(
            AgentRole.DEBUGGER,
            "Debugger",
            "You investigate failures. Read logs and code, form hypotheses, propose minimal fixes. Do not rewrite unrelated modules.",
            setOf("list_files", "read_file", "run_command"),
            "gpt-4o"
        ),
        AgentProfile(
            AgentRole.TESTER,
            "Tester",
            "You run available tests and validate behavior. Report exact exit codes and failing assertions.",
            setOf("list_files", "read_file", "run_command"),
            "gpt-4o-mini"
        ),
        AgentProfile(
            AgentRole.REVIEWER,
            "Reviewer",
            "You inspect diffs for correctness, security, and architecture. Flag silent overwrites and missing error handling.",
            setOf("list_files", "read_file"),
            "claude-3-5-sonnet-20241022"
        ),
        AgentProfile(
            AgentRole.RESEARCHER,
            "Researcher",
            "You research documentation and technical facts. Prefer primary sources. Summarize with citations when available.",
            setOf("list_files", "read_file"),
            "gpt-4o-mini"
        )
    )

    fun byRole(role: AgentRole): AgentProfile = all.first { it.role == role }
}
