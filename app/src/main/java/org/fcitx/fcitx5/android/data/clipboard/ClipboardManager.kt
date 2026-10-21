/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import org.fcitx.fcitx5.android.BuildConfig
import androidx.annotation.Keep
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroupDao
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.timestamp
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {

    /**
     * 构造剪切板图片的可读 content URI（经 FileProvider 暴露 files/clipboard_images/，跨应用可读）。
     * 返回 null 表示无法构造（如图片不在应用外部文件目录内），此时由调用方降级处理。
     */
    fun imageContentUri(imagePath: String): Uri? = try {
        val file = File(imagePath)
        if (!file.exists()) return null
        androidx.core.content.FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
    } catch (e: Exception) {
        null
    }

    private lateinit var clbDb: ClipboardDatabase
    private lateinit var clbDao: ClipboardDao
    private lateinit var clbGroupDao: ClipboardGroupDao

    /** 供同步引擎读取 DAO（仅内部使用）。 */
    internal fun syncDao(): ClipboardDao = clbDao
    internal fun syncGroupDao(): ClipboardGroupDao = clbGroupDao
    internal fun syncDatabase(): ClipboardDatabase = clbDb

    private val syncPrefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences("clipboard_sync", android.content.Context.MODE_PRIVATE)
    }

    /** 同步写库/合并操作统一串行锁：保证多客户端/多并发下「合并→推进水位线→回收墓碑」严格顺序、不并行。 */
    private val syncMutex = Mutex()
    private var lastObservedRemoteTime: Long = 0L
    internal fun observeRemoteTime(remote: Long): Long {
        val persisted = syncPrefs.getLong("clipboard_logical_clock", 0L)
        val next = maxOf(System.currentTimeMillis(), remote, persisted + 1L, lastObservedRemoteTime + 1L)
        lastObservedRemoteTime = next
        check(syncPrefs.edit().putLong("clipboard_logical_clock", next).commit()) { "无法持久保存同步版本时钟" }
        return next
    }

    internal fun nextMutationTime(): Long = observeRemoteTime(0L)

    internal fun enableSyncRetention() {
        if (!syncPrefs.getBoolean("retain_complete_sync_collection", false)) {
            check(syncPrefs.edit().putBoolean("retain_complete_sync_collection", true).commit()) { "无法保存完整同步保留设置" }
        }
    }

    /** 在同步写库锁内执行 [block]，保证所有同步写操作串行、不颠倒顺序。 */
    private var syncBaselineReady = false
    internal suspend fun <T> withSyncLock(block: suspend () -> T): T = syncMutex.withLock {
        // Recover committed history before baseline generation, purge, export or sync.
        ClipboardOplog.replayPending(clbDb)
        if (!syncBaselineReady) {
            ClipboardOplog.withTransaction(clbDb) { ensureSyncDataUnlocked() }
            syncBaselineReady = true
        }
        block()
    }

    /**
     * 按设备分水位线的增量同步基准。
     * 每台设备各自记账 `last_sync_at_<deviceId>`，互不干扰（多设备时钟不一致也不误漏）。
     */
    private fun watermarkKey(deviceId: String): String = "last_sync_at_$deviceId"

    /** 本机水位线（用于决定上报哪些增量）。 */
    internal fun lastSyncAt(): Long = syncPrefs.getLong(watermarkKey(ClipboardOplog.deviceId), 0L)

    /** 推进某设备的水位线。 */
    internal fun setDeviceWatermark(deviceId: String, ts: Long) {
        syncPrefs.edit().putLong(watermarkKey(deviceId), ts).apply()
    }

    /** 读取某设备的水位线（墓碑按设备确认清理用；未知设备按 0 处理，不清理）。 */
    internal fun deviceWatermark(deviceId: String): Long = syncPrefs.getLong(watermarkKey(deviceId), 0L)

    /** 所有已知设备的水位线 map（deviceId -> 时刻，含本机）。 */
    internal fun allWatermarks(): Map<String, Long> {
        val all = HashMap<String, Long>()
        all[ClipboardOplog.deviceId] = lastSyncAt()
        syncPrefs.all.forEach { (k, v) ->
            if (k.startsWith("last_sync_at_") && v is Long) {
                all[k.removePrefix("last_sync_at_")] = v
            }
        }
        return all
    }

    /**
     * 合并对端分发来的全网水位线（多设备同步信息随快照传播）。
     * 每台设备取各自的最大值并持久化——这样即使换主机/主机下线，
     * 每台设备仍掌握全网水位线，墓碑确认不锁死在同一台主机。
     */
    internal fun mergeWatermarks(remote: Map<String, Long>, durable: Boolean = false) {
        if (remote.isEmpty()) return
        val editor = syncPrefs.edit()
        remote.forEach { (deviceId, ts) ->
            if (deviceId.isBlank()) return@forEach
            val cur = syncPrefs.getLong(watermarkKey(deviceId), 0L)
            if (ts > cur) editor.putLong(watermarkKey(deviceId), ts)
        }
        if (durable) check(editor.commit()) { "无法持久保存备份同步水位线" } else editor.apply()
    }

    /**
     * 墓碑按设备确认后清理。
     *
     * 删除墓碑带 `deleted=1 + syncId`，是删除意图的传播载体，须保留到所有设备都已越过它，
     * 否则对端存活副本会把它传回来造成“复活”。
     *
     * 安全条件：**所有已知设备**（含本机）的水位线都已 ≥ 墓碑.updatedAt（即所有设备都已同步越过该删除）。
     * 满足后该删除已送达全部设备，可物理回收墓碑。
     *
     * 调用方须持有 mutex（由同步引擎在持锁内调用）。
     */
    internal suspend fun recycleConfirmedTombstones() {
        // Tombstones are retained permanently.  A physical deletion here would
        // allow an old replica to re-introduce the entry on a later merge.
    }

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val clipboardManager = appContext.clipboardManager


    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    internal suspend fun clearLastEntryAndRefreshCount() {
        lastEntry = null
        updateItemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    var transformer: ((String) -> String)? = null

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val limitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimit

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch { removeOutdated() }
    }

    var lastEntry: ClipboardEntry? = null

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        onUpdateListeners.forEach { it.onUpdate(entry) }
    }

    fun init(context: Context) {
        clbDb = Room
            .databaseBuilder(context, ClipboardDatabase::class.java, "clbdb")
            .addMigrations(ClipboardDatabase.MIGRATION_8_9, ClipboardDatabase.MIGRATION_9_10, ClipboardDatabase.MIGRATION_10_11, ClipboardDatabase.MIGRATION_11_12)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    ClipboardDatabasePragmas.enableSecureDelete(db)
                }
            })
            // A downgrade must fail instead of silently destroying the clipboard database.
            .build()
        clbDao = clbDb.clipboardDao()
        clbGroupDao = clbDb.clipboardGroupDao()
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(limitPref.key, limitPref.getValue())
        limitPref.registerOnChangeListener(limitListener)
        launch {
            ClipboardSyncLog.sanitizeLegacy()
            ClipboardSyncNotificationStore.sanitizeLegacy()
            ClipboardPurge.purgeExpiredDeleted()
            updateItemCount()
            // 预建默认「图片」分组，使剪切板窗口的分组栏首屏即能看到它
            ensureDefaultImageGroup()
        }
        launch {
            while (isActive) {
                delay(60L * 60L * 1000L)
                runCatching { ClipboardPurge.purgeExpiredDeleted() }
            }
        }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    /** 被覆盖/拒绝的版本，仅供恢复面板使用，不直接加入可见列表。 */
    suspend fun revisions(): List<SyncRevision> = withSyncLock {
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        ClipboardOplog.readAll().asReversed().filter { op ->
            ClipboardRevisionRecovery.canRestore(op, ::recoverableImage)
        }.map { op ->
            SyncRevision(ClipboardOplog.revisionId(op), op.syncId, op.ts, op.deviceId, op.opType, op.entry, op.group)
        }
    }

    private fun recoverableImage(path: String): Boolean =
        ClipboardPurge.isPrivateClipboardImage(path) && File(path).isFile

    /** 高级管理列表：包含仍保留的删除墓碑；正文已清理的墓碑仍会返回。 */
    suspend fun advancedEntries(): List<ClipboardEntry> = withSyncLock {
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        clbDao.getAllIncludingDeleted().sortedWith(
            compareByDescending<ClipboardEntry> { it.pinned }
                .thenByDescending { it.timestamp }
                .thenByDescending { it.updatedAt }
        )
    }

    /** lastModifiedBy is source/last-modifier only, never a receiving-peer acknowledgement. */
    fun syncStatus(entry: ClipboardEntry): ClipboardSyncStatus {
        val receiptDevices = ClipboardReceiptStore.getAll(entry.syncId, ClipboardReceiptVersion.token(entry.updatedAt, entry.lastModifiedBy.ifBlank { ClipboardOplog.deviceId }))
        return when {
        receiptDevices.isNotEmpty() -> ClipboardSyncStatus(
            ClipboardSyncState.RECEIPT_CONFIRMED, peerReceiptConfirmed = true,
            receiptDeviceId = receiptDevices.joinToString(","),
            sourceOrLastModifiedDeviceId = entry.lastModifiedBy.takeIf { it.isNotBlank() },
        )
        !entry.hasBeenSynced && !entry.syncExposurePending -> ClipboardSyncStatus(
            ClipboardSyncState.LOCAL_ONLY,
            sourceOrLastModifiedDeviceId = entry.lastModifiedBy.takeIf { it.isNotBlank() },
        )
        entry.syncExposurePending -> ClipboardSyncStatus(
            ClipboardSyncState.EXPOSURE_PENDING,
            sourceOrLastModifiedDeviceId = entry.lastModifiedBy.takeIf { it.isNotBlank() },
        )
        else -> ClipboardSyncStatus(
            if (receiptDevices.isNotEmpty()) ClipboardSyncState.RECEIPT_CONFIRMED
            else ClipboardSyncState.SHARED_OR_LEGACY_UNKNOWN,
            peerReceiptConfirmed = receiptDevices.isNotEmpty(),
            receiptDeviceId = receiptDevices.joinToString(",").takeIf { it.isNotBlank() },
            sourceOrLastModifiedDeviceId = entry.lastModifiedBy.takeIf { it.isNotBlank() },
        )
        }
    }

    /** Monotonic receipt-store revision for refreshing advanced status labels. */
    fun receiptRevision(): Long = ClipboardReceiptStore.revision

    /** 显式恢复一个历史条目版本；恢复是新的本机版本，会正常进入同步。 */
    suspend fun restoreRevision(id: String): Boolean = withSyncLock {
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        restoreRevisionUnlocked(id)
    }

    private suspend fun restoreRevisionUnlocked(id: String): Boolean {
        val op = ClipboardOplog.readAll().firstOrNull { ClipboardOplog.revisionId(it) == id } ?: return false
        if (!ClipboardRevisionRecovery.canRestore(op, ::recoverableImage)) return false
        op.group?.let { payload ->
            val old = clbGroupDao.getBySyncId(op.syncId)
            val now = nextMutationTime()
            ClipboardOplog.withTransaction(clbDb) {
                val stable = op.syncId.ifBlank { ClipboardOplog.newSyncId() }
                val restored = (old ?: ClipboardGroup(name = payload.name, sortOrder = payload.sortOrder))
                    .copy(name = payload.name, sortOrder = payload.sortOrder, syncId = stable,
                        updatedAt = now, deleted = false, deviceId = ClipboardOplog.deviceId)
                clbGroupDao.upsert(restored)
                ClipboardOplog.append(ClipboardOplog.OP_GROUP_RENAME, stable,
                    group = ClipboardOplog.GroupOp(restored.name, restored.sortOrder), ts = now)
            }
            return true
        }
        val payload = op.entry ?: return false
        val local = clbDao.getBySyncId(op.syncId) ?: ClipboardEntry(text = payload.text, syncId = op.syncId)
        val imagePath = if (payload.type.startsWith("image/")) {
            imageBase64ToFile(payload.imageData, payload.type)
                ?: payload.imagePath?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile }
                ?: local.imagePath?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile }
                ?: return false
        } else null
        val now = nextMutationTime()
        ClipboardOplog.withTransaction(clbDb) {
            val group = payload.groupSyncId.takeIf { it.isNotBlank() }?.let { clbGroupDao.getBySyncId(it) }
                ?: payload.groupName.takeIf { it.isNotBlank() }?.let { clbGroupDao.getByName(it) }
            clbDao.upsert(local.copy(text = payload.text, pinned = payload.pinned, timestamp = payload.timestamp,
                type = payload.type, sensitive = payload.sensitive, deleted = false,
                imagePath = imagePath, sortOrder = payload.sortOrder,
                groupId = group?.id ?: 0, groupSyncId = group?.syncId ?: payload.groupSyncId,
                updatedAt = now, syncId = local.syncId.ifEmpty { ClipboardOplog.newSyncId() },
                lastModifiedBy = ClipboardOplog.deviceId))
            ClipboardOplog.append(ClipboardOplog.OP_INSERT, local.syncId.ifEmpty { op.syncId },
                payload.copy(deleted = false, imagePath = imagePath, imageData = null), ts = now)
        }
        updateItemCount(); return true
    }

    /** 恢复删除条目。过期或正文/图片已清理的墓碑不可恢复。 */
    suspend fun restoreDeleted(id: Int): Boolean = withSyncLock {
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        val row = clbDao.getAllIncludingDeleted().firstOrNull { it.id == id && it.deleted }
            ?: return@withSyncLock false
        val revision = ClipboardOplog.readAll().asReversed().firstOrNull {
            it.syncId == row.syncId && ClipboardRevisionRecovery.canRestore(it, ::recoverableImage)
        } ?: return@withSyncLock false
        restoreRevisionUnlocked(ClipboardOplog.revisionId(revision))
    }

    /**
     * 立即清除正文、私有图片及历史正文，但永久保留删除墓碑和删除操作传播。
     * 这是不可逆操作：后续 restoreDeleted 必然返回 false。
     */
    suspend fun forceDelete(id: Int): Boolean = ClipboardDelivery.withLock {
        withSyncLock {
            val old = clbDao.getAllIncludingDeleted().firstOrNull { it.id == id }
                ?: return@withSyncLock false
            if (!ClipboardDeletionPolicy.needsTombstone(old.hasBeenSynced, old.syncExposurePending)) {
                ClipboardOplog.withTransaction(clbDb) { deleteEntryUnlocked(old) }
                if (lastEntry?.id == id) lastEntry = null
                updateItemCount()
                return@withSyncLock true
            }
            val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
            val now = nextMutationTime()
            val paths = (listOfNotNull(old.imagePath) + ClipboardOplog.imagePathsForSyncId(stable)).distinct()
            ClipboardOplog.withTransaction(clbDb) {
                val tombstone = old.copy(text = "", imagePath = null, pinned = false,
                    syncId = stable, deleted = true, updatedAt = now,
                    lastModifiedBy = ClipboardOplog.deviceId,
                    hasBeenSynced = true, syncExposurePending = false)
                clbDao.upsert(tombstone)
                ClipboardOplog.append(ClipboardOplog.OP_DELETE, stable, entry = entryOp(tombstone, true), ts = now)
                // Soft rewrite removes all historical payloads while retaining the blank DELETE op.
                ClipboardOplog.stageDeletion(ClipboardOplog.Deletion(stable, now, hard = false, imagePaths = paths))
            }
            if (lastEntry?.id == id) lastEntry = null
            updateItemCount()
            true
        }
    }

    /** 默认「图片」分组的展示名（复制的图片自动归入此组）。 */
    const val DEFAULT_IMAGE_GROUP_NAME = "图片"

    private var defaultImageGroupId = 0

    /**
     * 确保默认「图片」分组存在并返回其 groupId。
     * 复制的图片条目（captureImageEntry）会自动归入该分组，便于集中查看。
     * 分组只存本地 Room 库，不写导出，不影响与小企鹅互操作。
     */
    suspend fun ensureDefaultImageGroup(): Int = withSyncLock {
        ClipboardOplog.withTransaction(clbDb) { ensureDefaultImageGroupUnlocked() }
    }

    private suspend fun ensureDefaultImageGroupUnlocked(): Int {
        if (defaultImageGroupId == 0 || clbGroupDao.get(defaultImageGroupId)?.deleted != false) {
            val existing = clbGroupDao.getAll().firstOrNull { it.name == DEFAULT_IMAGE_GROUP_NAME }
            defaultImageGroupId = existing?.id ?: clbGroupDao.insert(
                ClipboardGroup(name = DEFAULT_IMAGE_GROUP_NAME, sortOrder = 0,
                    syncId = ClipboardOplog.newSyncId(), updatedAt = nextMutationTime(),
                    deviceId = ClipboardOplog.deviceId)
            ).toInt()
            clbGroupDao.get(defaultImageGroupId)?.let { group ->
                if (!ClipboardOplog.hasBaselineOp(group.syncId)) ClipboardOplog.append(ClipboardOplog.OP_GROUP_ADD,
                    group.syncId, group = ClipboardOplog.GroupOp(group.name, group.sortOrder), ts = group.updatedAt)
            }
        }
        return defaultImageGroupId
    }

    suspend fun haveUnpinned() = clbDao.haveUnpinned()

    fun allEntries() = clbDao.allEntries()

    /** 全量读取未删除条目（供全屏管理页直接展示）。 */
    suspend fun getAll(): List<ClipboardEntry> = clbDao.getAll()

    /** 全量读取未删除条目，按手动排序优先、其次时间倒序（最新在最上面）。 */
    suspend fun getAllSorted(): List<ClipboardEntry> = clbDao.getAllSorted()

    /** 按时间倒序读取（最新在上，不做置顶优先）。 */
    suspend fun getAllByTimeDesc(): List<ClipboardEntry> = clbDao.getAllByTimeDesc()

    /** 按时间正序读取（最旧在上）。 */
    suspend fun getAllByTimeAsc(): List<ClipboardEntry> = clbDao.getAllByTimeAsc()

    /** 按更新时间倒序读取（最近变更/最近操作过的在顶）。 */
    suspend fun getAllByUpdatedAtDesc(): List<ClipboardEntry> = clbDao.getAllByUpdatedAtDesc()

    /** 按文本字典序读取（忽略大小写，其次时间倒序）。 */
    suspend fun getAllByTextAsc(): List<ClipboardEntry> = clbDao.getAllByTextAsc()

    /** 图片优先（图片在最前，其余按时间倒序）。 */
    suspend fun getAllByImageFirst(): List<ClipboardEntry> = clbDao.getAllByImageFirst()

    /** 纯文本优先（文本在最前，图片靠后，其余按时间倒序）。 */
    suspend fun getAllByTextFirst(): List<ClipboardEntry> = clbDao.getAllByTextFirst()

    /** 按自定义拖拽顺序读取（sortOrder 越大越靠前）。 */
    suspend fun getAllBySortOrder(): List<ClipboardEntry> = clbDao.getAllBySortOrder()

    /** 更新某条目的手动排序位置（拖动排序用）。 */
    suspend fun reorderEntry(id: Int, sortOrder: Long) = withSyncLock {
        ClipboardOplog.withTransaction(clbDb) {
            clbDao.get(id)?.let { old ->
                val now = nextMutationTime()
                val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
                val updated = old.copy(sortOrder = sortOrder, syncId = stable,
                    updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId)
                clbDao.upsert(updated)
                ClipboardOplog.append(ClipboardOplog.OP_SET_GROUP, stable,
                    entry = entryOp(updated, updated.deleted), ts = now)
            }
        }
    }

    private suspend fun entryOp(e: ClipboardEntry, deleted: Boolean = e.deleted) = ClipboardOplog.EntryOp(
        text = e.text, pinned = e.pinned, timestamp = e.timestamp, type = e.type,
        sensitive = e.sensitive, deleted = deleted, groupName = groupNameById(e.groupId),
        imagePath = e.imagePath, groupSyncId = e.groupSyncId, sortOrder = e.sortOrder)

    suspend fun pin(id: Int) = mutateEntry(id, ClipboardOplog.OP_PIN) { it.copy(pinned = true) }
    suspend fun unpin(id: Int) = mutateEntry(id, ClipboardOplog.OP_PIN) { it.copy(pinned = false) }

    suspend fun updateText(id: Int, text: String) = mutateEntry(id, ClipboardOplog.OP_UPDATE_TEXT) { it.copy(text = text) }

    private suspend fun mutateEntry(id: Int, opType: String, transform: (ClipboardEntry) -> ClipboardEntry) = withSyncLock {
        var result: ClipboardEntry? = null
        ClipboardOplog.withTransaction(clbDb) {
            val old = clbDao.get(id) ?: return@withTransaction
            val now = nextMutationTime()
            val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
            val updated = transform(old).copy(syncId = stable, updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId)
            clbDao.upsert(updated)
            ClipboardOplog.append(opType, stable, entry = entryOp(updated, false), ts = now)
            result = updated
        }
        result?.let { if (lastEntry?.id == id) updateLastEntry(it) }
    }

    /** Unexposed local rows are erased; shared/uncertain rows retain recoverable content for seven days. */
    internal suspend fun deleteEntryUnlocked(old: ClipboardEntry, now: Long = nextMutationTime()) {
        val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
        val tombstone = ClipboardDeletionPolicy.retainedDeletion(old, stable, now, ClipboardOplog.deviceId)
        if (tombstone != null) {
            // Ensure a live revision exists even for legacy/imported rows without an INSERT.
            ClipboardOplog.archive(stable, old.updatedAt, old.lastModifiedBy,
                entry = entryOp(old, false))
            clbDao.upsert(tombstone)
            ClipboardOplog.append(ClipboardOplog.OP_DELETE, stable, entry = entryOp(tombstone, true), ts = now)
        } else {
            val paths = (listOfNotNull(old.imagePath) + ClipboardOplog.imagePathsForSyncId(stable)).distinct()
            clbDao.deleteById(old.id)
            ClipboardOplog.stageDeletion(ClipboardOplog.Deletion(stable, now, hard = true, imagePaths = paths))
        }
    }

    suspend fun delete(id: Int) = ClipboardDelivery.withLock {
        withSyncLock {
            if (lastEntry?.id == id) lastEntry = null
            ClipboardOplog.withTransaction(clbDb) { clbDao.get(id)?.let { deleteEntryUnlocked(it) } }
            updateItemCount()
        }
    }

    suspend fun deleteAll(skipPinned: Boolean = true): IntArray = ClipboardDelivery.withLock {
        withSyncLock {
            val ids = if (skipPinned) clbDao.findUnpinnedIds() else clbDao.findAllIds()
            if (lastEntry?.id?.let { it in ids } == true) lastEntry = null
            ClipboardOplog.withTransaction(clbDb) {
                // Read once and persist one logical clock value, not one fsync/query per selected row.
                val selected = ids.toHashSet()
                val now = nextMutationTime()
                clbDao.getAll().filter { it.id in selected }.forEach { deleteEntryUnlocked(it, now) }
            }
            updateItemCount(); ids
        }
    }

    /** Retained for source compatibility; explicit recovery goes through the validated history panel. */
    suspend fun undoDelete(vararg ids: Int) = Unit

    suspend fun realDelete() = withSyncLock {
        // 只物理清理“从未同步过”的本地残留（无 syncId）。
        // 带 syncId 的墓碑必须保留：它们是删除意图的传播载体，
        // 若删掉则本机快照不再包含这些条目，对端存活副本会把它们传回来造成“复活”。
        ClipboardOplog.withTransaction(clbDb) { clbDao.realDeleteLocalOnly() }
    }

    /** Main-callable retention entry point; returns the number of deleted rows purged. */
    suspend fun purgeExpiredDeleted(
        now: Long = System.currentTimeMillis(),
        retentionMs: Long = ClipboardPurge.DEFAULT_RETENTION_MS,
    ): Int = ClipboardPurge.purgeExpiredDeleted(now, retentionMs)

    /* ===== 搜索（额外配置，快速寻找） ===== */

    fun search(query: String) = clbDao.search(query)

    /** 搜索并返回完整列表（供全屏搜索结果页直接展示，非分页）。 */
    suspend fun searchAll(query: String): List<ClipboardEntry> = clbDao.searchAll(query)

    /** 搜索限定在某分组内：groupId>0 用该组；groupId==0 全量；groupId==-1 未分组。 */
    fun searchIn(groupId: Int, query: String) = when {
        groupId > 0 -> clbDao.searchInGroup(groupId, query)
        groupId == -1 -> clbDao.searchUngrouped(query)
        else -> clbDao.search(query)
    }

    /* ===== 分组（额外配置，快速寻找） ===== */

    suspend fun getGroups(): List<ClipboardGroup> = clbGroupDao.getAll()

    suspend fun getGroup(id: Int): ClipboardGroup? = clbGroupDao.get(id)

    suspend fun addGroup(name: String): Int = withSyncLock {
        ClipboardOplog.withTransaction(clbDb) {
            val groups = clbGroupDao.getAll(); val order = (groups.maxOfOrNull { it.sortOrder } ?: 0) + 1
            val now = nextMutationTime(); val syncId = ClipboardOplog.newSyncId()
            val id = clbGroupDao.insert(ClipboardGroup(name = name, sortOrder = order, syncId = syncId, updatedAt = now, deviceId = ClipboardOplog.deviceId)).toInt()
            ClipboardOplog.append(ClipboardOplog.OP_GROUP_ADD, syncId, group = ClipboardOplog.GroupOp(name, order), ts = now); id
        }
    }

    suspend fun renameGroup(id: Int, name: String) = withSyncLock {
        ClipboardOplog.withTransaction(clbDb) {
            val old = clbGroupDao.get(id) ?: return@withTransaction
            val now = nextMutationTime(); val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
            val updated = old.copy(name = name, syncId = stable, updatedAt = now, deviceId = ClipboardOplog.deviceId)
            clbGroupDao.upsert(updated)
            ClipboardOplog.append(ClipboardOplog.OP_GROUP_RENAME, stable, group = ClipboardOplog.GroupOp(name, old.sortOrder), ts = now)
        }
    }

    suspend fun reorderGroup(id: Int, sortOrder: Int) = withSyncLock {
        ClipboardOplog.withTransaction(clbDb) {
            clbGroupDao.get(id)?.let { old ->
                val now = nextMutationTime(); val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
                clbGroupDao.upsert(old.copy(sortOrder = sortOrder, syncId = stable,
                    updatedAt = now, deviceId = ClipboardOplog.deviceId))
                ClipboardOplog.append(ClipboardOplog.OP_GROUP_RENAME, stable,
                    group = ClipboardOplog.GroupOp(old.name, sortOrder), ts = now)
            }
        }
    }

    /** 删除分组，并把该组下条目移回「未分组」。 */
    suspend fun deleteGroup(id: Int) = withSyncLock {
        ClipboardOplog.withTransaction(clbDb) {
            val group = clbGroupDao.get(id) ?: return@withTransaction
            val now = nextMutationTime(); val stable = group.syncId.ifEmpty { ClipboardOplog.newSyncId() }
            clbDao.getAllIncludingDeleted().filter { it.groupId == id }.forEach { old ->
                val ets = nextMutationTime(); val es = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
                val cleared = old.copy(groupId = 0, groupSyncId = "", syncId = es, updatedAt = ets, lastModifiedBy = ClipboardOplog.deviceId)
                clbDao.upsert(cleared)
                ClipboardOplog.append(ClipboardOplog.OP_SET_GROUP, es, entry = entryOp(cleared, cleared.deleted), ts = ets)
            }
            clbGroupDao.markDeleted(id, now, ClipboardOplog.deviceId)
            clbGroupDao.setSyncInfo(id, stable, now, ClipboardOplog.deviceId)
            ClipboardOplog.append(ClipboardOplog.OP_GROUP_DELETE, stable, group = ClipboardOplog.GroupOp(group.name, group.sortOrder), ts = now)
        }
    }

    suspend fun setEntryGroup(entryId: Int, groupId: Int) = withSyncLock {
        ClipboardOplog.withTransaction(clbDb) {
            val old = clbDao.get(entryId) ?: return@withTransaction
            val group = if (groupId == 0) null else clbGroupDao.get(groupId)
            val now = nextMutationTime(); val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
            val updated = old.copy(groupId = group?.id ?: 0, groupSyncId = group?.syncId ?: "", syncId = stable, updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId)
            clbDao.upsert(updated)
            ClipboardOplog.append(ClipboardOplog.OP_SET_GROUP, stable, entry = entryOp(updated, false), ts = now)
        }
    }

    fun entriesInGroup(groupId: Int) = if (groupId > 0) clbDao.entriesInGroup(groupId) else clbDao.ungroupedEntries()

    suspend fun countInGroup(groupId: Int) = clbDao.countInGroup(groupId)

    private suspend fun groupNameById(groupId: Int): String {
        if (groupId == 0) return ""
        // 分组名可能尚未缓存；直接从库读取，兼顾同步定位
        return runCatching { clbGroupDao.get(groupId)?.name }.getOrNull() ?: ""
    }

    /** 供同步引擎读取本地完整数据。 */
    suspend fun readAllIncludingDeleted() = clbDao.getAllIncludingDeleted()
    suspend fun readAllGroupsIncludingAll() = clbGroupDao.getAllIncludingAll()

    /**
     * 同步前「托底生成」：扫描本地全部非删除条目与分组，为尚未出现在 oplog 中的
     * 历史数据补生成基线操作（INSERT / GROUP_ADD），使旧数据也能被局域网同步拉取，
     * 而不只限于近期复制产生的日志。生成过的数据（syncId 已在 oplog）会跳过。
     * 返回本轮新生成的基线条数。
     */
    suspend fun ensureSyncData(): Int = withSyncLock { ClipboardOplog.withTransaction(clbDb) { ensureSyncDataUnlocked() } }

    /** Caller must hold withSyncLock and, for migration/snapshot, the Room transaction. */
    internal suspend fun ensureSyncDataUnlocked(): Int {
        var generated = 0
        // Advance the logical clock past every persisted version without
        // rewriting those versions (old timestamps are part of their identity).
        val persistedMax = maxOf(
            clbDao.getAllIncludingDeleted().maxOfOrNull { maxOf(it.updatedAt, it.timestamp) } ?: 0L,
            clbGroupDao.getAllIncludingAll().maxOfOrNull { it.updatedAt } ?: 0L,
            ClipboardOplog.readAll().maxOfOrNull { it.ts } ?: 0L,
        )
        observeRemoteTime(persistedMax)
        val now = nextMutationTime()

        // 旧版本曾物理删除分组；把 oplog 中的删除恢复为永久墓碑后再做基线。
        ClipboardOplog.readAll().filter { it.opType == ClipboardOplog.OP_GROUP_DELETE && it.syncId.isNotBlank() }.forEach { op ->
            if (clbGroupDao.getBySyncId(op.syncId) == null) {
                val p = op.group ?: ClipboardOplog.GroupOp("", 0)
                clbGroupDao.insert(ClipboardGroup(name = p.name, sortOrder = p.sortOrder, syncId = op.syncId, updatedAt = op.ts, deleted = true, deviceId = op.deviceId))
                generated++
            }
        }

        // 1. 分组：未建基线的补 GROUP_ADD（已删分组已被物理删除、不会出现在库里，其删除墓碑在 deleteGroup 时已写）
        clbGroupDao.getAllIncludingAll().forEach { g ->
            val existingSyncId = g.syncId.takeIf { it.isNotBlank() }
            val syncId = existingSyncId ?: ClipboardOplog.newSyncId()
            if (existingSyncId == null || g.deviceId.isBlank()) {
                clbGroupDao.setSyncInfo(g.id, syncId, g.updatedAt, g.deviceId.ifBlank { ClipboardOplog.deviceId })
            }
            if (!ClipboardOplog.hasBaselineOp(syncId)) {
                ClipboardOplog.append(
                    ClipboardOplog.OP_GROUP_ADD, syncId,
                    group = ClipboardOplog.GroupOp(g.name, g.sortOrder), ts = g.updatedAt
                )
                generated++
            }
        }

        // 2. 条目
        clbDao.getAllIncludingDeleted().forEach { entry ->
            if (entry.groupId != 0) {
                clbGroupDao.get(entry.groupId)?.syncId?.takeIf { it.isNotBlank() }?.let {
                    if (entry.groupSyncId != it) clbDao.setGroup(entry.id, entry.groupId, it)
                }
            }
            val existingSyncId = entry.syncId.takeIf { it.isNotBlank() }
            val syncId = existingSyncId ?: ClipboardOplog.newSyncId()
            val finalTs = entry
                .let { if (it.updatedAt > 0) it.updatedAt else it.timestamp }
                .let { if (it > 0) it else now }
            if (existingSyncId == null || entry.lastModifiedBy.isBlank() || entry.updatedAt <= 0) {
                clbDao.upsert(entry.copy(syncId = syncId, updatedAt = finalTs,
                    groupSyncId = entry.groupSyncId.ifBlank { clbGroupDao.get(entry.groupId)?.syncId.orEmpty() },
                    lastModifiedBy = entry.lastModifiedBy.ifBlank { ClipboardOplog.deviceId }))
            }

            if (!ClipboardOplog.hasBaselineOp(syncId)) {
                // 从未发过 INSERT 基线：无论是否已删先建基线（保证对端认知该 id / 作为删除前提）
                if (existingSyncId == null) clbDao.setSyncInfo(entry.id, syncId, finalTs)
                ClipboardOplog.append(
                    ClipboardOplog.OP_INSERT, syncId,
                    ClipboardOplog.EntryOp(
                        text = entry.text,
                        pinned = entry.pinned,
                        timestamp = entry.timestamp,
                        type = entry.type,
                        sensitive = entry.sensitive,
                        deleted = false,
                        groupName = groupNameById(entry.groupId),
                        imagePath = entry.imagePath,
                        groupSyncId = entry.groupSyncId.ifBlank { clbGroupDao.get(entry.groupId)?.syncId.orEmpty() },
                        sortOrder = entry.sortOrder,
                    ),
                    ts = finalTs
                )
                generated++
            }
            // 已建基线且条目已删、但未发过删除墓碑 → 补 DELETE（同步删除到对端）
            if (entry.deleted && !ClipboardOplog.hasDeleteOp(syncId)) {
                ClipboardOplog.append(
                    ClipboardOplog.OP_DELETE, syncId,
                    ClipboardOplog.EntryOp(
                        text = entry.text,
                        pinned = entry.pinned,
                        timestamp = entry.timestamp,
                        type = entry.type,
                        sensitive = entry.sensitive,
                        deleted = true,
                        groupName = groupNameById(entry.groupId),
                        imagePath = entry.imagePath,
                        groupSyncId = entry.groupSyncId.ifBlank { clbGroupDao.get(entry.groupId)?.syncId.orEmpty() },
                        sortOrder = entry.sortOrder,
                    ),
                    ts = finalTs
                )
                generated++
            }
        }

        return generated
    }

    /* ===== 导入 / 导出 ===== */

    /** 导出一致性快照；不复制 Room 文件，因此不受 WAL 竞争影响。 */
    suspend fun exportTo(output: java.io.OutputStream): Int = withSyncLock {
        val payload = ClipboardOplog.withTransaction(clbDb) { manualBackupPacketUnlocked() }
        val packageName = org.fcitx.fcitx5.android.BuildConfig.APPLICATION_ID
        val versionName = org.fcitx.fcitx5.android.BuildConfig.VERSION_NAME
        ClipboardExportManager.export(payload.packet, packageName, versionName, output) { key -> payload.images[key]?.invoke() }
    }

    /**
     * 从输入流导入剪切板数据（统一 zip 载体）。
     * 读取便携快照或兼容的旧数据库，按稳定身份和版本合并，不清理本机数据。
     * 返回新增、更新或图片补全的条目与分组数。
     */
    suspend fun importFrom(input: InputStream): Int {
        // Parsing is intentionally outside the lock; malformed/incorrect入口 throws a
        // useful error instead of being reported as a successful zero-row import.
        val imported = ClipboardExportManager.importArchive(input)
        val changed = try { ClipboardManualRestore.merge(imported) } finally { imported.close() }
        updateItemCount()
        return changed
    }

    /** Must run under syncMutex and a Room transaction. It deliberately does not call
     * ClipboardSnapshotStore.capture(), whose network path applies retention purge. */
    private data class ManualBackupPayload(val packet: ClipboardSyncPacket, val images: Map<String, () -> InputStream>)
    private suspend fun manualBackupPacketUnlocked(): ManualBackupPayload {
        val groups = clbGroupDao.getAllIncludingAll()
        val stableGroups = groups.associate { it.id to it.syncId.ifBlank { "legacy:${ClipboardOplog.deviceId}:group:${it.id}" } }
        val byId = groups.associateBy { it.id }
        val images = linkedMapOf<String, () -> InputStream>()
        fun register(key: String, path: String?, embedded: String? = null) {
            val file = path?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) }?.let(::File)?.takeIf { it.isFile }
            when {
                file != null -> images[key] = { file.inputStream() }
                !embedded.isNullOrBlank() -> images[key] = {
                    android.util.Base64InputStream(embedded.byteInputStream(), android.util.Base64.DEFAULT)
                }
                !path.isNullOrBlank() -> error("备份图片原文件已缺失，未完成备份：${File(path).name}")
            }
        }
        val entries = clbDao.getAllIncludingDeleted().map { e ->
            val id = e.syncId.ifBlank { "legacy:${ClipboardOplog.deviceId}:entry:${e.id}" }
            if (e.type.startsWith("image/")) {
                register("e:$id", e.imagePath)
                check(e.deleted || images.containsKey("e:$id")) { "剪贴板图片缺失，未完成备份" }
            }
            SyncEntry(syncId = id, text = e.text, pinned = e.pinned, timestamp = e.timestamp,
                type = e.type, sensitive = e.sensitive, deleted = e.deleted, groupName = byId[e.groupId]?.name.orEmpty(),
                updatedAt = e.updatedAt, deviceId = e.lastModifiedBy,
                groupSyncId = e.groupSyncId.ifBlank { stableGroups[e.groupId].orEmpty() }, sortOrder = e.sortOrder)
        }
        val syncGroups = groups.map { g -> SyncGroup(stableGroups.getValue(g.id), g.name, g.sortOrder, g.updatedAt, g.deleted, g.deviceId) }
        val history = ClipboardOplog.readAll().map { op ->
            val id = ClipboardOplog.revisionId(op)
            val p = op.entry?.let { e ->
                if (e.type.startsWith("image/")) register("h:$id", e.imagePath, e.imageData)
                e.copy(imagePath = null, imageData = null)
            }
            SyncRevision(id, op.syncId, op.ts, op.deviceId, op.opType, p, op.group)
        }
        return ManualBackupPayload(ClipboardSyncPacket(deviceId = ClipboardOplog.deviceId, entries = entries,
            groups = syncGroups, watermarks = allWatermarks(), history = history), images)
    }
    suspend fun nukeTable() = withSyncLock {
        withContext(coroutineContext) {
            clbDb.clearAllTables()
            updateItemCount()
        }
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    /** 应用主动写系统剪贴板（图片发送/粘贴）后抑制重新捕获的截止时刻（毫秒）。 */
    @Volatile private var suppressNextCaptureUntil = 0L

    /**
     * 把图片写入系统剪贴板（供跨应用粘贴/发送），并抑制因此触发的重新捕获，
     * 避免应用自己写回的图片又被捕获成一条新记录（产生重复条目）。
     * @return true 表示写入成功。
     */
    fun copyImageToSystemClipboard(imagePath: String, label: String = "clipboard_image"): Boolean {
        val file = File(imagePath)
        if (!file.exists()) return false
        val uri = imageContentUri(imagePath) ?: return false
        val clip = ClipData.newUri(appContext.contentResolver, label, uri)
        suppressNextCaptureUntil = System.currentTimeMillis() + 3000
        clipboardManager.setPrimaryClip(clip)
        return true
    }

    override fun onPrimaryClipChanged() {
        // 本次剪贴板变更是本应用主动写入图片引起的，跳过捕获（避免重复条目）
        if (System.currentTimeMillis() < suppressNextCaptureUntil) {
            suppressNextCaptureUntil = 0
            return
        }
        val clip = clipboardManager.primaryClip ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            ClipboardDelivery.withLock { withSyncLock {
                val imageEntry = captureImageEntry(clip)
                val entry = imageEntry ?: ClipboardEntry.fromClipData(clip, transformer) ?: return@withSyncLock
                if (!entry.type.startsWith("image/") && entry.text.isBlank()) return@withSyncLock
                val isImage = entry.type.startsWith("image/") && entry.imagePath != null
                try {
                    val captured = ClipboardOplog.withTransaction(clbDb) {
                    if (!isImage) {
                        clbDao.find(entry.text, entry.sensitive)?.let { old ->
                            val now = nextMutationTime()
                            val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
                            val updated = old.copy(timestamp = entry.timestamp, syncId = stable,
                                updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId)
                            clbDao.upsert(updated)
                            ClipboardOplog.append(ClipboardOplog.OP_INSERT, stable,
                                entry = entryOp(updated, false), ts = now)
                            return@withTransaction updated
                        }
                    }
                    // 图片条目自动归入默认「图片」分组，便于集中查看
                    val now = nextMutationTime()
                    val entryToInsert = if (isImage) {
                        val groupId = ensureDefaultImageGroupUnlocked()
                        entry.copy(groupId = groupId, groupSyncId = clbGroupDao.get(groupId)?.syncId.orEmpty())
                    } else entry
                    val syncId = ClipboardOplog.newSyncId()
                    val newEntry = entryToInsert.copy(syncId = syncId, updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId, hasBeenSynced = false)
                    val rowId = clbDao.insert(newEntry)
                    // 记录「复制」操作日志（供局域网增量同步）
                    ClipboardOplog.append(
                        ClipboardOplog.OP_INSERT,
                        syncId,
                        ClipboardOplog.EntryOp(
                            text = entryToInsert.text,
                            pinned = entryToInsert.pinned,
                            timestamp = entryToInsert.timestamp,
                            type = entryToInsert.type,
                            sensitive = entryToInsert.sensitive,
                            deleted = false,
                            groupName = groupNameById(entryToInsert.groupId),
                            imagePath = entryToInsert.imagePath,
                            groupSyncId = entryToInsert.groupSyncId,
                            sortOrder = entryToInsert.sortOrder,
                        ),
                        ts = now
                    )
                    removeOutdatedUnlocked()
                    clbDao.get(rowId) ?: newEntry.copy(id = rowId.toInt())
                    }
                    updateLastEntry(captured)
                    updateItemCount()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database (%s)", exception.javaClass.simpleName)
                    updateLastEntry(entry)
                }
            } }
        }
    }

    private suspend fun removeOutdated() = ClipboardDelivery.withLock { withSyncLock { ClipboardOplog.withTransaction(clbDb) { removeOutdatedUnlocked() } } }

    private suspend fun removeOutdatedUnlocked() {
        // A replica's local history limit must never silently delete another device's shared data.
        if (syncPrefs.getBoolean("retain_complete_sync_collection", false)) return
        val limit = limitPref.getValue()
        val unpinned = clbDao.getAllUnpinned()
        if (unpinned.size > limit) {
            val last = unpinned
                .sortedBy { it.id }
                .getOrNull(unpinned.size - limit)
            val cutoff = last?.timestamp ?: nextMutationTime()
            // 找出真正超上限需清理的条目 id
            val toRemove = clbDao.findUnpinnedOlderThan(cutoff)
            if (toRemove.isEmpty()) return
            val selected = toRemove.toHashSet()
            val now = nextMutationTime()
            unpinned.filter { it.id in selected }.forEach { deleteEntryUnlocked(it, now) }
            updateItemCount()
        }
    }

    private fun captureImageEntry(clip: android.content.ClipData): ClipboardEntry? {
        return try {
            val desc = clip.description
            if (clip.itemCount < 1) return null
            // 按 MIME 判定是否为图片，而不是看 item.text：
            // 浏览器/应用复制图片时 item.text 常带图片 URL 或 alt 文本，若据此放弃会把图存成链接。
            // 遍历所有 MIME type（某些应用把 text/uri-list 排在 image/* 前面）
            var mime: String? = null
            for (i in 0 until desc.mimeTypeCount) {
                val m = desc.getMimeType(i)
                if (m != null && m.startsWith("image/")) { mime = m; break }
            }
            if (mime == null) return null
            val item = clip.getItemAt(0)
            val uri = item.uri ?: return null
            val imageDir = File(appContext.getExternalFilesDir(null), "clipboard_images").apply { mkdirs() }
            val ext = when (mime) {
                "image/png" -> ".png"
                "image/gif" -> ".gif"
                "image/webp" -> ".webp"
                else -> ".jpg"
            }
            val file = File(imageDir, "${System.currentTimeMillis()}$ext")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            } ?: return null
            if (file.length() == 0L) {
                file.delete()
                return null
            }
            ClipboardEntry(
                text = "图片",
                timestamp = clip.timestamp(),
                type = mime,
                imagePath = file.absolutePath,
            )
        } catch (e: Exception) {
            Timber.w("Failed to capture clipboard image (%s)", e.javaClass.simpleName)
            null
        }
    }

}
