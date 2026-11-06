package com.cosmic.ai.runtime.project

import android.webkit.WebView
import com.cosmic.ai.core.project.RuntimeCatalog
import com.cosmic.ai.core.project.RuntimeInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Probes only fixed executable names in the Android app-visible PATH; it never installs or runs project commands. */
class AndroidRuntimeProbe {
    suspend fun availableExecutables(language: String): Set<String> = inspect(language, languageDetected = true).availableExecutables

    suspend fun inspect(language: String, languageDetected: Boolean): RuntimeInspection = withContext(Dispatchers.IO) {
        if (language.equals("HTML", true) || language.equals("CSS", true) || language.equals("JavaScript", true)) {
            val webViewPackage = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
            if (webViewPackage != null) {
                return@withContext RuntimeInspection(
                    languageDetected = languageDetected,
                    runtimeInstalled = true,
                    executable = "Android System WebView",
                    version = webViewPackage.versionName,
                    availableExecutables = emptySet(),
                    executionSupported = true, // Browser preview only; not a general-purpose JS shell.
                    buildSupported = false,
                    testSupported = false
                )
            }
        }
        val definition = RuntimeCatalog.forLanguage(language)
            ?: return@withContext RuntimeCatalog.unsupported(language, languageDetected)
        val available = definition.executables.filterTo(linkedSetOf(), ::isAvailable)
        val executable = definition.versionExecutables.firstOrNull { it in available }
        val version = executable?.let(::versionOf)
        RuntimeInspection(
            languageDetected = languageDetected,
            runtimeInstalled = executable != null,
            executable = executable,
            version = version,
            availableExecutables = available,
            executionSupported = executable != null,
            buildSupported = definition.buildExecutables.any { it in available },
            testSupported = definition.testExecutables.any { it in available }
        )
    }

    fun isAvailable(executable: String): Boolean = runFixed(executable, "command -v '$executable'") != null

    private fun versionOf(executable: String): String? {
        val output = runFixed(executable, "'$executable' --version") ?: return null
        return output.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }?.take(160)
    }

    /** Only catalog constants reach this shell; no user- or model-supplied text is interpolated. */
    private fun runFixed(executable: String, command: String): String? {
        if (!executable.matches(Regex("[A-Za-z0-9_.+-]+"))) return null
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
            val output = ByteArrayOutputStream()
            val reader = Thread {
                process.inputStream.use { input ->
                    val buffer = ByteArray(1024)
                    var total = 0
                    while (total < 4096) {
                        val read = input.read(buffer, 0, minOf(buffer.size, 4096 - total))
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(500)
            if (finished && process.exitValue() == 0) output.toString(Charsets.UTF_8.name()) else null
        } catch (_: Exception) {
            null
        }
    }
}
