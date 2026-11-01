// language: kotlin, file: ModelRouter.kt, runtime: pure Kotlin, target: COSMIC INTELLIGENCE routing
package com.cosmic.ai.intelligence.router

import com.cosmic.ai.agents.AgentRole
import com.cosmic.ai.domain.provider.ProviderConfig
import com.cosmic.ai.domain.provider.ProviderType

data class RouteDecision(
    val provider: ProviderConfig,
    val model: String,
    val reason: String
)

class ModelRouter(
    private val providers: () -> List<ProviderConfig>
) {
    fun route(
        taskType: String,
        role: AgentRole? = null,
        preferCoding: Boolean = false,
        userOverrideModel: String? = null,
        userOverrideProviderId: String? = null
    ): RouteDecision? {
        val list = providers()
        if (list.isEmpty()) return null

        if (userOverrideProviderId != null) {
            val p = list.firstOrNull { it.id == userOverrideProviderId } ?: list.first()
            return RouteDecision(p, userOverrideModel ?: p.defaultModel, "user override")
        }

        // heuristic: coding / architect → prefer stronger models when available
        val preferredType = when {
            preferCoding || role == AgentRole.BUILDER || role == AgentRole.DEBUGGER ->
                listOf(ProviderType.ANTHROPIC, ProviderType.OPENAI_COMPATIBLE, ProviderType.OPENROUTER)
            role == AgentRole.ARCHITECT || role == AgentRole.REVIEWER ->
                listOf(ProviderType.ANTHROPIC, ProviderType.OPENAI_COMPATIBLE)
            role == AgentRole.RESEARCHER || role == AgentRole.TESTER ->
                listOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.DEEPSEEK, ProviderType.GEMINI)
            else -> listOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.OPENROUTER, ProviderType.GEMINI)
        }

        val picked = preferredType.firstNotNullOfOrNull { t -> list.firstOrNull { it.type == t } }
            ?: list.first()

        val model = userOverrideModel
            ?: role?.let { hintModel(it, picked) }
            ?: picked.defaultModel

        return RouteDecision(picked, model, "routed for $taskType / ${role?.name ?: "general"}")
    }

    private fun hintModel(role: AgentRole, config: ProviderConfig): String {
        return when (config.type) {
            ProviderType.ANTHROPIC -> when (role) {
                AgentRole.ARCHITECT, AgentRole.REVIEWER -> "claude-3-5-sonnet-20241022"
                else -> config.defaultModel
            }
            ProviderType.GEMINI -> "gemini-1.5-pro"
            else -> config.defaultModel
        }
    }
}
