package org.fcitx.fcitx5.android.data.clipboard

/** A human-readable change row; [preview] never exposes sensitive clipboard text. */
data class ClipboardSyncChange(
    val syncId: String,
    val action: String,
    val preview: String,
    val isGroup: Boolean = false,
)

/**
 * Statistics for one snapshot exchange. Tombstones participate in matching and
 * deletion detection, but never in [added] or [totalAfter].
 */
data class ClipboardSyncStats(
    val added: Int,
    val mergedExisting: Int,
    val updated: Int,
    val deleted: Int,
    val totalAfter: Int,
    val addedDetails: List<ClipboardSyncChange> = emptyList(),
    val updatedDetails: List<ClipboardSyncChange> = emptyList(),
    val deletedDetails: List<ClipboardSyncChange> = emptyList(),
)

/** Pure packet-only accounting; deliberately has no Room, Android, or sync side effects. */
object ClipboardSyncSummary {
    fun summarize(
        before: ClipboardSyncPacket,
        incoming: ClipboardSyncPacket,
        after: ClipboardSyncPacket,
    ): ClipboardSyncStats {
        val beforeEntries = before.entries.associateBy { it.syncId }
        val incomingEntries = incoming.entries.associateBy { it.syncId }
        val afterEntries = after.entries.associateBy { it.syncId }
        val beforeVisible = beforeEntries.filterValues { !it.deleted }
        val afterVisible = afterEntries.filterValues { !it.deleted }

        val beforeGroups = before.groups.associateBy { it.syncId }
        val afterGroups = after.groups.associateBy { it.syncId }
        fun groupChange(group: SyncGroup, action: String) = ClipboardSyncChange(group.syncId, action,
            "分组：${group.name.replace("\\s+".toRegex(), " ").take(80)}", isGroup = true)
        val matched = incomingEntries.keys.count { it in beforeEntries }
        val addedDetails = afterVisible.values
            .filter { it.syncId !in beforeVisible }
            .map { change(it, "新增") } + afterGroups.values
            .filter { !it.deleted && beforeGroups[it.syncId]?.deleted != false }.map { groupChange(it, "新增") }
        val deletedDetails = beforeVisible.values
            .filter { afterEntries[it.syncId]?.deleted == true }
            .map { change(it, "删除") } + beforeGroups.values
            .filter { !it.deleted && afterGroups[it.syncId]?.deleted == true }.map { groupChange(it, "删除") }
        val entryUpdates = beforeVisible.keys.mapNotNull { id ->
            val old = beforeEntries.getValue(id)
            val now = afterEntries[id]
            if (now != null && !now.deleted && entryMeaning(old) != entryMeaning(now)) change(now, "编辑") else null
        }
        val groupUpdates = beforeGroups.values.mapNotNull { old ->
            val now = afterGroups[old.syncId]
            if (now != null && !old.deleted && !now.deleted && (old.name != now.name || old.sortOrder != now.sortOrder)) {
                groupChange(now, "编辑")
            } else null
        }
        val updatedDetails = entryUpdates + groupUpdates
        return ClipboardSyncStats(
            added = addedDetails.size,
            mergedExisting = matched,
            updated = updatedDetails.size,
            deleted = deletedDetails.size,
            totalAfter = afterVisible.size,
            addedDetails = addedDetails,
            updatedDetails = updatedDetails,
            deletedDetails = deletedDetails,
        )
    }

    private fun change(entry: SyncEntry, action: String) = ClipboardSyncChange(
        syncId = entry.syncId,
        action = action,
        preview = when {
            entry.sensitive -> "敏感内容（不显示）"
            entry.type.startsWith("image/") -> "图片条目"
            else -> entry.text.replace("\\s+".toRegex(), " ").let { if (it.length > 80) it.take(80) + "…" else it }
        },
    )

    private fun entryMeaning(entry: SyncEntry): String = listOf(
        entry.text, entry.pinned, entry.timestamp, entry.type, entry.sensitive,
        entry.groupSyncId, entry.sortOrder,
    ).joinToString("\u0000")
}
