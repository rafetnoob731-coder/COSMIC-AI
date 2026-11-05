package com.cosmic.ai.agents

import com.cosmic.ai.core.state.ApprovalEntry
import com.cosmic.ai.core.state.TaskPhase
import com.cosmic.ai.core.state.TaskPlanStep
import com.cosmic.ai.core.state.TaskStateMachine
import com.cosmic.ai.core.state.ToolHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentOrchestrationStateTest {
    @Test fun planningApprovalExecutionAndCompletionRetainIndependentTaskHistory() {
        val state = TaskStateMachine()
        val task = state.create("session-1", "Review project", "Inspect and test")
        state.assignAgent(task.id, "REVIEWER", "model-a")
        state.setPlan(task.id, listOf(TaskPlanStep(title = "Inspect", description = "Read project files")))
        state.appendApproval(task.id, ApprovalEntry(action = "read project", approved = true))
        state.transition(task.id, TaskPhase.EXECUTING)
        state.appendTool(task.id, ToolHistoryEntry("list_files", "{}", "src", success = true))
        state.transition(task.id, TaskPhase.COMPLETED, result = "Review complete")

        val completed = state.get(task.id)
        assertNotNull(completed)
        assertEquals(TaskPhase.COMPLETED, completed?.phase)
        assertEquals("REVIEWER", completed?.assignedAgent)
        assertEquals("model-a", completed?.selectedModel)
        assertEquals(1, completed?.plan?.size)
        assertEquals(true, completed?.approvalHistory?.single()?.approved)
        assertEquals("list_files", completed?.toolHistory?.single()?.tool)
        assertEquals("Review complete", completed?.result)
        assertEquals(1, state.all().size)
    }
}
