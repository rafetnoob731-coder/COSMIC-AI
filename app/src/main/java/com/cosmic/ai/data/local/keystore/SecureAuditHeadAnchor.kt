package com.cosmic.ai.data.local.keystore

import com.cosmic.ai.data.local.db.AuditHeadAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** EncryptedSharedPreferences/Keystore checkpoint; rollback of app-private data remains in the threat model. */
class SecureAuditHeadAnchor(private val secureStore: SecureStore) : AuditHeadAnchor {
    override suspend fun read(): AuditHeadAnchor.Head? = withContext(Dispatchers.IO) {
        synchronized(LOCK) { readBlocking() }
    }

    override suspend fun write(head: AuditHeadAnchor.Head) = withContext(Dispatchers.IO) {
        require(head.sequence >= 0 && head.hash.isNotBlank()) { "Invalid audit checkpoint" }
        synchronized(LOCK) {
            val existing = readBlocking()
            require(existing == null || head.sequence >= existing.sequence) { "Refusing to roll back audit head checkpoint" }
            require(existing == null || head.sequence != existing.sequence || head.hash == existing.hash) {
                "Conflicting audit head at the same sequence"
            }
            val encoded = JSONObject().put("sequence", head.sequence).put("hash", head.hash).toString()
            check(secureStore.putSecretDurably(ALIAS, encoded)) { "Could not persist audit head checkpoint" }
        }
    }

    private fun readBlocking(): AuditHeadAnchor.Head? {
        val encoded = secureStore.getSecret(ALIAS) ?: return null
        val json = JSONObject(encoded)
        return AuditHeadAnchor.Head(json.getLong("sequence"), json.getString("hash"))
    }

    private companion object {
        const val ALIAS = "security_audit_head_v1"
        val LOCK = Any()
    }
}
