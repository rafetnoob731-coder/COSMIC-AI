package com.cosmic.ai.runtime.agent

import com.cosmic.ai.domain.provider.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolCallAssemblerTest {
    private val definition = ToolDefinition("read_file", "Read a project file",
        """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")

    @Test fun reconstructsMultipleInterleavedCallsByIndexAndPreservesIds() {
        val assembler = ToolCallAssembler()
        assembler.append(1, "call_b", "read_file", "{\"path\":\"b\"}")
        assembler.append(0, "call_a", "read_file", "{\"path\":")
        assembler.append(0, null, null, "\"a\"}")
        val calls = assembler.finish(listOf(definition))
        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
        assertEquals("{\"path\":\"a\"}", calls[0].arguments)
    }

    @Test fun rejectsTruncatedJsonWithoutProducingCallableTool() {
        val assembler = ToolCallAssembler()
        assembler.append(0, "call", "read_file", "{\"path\":\"")
        assertThrows(ToolCallFormatException::class.java) { assembler.finish(listOf(definition)) }
    }

    @Test fun rejectsMissingRequiredArgumentsUnknownToolsAndInvalidTypes() {
        fun assembler(arguments: String, name: String = "read_file") =
            ToolCallAssembler().also { it.append(0, "call", name, arguments) }
        assertThrows(ToolCallFormatException::class.java) { assembler("{}").finish(listOf(definition)) }
        assertThrows(ToolCallFormatException::class.java) { assembler("{}", "delete_everything").finish(listOf(definition)) }
        assertThrows(ToolCallFormatException::class.java) { assembler("{\"path\":12}").finish(listOf(definition)) }
    }

    @Test fun rejectsIdMismatchAcrossFragments() {
        val assembler = ToolCallAssembler()
        assembler.append(0, "call-a", "read_file", "{")
        assertThrows(ToolCallFormatException::class.java) { assembler.append(0, "call-b", null, "}") }
    }
}
