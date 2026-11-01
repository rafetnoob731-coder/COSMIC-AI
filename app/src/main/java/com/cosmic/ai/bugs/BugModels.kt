// language: kotlin, file: BugModels.kt, runtime: pure, target: Bug Center domain
package com.cosmic.ai.bugs

import java.util.UUID

enum class BugCategory {
    APPLICATION_CRASH, UI_UX, AI_PROVIDER, AGENT_EXECUTION, MCP,
    FILESYSTEM, TERMINAL, AUTHENTICATION, SECURITY, OTHER
}

enum class BugStatus {
    SUBMITTED, UNDER_REVIEW, CONFIRMED, IN_PROGRESS, FIXED, RELEASED, REJECTED
}

enum class BugPriority { LOW, MEDIUM, HIGH, CRITICAL }

data class BugReport(
    val id: String = "BUG-" + UUID.randomUUID().toString().take(8).uppercase(),
    val title: String,
    val category: BugCategory,
    val description: String,
    val steps: String,
    val expected: String,
    val actual: String,
    val appVersion: String,
    val deviceInfo: String,
    val status: BugStatus = BugStatus.SUBMITTED,
    val priority: BugPriority = BugPriority.MEDIUM,
    val createdAt: Long = System.currentTimeMillis(),
    val developerUpdate: String? = null
)
