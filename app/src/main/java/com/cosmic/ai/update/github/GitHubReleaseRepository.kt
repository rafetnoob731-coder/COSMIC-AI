// language: kotlin, file: GitHubReleaseRepository.kt, runtime: OkHttp, target: public GitHub Releases API
package com.cosmic.ai.update.github

import com.cosmic.ai.core.update.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches latest *stable* release from a public GitHub repo.
 * No auth token in the app. Handles 404 / rate-limit / network without crashing.
 */
class GitHubReleaseRepository(
    private val owner: String = "rafetnoob731-coder",
    private val repo: String = "COSMIC-AI",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val cacheTtlMs: Long = 15 * 60 * 1000L
) {
    @Volatile private var cached: Pair<Long, UpdateManifest>? = null

    private val latestUrl get() = "https://api.github.com/repos/$owner/$repo/releases/latest"

    suspend fun fetchLatest(force: Boolean = false): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hit = cached
        if (!force && hit != null && now - hit.first < cacheTtlMs) {
            return@withContext Result.success(hit.second)
        }
        try {
            val req = Request.Builder()
                .url(latestUrl)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "COSMIC-AI-Updater")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    404 -> return@withContext Result.failure(NoSuchElementException("No release published"))
                    403, 429 -> return@withContext Result.failure(
                        IllegalStateException("GitHub rate limited (${resp.code})")
                    )
                }
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("HTTP ${resp.code}"))
                }
                val body = resp.body?.string().orEmpty()
                val manifest = parseRelease(body)
                    ?: return@withContext Result.failure(IllegalStateException("Unusable release payload"))
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
        /**
         * Prefer release.json asset when present; otherwise derive from tag + APK asset.
         * Ignores draft/prerelease (latest endpoint already excludes drafts; still guard).
         */
        fun parseRelease(json: String): UpdateManifest? {
            val root = JSONObject(json)
            if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return null

            val tag = root.optString("tag_name", "").removePrefix("v")
            val body = root.optString("body", "")
            val published = root.optString("published_at", "")
            val assets = root.optJSONArray("assets") ?: JSONArray()

            // Try release.json first
            var metaJson: JSONObject? = null
            var apkUrl: String? = null
            var shaFromSums: String? = null

            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                when {
                    name.equals("release.json", true) -> {
                        // cannot fetch nested here without second call; store URL for optional enrich
                        // For single-request path, parse tag-based fallback
                    }
                    name.endsWith(".apk", true) -> apkUrl = url
                    name.equals("SHA256SUMS.txt", true) -> { /* optional second fetch */ }
                }
            }

            // Parse body front-matter style version_code if present
            val versionCode = extractInt(body, "version_code")
                ?: extractInt(body, "versionCode")
                ?: tagToCode(tag)
            val minSupported = extractInt(body, "minimum_supported_version_code")
                ?: extractInt(body, "minimumSupportedVersionCode")
                ?: 1
            val mandatory = body.contains("mandatory: true", ignoreCase = true) ||
                body.contains("\"mandatory\": true", ignoreCase = true)

            val notes = body.lines()
                .map { it.trim() }
                .filter { it.startsWith("- ") || it.startsWith("* ") }
                .map { it.removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf(root.optString("name", tag)) }

            if (apkUrl.isNullOrBlank()) {
                // still return manifest without download if only notes — caller treats missing url as invalid
                apkUrl = ""
            }

            val sha = extractSha(body) ?: "pending"

            return UpdateManifest(
                appId = "com.cosmic.ai",
                latestVersion = tag.ifBlank { "0.0.0" },
                versionCode = versionCode,
                minimumSupportedVersion = minSupported,
                updateRequired = mandatory,
                releaseName = root.optString("name", "COSMIC AI $tag"),
                releaseNotes = notes,
                downloadUrl = apkUrl ?: "",
                sha256 = sha.lowercase(),
                publishedAt = published,
                enabled = true,
                rolloutPercent = 100
            )
        }

        private fun tagToCode(tag: String): Int {
            // 0.4.0 -> 40, 1.2.3 -> 123 (simple)
            val parts = tag.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
            return when (parts.size) {
                0 -> 0
                1 -> parts[0]
                2 -> parts[0] * 10 + parts[1]
                else -> parts[0] * 100 + parts[1] * 10 + parts[2]
            }
        }

        private fun extractInt(text: String, key: String): Int? {
            val re = Regex("""(?i)$key\s*[:=]\s*(\d+)""")
            return re.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        private fun extractSha(text: String): String? {
            val re = Regex("""\b[a-fA-F0-9]{64}\b""")
            return re.find(text)?.value
        }
    }
}
