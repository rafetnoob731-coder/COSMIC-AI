package com.cosmic.ai.data.local.keystore

import com.cosmic.ai.domain.mcp.McpServerConfig
import com.cosmic.ai.domain.mcp.McpTransport
import org.json.JSONArray
import org.json.JSONObject

/** MCP connection definitions can contain command arguments, headers, URLs, or environment secrets. */
class McpServerConfigStore(private val secureStore: SecureStore) {
    @Synchronized
    fun load(): List<McpServerConfig> {
        val encoded = secureStore.getSecret(ALIAS) ?: return emptyList()
        val array = JSONArray(encoded)
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val row = array.getJSONObject(index)
                val args = row.optJSONArray("args") ?: JSONArray()
                McpServerConfig(
                    id = row.getString("id"), name = row.getString("name"),
                    transport = McpTransport.valueOf(row.getString("transport")),
                    command = row.optString("command").takeIf(String::isNotBlank),
                    args = (0 until args.length()).map { args.getString(it) },
                    url = row.optString("url").takeIf(String::isNotBlank),
                    headers = row.optJSONObject("headers").toStringMap(),
                    enabled = row.optBoolean("enabled", true),
                    env = row.optJSONObject("env").toStringMap()
                )
            }.getOrNull()
        }
    }

    @Synchronized
    fun save(configs: List<McpServerConfig>) {
        val array = JSONArray()
        configs.forEach { config ->
            val args = JSONArray().also { values -> config.args.forEach { values.put(it) } }
            val headers = JSONObject().also { values -> config.headers.forEach { (key, value) -> values.put(key, value) } }
            val env = JSONObject().also { values -> config.env.forEach { (key, value) -> values.put(key, value) } }
            array.put(JSONObject()
                .put("id", config.id).put("name", config.name).put("transport", config.transport.name)
                .put("command", config.command).put("args", args).put("url", config.url)
                .put("headers", headers).put("enabled", config.enabled).put("env", env))
        }
        secureStore.putSecret(ALIAS, array.toString())
    }

    private fun JSONObject?.toStringMap(): Map<String, String> = this?.let { source ->
        buildMap { source.keys().forEach { key -> put(key, source.optString(key)) } }
    } ?: emptyMap()

    private companion object { const val ALIAS = "mcp_server_configs_v1" }
}
