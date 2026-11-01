// language: kotlin, file: ApkDownloadManager.kt, runtime: Android DownloadManager, target: secure APK fetch
package com.cosmic.ai.update

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

sealed class DownloadProgress {
    data class Running(val bytes: Long, val total: Long) : DownloadProgress()
    data class Completed(val file: File) : DownloadProgress()
    data class Failed(val message: String) : DownloadProgress()
    data object Cancelled : DownloadProgress()
}

class ApkDownloadManager(private val context: Context) {
    private var downloadId: Long = -1L
    private val dm get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun download(url: String, fileName: String = "cosmic-ai-update.apk"): Flow<DownloadProgress> = flow {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("COSMIC AI Update")
            .setDescription("Downloading release")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        downloadId = dm.enqueue(request)
        while (true) {
            val q = DownloadManager.Query().setFilterById(downloadId)
            dm.query(q).use { c ->
                if (c == null || !c.moveToFirst()) {
                    emit(DownloadProgress.Failed("Query failed"))
                    return@flow
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = dm.getUriForDownloadedFile(downloadId)
                        val path = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        val file = resolveFile(uri, path)
                        if (file != null) emit(DownloadProgress.Completed(file))
                        else emit(DownloadProgress.Failed("File missing after download"))
                        return@flow
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        emit(DownloadProgress.Failed("Download failed ($reason)"))
                        return@flow
                    }
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> {
                        emit(DownloadProgress.Running(bytes, total.coerceAtLeast(0)))
                    }
                }
            }
            delay(400)
        }
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        if (downloadId >= 0) dm.remove(downloadId)
    }

    private fun resolveFile(uri: Uri?, path: String?): File? {
        if (path != null) {
            val p = path.removePrefix("file://")
            val f = File(p)
            if (f.exists()) return f
        }
        // fallback app external files
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return dir.listFiles()?.firstOrNull { it.name.endsWith(".apk") && it.length() > 0 }
    }
}
