// language: kotlin, file: UpdateRepository.kt, runtime: OkHttp, target: fetch + cache manifest
package com.cosmic.ai.update

import com.cosmic.ai.core.update.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UpdateRepository(
    private val endpointUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val cacheTtlMs: Long = 15 * 60 * 1000L
) {
    @Volatile private var cached: Pair<Long, UpdateManifest>? = null

    suspend fun fetch(force: Boolean = false): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hit = cached
        if (!force && hit != null && now - hit.first < cacheTtlMs) {
            return@withContext Result.success(hit.second)
        }
        try {
            val req = Request.Builder()
                .url(endpointUrl)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("HTTP ${resp.code}"))
                }
                val manifest = parse(body)
                cached = now to manifest
                Result.success(manifest)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clearCache() {
        cached = null
    }

    companion object {
        fun parse(json: String): UpdateManifest {
            val o = JSONObject(json)
            val notes = mutableListOf<String>()
            val arr = o.optJSONArray("release_notes")
            if (arr != null) for (i in 0 until arr.length()) notes.add(arr.getString(i))
            return UpdateManifest(
                appId = o.getString("app_id"),
                latestVersion = o.getString("latest_version"),
                versionCode = o.getInt("version_code"),
                minimumSupportedVersion = o.getInt("minimum_supported_version"),
                updateRequired = o.optBoolean("update_required", false),
                releaseName = o.optString("release_name", ""),
                releaseNotes = notes,
                downloadUrl = o.getString("download_url"),
                sha256 = o.getString("sha256").lowercase(),
                publishedAt = o.optString("published_at", ""),
                enabled = o.optBoolean("enabled", true),
                rolloutPercent = o.optInt("rollout_percent", 100)
            )
        }
    }
}
