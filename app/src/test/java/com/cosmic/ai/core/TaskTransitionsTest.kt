package com.cosmic.ai.core

import com.cosmic.ai.core.state.TaskTransitions
import com.cosmic.ai.domain.agent.TaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTransitionsTest {
    @Test fun normalExecutionRequiresPersistedIntermediateStates() {
        assertTrue(TaskTransitions.canTransition(TaskStatus.QUEUED, TaskStatus.STARTING))
        assertTrue(TaskTransitions.canTransition(TaskStatus.STARTING, TaskStatus.WAITING_FOR_MODEL))
        assertTrue(TaskTransitions.canTransition(TaskStatus.WAITING_FOR_MODEL, TaskStatus.WAITING_FOR_TOOL))
        assertTrue(TaskTransitions.canTransition(TaskStatus.COMPLETING, TaskStatus.COMPLETED))
    }

    @Test fun terminalTasksCannotBeReplayedByAccidentalTransition() {
        for (terminal in listOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            assertFalse(TaskTransitions.canTransition(terminal, TaskStatus.QUEUED))
            assertFalse(TaskTransitions.canTransition(terminal, TaskStatus.STARTING))
        }
    }

    @Test fun interruptedTasksRequireRecoveryDecision() {
        assertTrue(TaskTransitions.canTransition(TaskStatus.RECOVERY_REQUIRED, TaskStatus.QUEUED))
        assertTrue(TaskTransitions.canTransition(TaskStatus.RECOVERY_REQUIRED, TaskStatus.FAILED))
        assertFalse(TaskTransitions.canTransition(TaskStatus.WAITING_FOR_TOOL, TaskStatus.COMPLETED))
    }
}
