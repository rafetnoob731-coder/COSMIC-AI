// language: kotlin, file: UpdateConfig.kt, runtime: Android, target: GitHub Releases channel
package com.cosmic.ai.update

object UpdateConfig {
    const val GITHUB_OWNER = "rafetnoob731-coder"
    const val GITHUB_REPO = "COSMIC-AI"
    /** Legacy custom API still supported; prefer GitHub for public channel. */
    const val ENDPOINT = "https://api.github.com/repos/rafetnoob731-coder/COSMIC-AI/releases/latest"
    const val CACHE_TTL_MS = 15 * 60 * 1000L
    const val USE_GITHUB_RELEASES = true
}
