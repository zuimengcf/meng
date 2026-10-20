package org.fcitx.fcitx5.android.data.clipboard

import android.content.Context
import org.fcitx.fcitx5.android.utils.appContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** A persisted, content-safe result of one automatic or manual synchronization. */
data class ClipboardSyncResult(
    val timestamp: Long,
    val role: String,
    val added: Int,
    val mergedExisting: Int,
    val updated: Int,
    val deleted: Int,
    val totalAfter: Int,
    val addedDetails: List<ClipboardSyncChange>,
    val updatedDetails: List<ClipboardSyncChange>,
    val deletedDetails: List<ClipboardSyncChange>,
) {
    val hasVisibleChanges: Boolean get() = added > 0 || updated > 0 || deleted > 0
    fun headline(): String = "新增 $added · 编辑 $updated · 删除 $deleted · 现有 $totalAfter"
    fun detailText(): String = buildString {
        append(headline())
        append("\n")
        append(if (hasVisibleChanges) "变动明细" else "没有可见变化")
        fun appendRows(title: String, rows: List<ClipboardSyncChange>) {
            if (rows.isEmpty()) return
            append("\n\n$title（${rows.size}）")
            rows.forEach { row ->
                append("\n• ").append(row.preview)
            }
        }
        appendRows("新增", addedDetails)
        appendRows("编辑", updatedDetails)
        appendRows("删除", deletedDetails)
    }
}

/**
 * Stores redacted, short previews in private app storage. Call [publish] after
 * a merge has been durably applied; listeners are used by the foreground service.
 */
object ClipboardSyncNotificationStore {
    private const val PREFS = "clipboard_sync_notifications"
    private const val KEY_RESULTS = "results"
    private const val MAX_RESULTS = 20
    private val listeners = CopyOnWriteArraySet<(ClipboardSyncResult) -> Unit>()

    /** Compatibility-friendly API for callers that already have the before snapshot. */
    @Synchronized fun record(before: ClipboardSyncPacket, stats: ClipboardSyncStats, role: String = "同步", now: Long = System.currentTimeMillis()): ClipboardSyncResult {
        // Keep the before parameter in the public contract so callers cannot accidentally
        // publish a pre-merge count; stats must be calculated from before/incoming/after.
        @Suppress("UNUSED_VARIABLE") val ignoredBefore = before
        val result = ClipboardSyncResult(now, role, stats.added, stats.mergedExisting, stats.updated, stats.deleted, stats.totalAfter,
            sanitize(stats.addedDetails), sanitize(stats.updatedDetails), sanitize(stats.deletedDetails))
        if (!result.hasVisibleChanges) return result
        // Notifications are best-effort: never turn a successful merge into a failed sync.
        runCatching {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val rows = recent().toMutableList()
            rows.add(0, result)
            prefs.edit().putString(KEY_RESULTS, JSONArray(rows.take(MAX_RESULTS).map(::encode)).toString()).apply()
            listeners.forEach { listener -> runCatching { listener(result) } }
        }
        return result
    }

    fun publish(before: ClipboardSyncPacket, stats: ClipboardSyncStats, role: String = "同步"): ClipboardSyncResult = record(before, stats, role)

    fun recent(): List<ClipboardSyncResult> {
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RESULTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { decode(array.getJSONObject(it)) }.map { result ->
                result.copy(role = "同步", addedDetails = sanitize(result.addedDetails),
                    updatedDetails = sanitize(result.updatedDetails), deletedDetails = sanitize(result.deletedDetails))
            }
        }.getOrDefault(emptyList())
    }

    /** Replace legacy previews on disk as well as on screen; counts/results are retained. */
    @Synchronized fun sanitizeLegacy() {
        runCatching {
            val rows = recent()
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RESULTS, JSONArray(rows.take(MAX_RESULTS).map(::encode)).toString()).commit()
        }
    }

    fun latest(): ClipboardSyncResult? = recent().firstOrNull()
    fun addListener(listener: (ClipboardSyncResult) -> Unit) { listeners += listener }
    fun removeListener(listener: (ClipboardSyncResult) -> Unit) { listeners -= listener }

    private fun sanitize(rows: List<ClipboardSyncChange>) = rows.map { row ->
        // No free-text fields, including remote IDs/group names, enter the result log.
        row.copy(syncId = "", action = row.action.takeIf { it in setOf("新增", "编辑", "删除") } ?: "变更",
            preview = if (row.isGroup) "分组条目（不记录名称）" else "剪切板条目（不记录正文）")
    }
    private fun encode(result: ClipboardSyncResult) = JSONObject().apply {
        put("timestamp", result.timestamp); put("role", result.role); put("added", result.added)
        put("merged", result.mergedExisting); put("updated", result.updated); put("deleted", result.deleted); put("total", result.totalAfter)
        put("addedDetails", JSONArray(result.addedDetails.map(::encodeChange)))
        put("updatedDetails", JSONArray(result.updatedDetails.map(::encodeChange)))
        put("deletedDetails", JSONArray(result.deletedDetails.map(::encodeChange)))
    }
    private fun encodeChange(row: ClipboardSyncChange) = JSONObject().apply {
        put("id", row.syncId); put("action", row.action); put("preview", row.preview); put("group", row.isGroup)
    }
    private fun decode(json: JSONObject): ClipboardSyncResult? = runCatching { ClipboardSyncResult(
        json.getLong("timestamp"), json.optString("role", "同步"), json.optInt("added"), json.optInt("merged"),
        json.optInt("updated"), json.optInt("deleted"), json.optInt("total"), decodeRows(json.optJSONArray("addedDetails")),
        decodeRows(json.optJSONArray("updatedDetails")), decodeRows(json.optJSONArray("deletedDetails")))
    }.getOrNull()
    private fun decodeRows(array: JSONArray?): List<ClipboardSyncChange> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { i -> runCatching { array.getJSONObject(i).let { ClipboardSyncChange(it.optString("id"), it.optString("action"), it.optString("preview"), it.optBoolean("group")) } }.getOrNull() }
}

/** Public facade used by both client and host after a durable merge. */
object ClipboardSyncNotifications {
    /** Best-effort: notification persistence must never turn a successful sync into failure. */
    fun record(stats: ClipboardSyncStats, role: String = "同步"): ClipboardSyncResult? = runCatching {
        ClipboardSyncNotificationStore.record(ClipboardSyncPacket(), stats, role)
    }.getOrNull()

    fun publish(stats: ClipboardSyncStats, role: String = "同步"): ClipboardSyncResult? = record(stats, role)
    fun latest(): ClipboardSyncResult? = runCatching { ClipboardSyncNotificationStore.latest() }.getOrNull()
    fun recent(): List<ClipboardSyncResult> = runCatching { ClipboardSyncNotificationStore.recent() }.getOrDefault(emptyList())
    fun addListener(listener: (ClipboardSyncResult) -> Unit) = ClipboardSyncNotificationStore.addListener(listener)
    fun removeListener(listener: (ClipboardSyncResult) -> Unit) = ClipboardSyncNotificationStore.removeListener(listener)
}
