// language: kotlin, file: SecureStore.kt, runtime: Android Keystore + EncryptedSharedPreferences, target: API keys
package com.cosmic.ai.data.local.keystore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecretMasker {
    fun mask(secret: String?): String {
        val value = secret ?: return ""
        if (value.length <= 8) return "••••••••"
        return value.take(4) + "••••" + value.takeLast(4)
    }
}

class SecureStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "cosmic_secure_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Throwable) {
        context.getSharedPreferences("cosmic_secure_store_fallback", Context.MODE_PRIVATE)
    }

    fun putSecret(alias: String, value: String) {
        prefs.edit().putString(alias, value).apply()
    }

    /** Synchronous durable checkpoint write, used only for security audit head anchoring. */
    fun putSecretDurably(alias: String, value: String): Boolean = prefs.edit().putString(alias, value).commit()

    fun getSecret(alias: String): String? = prefs.getString(alias, null)

    fun removeSecret(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    fun hasSecret(alias: String): Boolean = prefs.contains(alias)

    fun mask(alias: String): String = SecretMasker.mask(getSecret(alias))
}
