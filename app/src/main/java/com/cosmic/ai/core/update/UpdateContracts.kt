// language: kotlin, file: UpdateContracts.kt, runtime: pure Kotlin, target: COSMIC CORE update policy
package com.cosmic.ai.core.update

/**
 * Platform-independent update contracts.
 * Android adapters implement download/install; CORE only decides policy.
 */
data class UpdateManifest(
    val appId: String,
    val latestVersion: String,
    val versionCode: Int,
    val minimumSupportedVersion: Int,
    val updateRequired: Boolean,
    val releaseName: String,
    val releaseNotes: List<String>,
    val downloadUrl: String,
    val sha256: String,
    val publishedAt: String,
    val enabled: Boolean = true,
    val rolloutPercent: Int = 100
)

enum class UpdateDecision {
    UP_TO_DATE,
    OPTIONAL,
    MANDATORY,
    BLOCKED_DISABLED_RELEASE,
    INVALID_MANIFEST,
    NETWORK_ERROR
}

data class UpdateEvaluation(
    val decision: UpdateDecision,
    val manifest: UpdateManifest? = null,
    val installedVersionCode: Int,
    val installedVersionName: String,
    val message: String
)

object VersionComparator {
    /** Compare integer version codes. Positive if remote > local. */
    fun compareCodes(remote: Int, local: Int): Int = remote - local

    fun evaluate(
        manifest: UpdateManifest?,
        installedCode: Int,
        installedName: String,
        expectedAppId: String,
        error: String? = null
    ): UpdateEvaluation {
        if (error != null) {
            return UpdateEvaluation(
                UpdateDecision.NETWORK_ERROR,
                null,
                installedCode,
                installedName,
                error
            )
        }
        if (manifest == null) {
            return UpdateEvaluation(
                UpdateDecision.INVALID_MANIFEST,
                null,
                installedCode,
                installedName,
                "Empty manifest"
            )
        }
        if (manifest.appId != expectedAppId) {
            return UpdateEvaluation(
                UpdateDecision.INVALID_MANIFEST,
                manifest,
                installedCode,
                installedName,
                "app_id mismatch"
            )
        }
        if (!manifest.enabled) {
            return UpdateEvaluation(
                UpdateDecision.BLOCKED_DISABLED_RELEASE,
                manifest,
                installedCode,
                installedName,
                "Release disabled"
            )
        }
        if (manifest.sha256.isBlank() || manifest.downloadUrl.isBlank()) {
            return UpdateEvaluation(
                UpdateDecision.INVALID_MANIFEST,
                manifest,
                installedCode,
                installedName,
                "Missing sha256 or download_url"
            )
        }
        if (installedCode < manifest.minimumSupportedVersion) {
            return UpdateEvaluation(
                UpdateDecision.MANDATORY,
                manifest,
                installedCode,
                installedName,
                "Below minimum supported version"
            )
        }
        if (manifest.versionCode <= installedCode) {
            return UpdateEvaluation(
                UpdateDecision.UP_TO_DATE,
                manifest,
                installedCode,
                installedName,
                "Up to date"
            )
        }
        // newer available
        val decision = if (manifest.updateRequired) UpdateDecision.MANDATORY else UpdateDecision.OPTIONAL
        return UpdateEvaluation(
            decision,
            manifest,
            installedCode,
            installedName,
            if (decision == UpdateDecision.MANDATORY) "Mandatory update required" else "Optional update available"
        )
    }
}
