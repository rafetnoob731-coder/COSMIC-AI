// language: kotlin, file: IntegrityVerifier.kt, runtime: Android, target: SHA-256 + package checks
package com.cosmic.ai.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object IntegrityVerifier {
    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyHash(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || expectedSha256.isBlank()) return false
        return sha256File(file).equals(expectedSha256.trim(), ignoreCase = true)
    }

    /** Archive package name must match expected applicationId. */
    fun verifyPackageName(context: Context, apkFile: File, expectedPackage: String): Boolean {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
            info?.packageName == expectedPackage
        } catch (_: Exception) {
            false
        }
    }
}
