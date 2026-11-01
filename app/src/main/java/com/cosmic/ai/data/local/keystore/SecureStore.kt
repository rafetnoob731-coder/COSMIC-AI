// language: kotlin, file: SecureStore.kt, runtime: Android Keystore + EncryptedSharedPreferences, target: API keys
package com.cosmic.ai.data.local.keystore

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cosmic_secure_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun putSecret(alias: String, value: String) {
        prefs.edit().putString(alias, value).apply()
    }

    fun getSecret(alias: String): String? = prefs.getString(alias, null)

    fun removeSecret(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    fun hasSecret(alias: String): Boolean = prefs.contains(alias)

    fun mask(alias: String): String {
        val v = getSecret(alias) ?: return ""
        if (v.length <= 8) return "••••••••"
        return v.take(4) + "••••" + v.takeLast(4)
    }
}
