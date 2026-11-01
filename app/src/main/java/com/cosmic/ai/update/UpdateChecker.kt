// language: kotlin, file: UpdateChecker.kt, runtime: Android, target: startup evaluation
package com.cosmic.ai.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.cosmic.ai.core.update.UpdateEvaluation
import com.cosmic.ai.core.update.VersionComparator
import com.cosmic.ai.update.github.GitHubReleaseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateChecker(
    private val context: Context,
    private val expectedAppId: String = context.packageName
) {
    private val github = GitHubReleaseRepository(
        owner = UpdateConfig.GITHUB_OWNER,
        repo = UpdateConfig.GITHUB_REPO,
        cacheTtlMs = UpdateConfig.CACHE_TTL_MS
    )

    suspend fun check(force: Boolean = false): UpdateEvaluation = withContext(Dispatchers.IO) {
        val (code, name) = installedVersion()
        val result = if (UpdateConfig.USE_GITHUB_RELEASES) {
            github.fetchLatest(force)
        } else {
            UpdateRepository(UpdateConfig.ENDPOINT, cacheTtlMs = UpdateConfig.CACHE_TTL_MS).fetch(force)
        }
        result.fold(
            onSuccess = { manifest ->
                VersionComparator.evaluate(manifest, code, name, expectedAppId)
            },
            onFailure = { e ->
                VersionComparator.evaluate(null, code, name, expectedAppId, e.message ?: "network error")
            }
        )
    }

    fun installedVersion(): Pair<Int, String> {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else {
                @Suppress("DEPRECATION") info.versionCode
            }
            code to (info.versionName ?: "0")
        } catch (_: Exception) {
            0 to "0"
        }
    }
}
