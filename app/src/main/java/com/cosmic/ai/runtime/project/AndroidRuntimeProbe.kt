package com.cosmic.ai.runtime.project

import com.cosmic.ai.core.project.RuntimeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Checks the app's actual Android shell PATH using fixed executable names from RuntimeCatalog. */
class AndroidRuntimeProbe {
    suspend fun availableExecutables(language: String): Set<String> = withContext(Dispatchers.IO) {
        val candidates = RuntimeCatalog.forLanguage(language)?.executables.orEmpty()
        candidates.filterTo(linkedSetOf()) { executable ->
            try {
                val process = ProcessBuilder("/system/bin/sh", "-c", "command -v '$executable'")
                    .redirectErrorStream(true).start()
                val exited = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                if (!exited) process.destroyForcibly()
                exited && process.exitValue() == 0
            } catch (_: Exception) {
                false
            }
        }
    }
}
