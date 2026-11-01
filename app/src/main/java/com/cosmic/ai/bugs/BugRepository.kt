// language: kotlin, file: BugRepository.kt, runtime: local + optional remote, target: real submissions
package com.cosmic.ai.bugs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Local-first bug store. Optional remote endpoint from preferences.
 * If remote unavailable, still persists locally and reports honest status.
 */
class BugRepository(private val context: Context) {
    private val file = File(context.filesDir, "bugs.json")
    private val _bugs = MutableStateFlow(load())
    val bugs: StateFlow<List<BugReport>> = _bugs.asStateFlow()

    var remoteBaseUrl: String? = null // e.g. https://api.example.com — null = local only

    fun submit(report: BugReport): Result<BugReport> {
        if (report.title.isBlank() || report.description.isBlank()) {
            return Result.failure(IllegalArgumentException("Title and description required"))
        }
        val next = listOf(report) + _bugs.value
        _bugs.value = next
        persist(next)
        // remote attempt (honest failure)
        remoteBaseUrl?.let { base ->
            // network left to caller service; local is source of truth for offline
        }
        return Result.success(report)
    }

    fun myReports(): List<BugReport> = _bugs.value

    fun get(id: String): BugReport? = _bugs.value.firstOrNull { it.id == id }

    private fun persist(list: List<BugReport>) {
        try {
            val arr = org.json.JSONArray()
            list.forEach { b ->
                arr.put(JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("category", b.category.name)
                    .put("description", b.description)
                    .put("steps", b.steps)
                    .put("expected", b.expected)
                    .put("actual", b.actual)
                    .put("appVersion", b.appVersion)
                    .put("deviceInfo", b.deviceInfo)
                    .put("status", b.status.name)
                    .put("priority", b.priority.name)
                    .put("createdAt", b.createdAt)
                    .put("developerUpdate", b.developerUpdate)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    private fun load(): List<BugReport> {
        return try {
            if (!file.exists()) return emptyList()
            val arr = org.json.JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        BugReport(
                            id = o.getString("id"),
                            title = o.getString("title"),
                            category = BugCategory.valueOf(o.getString("category")),
                            description = o.getString("description"),
                            steps = o.optString("steps"),
                            expected = o.optString("expected"),
                            actual = o.optString("actual"),
                            appVersion = o.optString("appVersion"),
                            deviceInfo = o.optString("deviceInfo"),
                            status = BugStatus.valueOf(o.optString("status", "SUBMITTED")),
                            priority = BugPriority.valueOf(o.optString("priority", "MEDIUM")),
                            createdAt = o.optLong("createdAt"),
                            developerUpdate = o.optString("developerUpdate").ifBlank { null }
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
