package com.cosmic.ai.data.keystore

import android.app.Application
import com.cosmic.ai.data.local.keystore.McpServerConfigStore
import com.cosmic.ai.data.local.keystore.SecureStore
import com.cosmic.ai.domain.mcp.McpServerConfig
import com.cosmic.ai.domain.mcp.McpTransport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = McpServerConfigStoreTest.TestApplication::class, sdk = [35])
class McpServerConfigStoreTest {
    class TestApplication : Application()

    @Test fun serverDefinitionsSurviveReloadInEncryptedStore() {
        val store = McpServerConfigStore(SecureStore(RuntimeEnvironment.getApplication()))
        val expected = listOf(McpServerConfig(
            id = "server-1", name = "Private MCP", transport = McpTransport.HTTP,
            url = "https://example.invalid/mcp", headers = mapOf("Authorization" to "Bearer test-secret"),
            args = listOf("--mode", "safe"), env = mapOf("TOKEN" to "test-secret")
        ))
        store.save(expected)
        assertEquals(expected, McpServerConfigStore(SecureStore(RuntimeEnvironment.getApplication())).load())
    }
}
