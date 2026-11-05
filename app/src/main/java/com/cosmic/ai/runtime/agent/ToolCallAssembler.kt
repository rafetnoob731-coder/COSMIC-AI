package com.cosmic.ai.runtime.agent

import com.cosmic.ai.domain.provider.ToolCall
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Reassembles OpenAI-style streamed tool call fragments without executing partial JSON. */
class ToolCallAssembler {
    private data class Partial(
        var id: String = "",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    )

    private val calls = sortedMapOf<Int, Partial>()

    fun append(index: Int, id: String?, nameFragment: String?, argumentsFragment: String?) {
        require(index >= 0) { "Tool call index must be non-negative" }
        val call = calls.getOrPut(index) { Partial() }
        if (!id.isNullOrEmpty()) {
            if (call.id.isNotEmpty() && call.id != id) throw ToolCallFormatException("Tool call ID changed for index $index")
            call.id = id
        }
        if (!nameFragment.isNullOrEmpty()) call.name.append(nameFragment)
        if (!argumentsFragment.isNullOrEmpty()) call.arguments.append(argumentsFragment)
    }

    fun finish(definitions: List<com.cosmic.ai.domain.provider.ToolDefinition>): List<ToolCall> {
        val schemas = definitions.associateBy { it.name }
        return calls.map { (index, partial) ->
            val id = partial.id.takeIf(String::isNotBlank)
                ?: throw ToolCallFormatException("Tool call at index $index has no ID")
            val name = partial.name.toString().takeIf(String::isNotBlank)
                ?: throw ToolCallFormatException("Tool call $id has no function name")
            val schema = schemas[name]
                ?: throw ToolCallFormatException("Model requested an unregistered tool: $name")
            val raw = partial.arguments.toString().ifBlank { "{}" }
            val args = try { JSONObject(raw) } catch (_: JSONException) {
                throw ToolCallFormatException("Arguments for $name are incomplete or invalid JSON")
            }
            validateTopLevel(args, schema.parametersJsonSchema, name)
            ToolCall(id = id, name = name, arguments = args.toString())
        }
    }

    private fun validateTopLevel(args: JSONObject, schemaText: String, toolName: String) {
        val schema = try { JSONObject(schemaText) } catch (_: JSONException) {
            throw ToolCallFormatException("Registered schema for $toolName is invalid")
        }
        if (schema.optString("type").let { it.isNotEmpty() && it != "object" }) {
            throw ToolCallFormatException("Registered schema for $toolName must describe an object")
        }
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) {
            val key = required.optString(i)
            if (key.isNotBlank() && !args.has(key)) throw ToolCallFormatException("Tool $toolName is missing required argument '$key'")
        }
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val property = properties.optJSONObject(key)
            if (property == null) continue
            val expected = property.optString("type")
            val value = args.get(key)
            val valid = when (expected) {
                "string" -> value is String
                "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                "number" -> value is Number
                "boolean" -> value is Boolean
                "object" -> value is JSONObject
                "array" -> value is JSONArray
                else -> true
            }
            if (!valid) throw ToolCallFormatException("Argument '$key' for $toolName must be $expected")
        }
    }
}

class ToolCallFormatException(message: String) : IllegalArgumentException(message)
