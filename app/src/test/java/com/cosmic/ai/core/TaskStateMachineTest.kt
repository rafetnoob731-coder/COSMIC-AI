package com.cosmic.ai.core

import com.cosmic.ai.core.state.TaskPhase
import com.cosmic.ai.core.state.TaskStateMachine
import org.junit.Assert.*
import org.junit.Test

class TaskStateMachineTest {
    @Test
    fun createAndTransition() {
        val m = TaskStateMachine()
        val t = m.create("s1", "Build", "implement feature")
        assertEquals(TaskPhase.CREATED, t.phase)
        val next = m.transition(t.id, TaskPhase.PLANNING)
        assertNotNull(next)
        assertEquals(TaskPhase.PLANNING, next!!.phase)
    }

    @Test
    fun appendToolHistory() {
        val m = TaskStateMachine()
        val t = m.create("s1", "T", "x")
        m.appendTool(t.id, com.cosmic.ai.core.state.ToolHistoryEntry("read_file", "{}", "ok", true))
        assertEquals(1, m.get(t.id)!!.toolHistory.size)
    }
}
