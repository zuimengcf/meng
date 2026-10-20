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
import androidx.room.withTransaction
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
        if (!syncBaselineReady) {
            clbDb.withTransaction { ensureSyncDataUnlocked() }
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
    internal fun mergeWatermarks(remote: Map<String, Long>) {
        if (remote.isEmpty()) return
        val editor = syncPrefs.edit()
        remote.forEach { (deviceId, ts) ->
            if (deviceId.isBlank()) return@forEach
            val cur = syncPrefs.getLong(watermarkKey(deviceId), 0L)
            if (ts > cur) editor.putLong(watermarkKey(deviceId), ts)
        }
        editor.apply()
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
            .addMigrations(ClipboardDatabase.MIGRATION_8_9, ClipboardDatabase.MIGRATION_9_10)
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
        ClipboardOplog.readAll().asReversed().map { op ->
            SyncRevision(ClipboardOplog.revisionId(op), op.syncId, op.ts, op.deviceId, op.opType, op.entry, op.group)
        }
    }

    /** 显式恢复一个历史条目版本；恢复是新的本机版本，会正常进入同步。 */
    suspend fun restoreRevision(id: String): Boolean = withSyncLock {
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        val op = ClipboardOplog.readAll().firstOrNull { ClipboardOplog.revisionId(it) == id } ?: return@withSyncLock false
        if ((op.opType == ClipboardOplog.OP_DELETE && op.entry == null) || ClipboardOplog.isPurgedPayload(op)) return@withSyncLock false
        op.group?.let { payload ->
            val old = clbGroupDao.getBySyncId(op.syncId)
            val now = nextMutationTime()
            clbDb.withTransaction {
                val stable = op.syncId.ifBlank { ClipboardOplog.newSyncId() }
                val restored = (old ?: ClipboardGroup(name = payload.name, sortOrder = payload.sortOrder))
                    .copy(name = payload.name, sortOrder = payload.sortOrder, syncId = stable,
                        updatedAt = now, deleted = false, deviceId = ClipboardOplog.deviceId)
                clbGroupDao.upsert(restored)
                ClipboardOplog.append(ClipboardOplog.OP_GROUP_RENAME, stable,
                    group = ClipboardOplog.GroupOp(restored.name, restored.sortOrder), ts = now)
            }
            return@withSyncLock true
        }
        val payload = op.entry ?: return@withSyncLock false
        val local = clbDao.getBySyncId(op.syncId) ?: ClipboardEntry(text = payload.text, syncId = op.syncId)
        val imagePath = if (payload.type.startsWith("image/")) {
            imageBase64ToFile(payload.imageData, payload.type)
                ?: payload.imagePath?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile }
                ?: local.imagePath?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile }
                ?: return@withSyncLock false
        } else null
        val now = nextMutationTime()
        clbDb.withTransaction {
            val group = payload.groupSyncId.takeIf { it.isNotBlank() }?.let { clbGroupDao.getBySyncId(it) }
                ?: payload.groupName.takeIf { it.isNotBlank() }?.let { clbGroupDao.getByName(it) }
            clbDao.upsert(local.copy(text = payload.text, pinned = payload.pinned, timestamp = payload.timestamp,
                type = payload.type, sensitive = payload.sensitive, deleted = payload.deleted,
                imagePath = imagePath, sortOrder = payload.sortOrder,
                groupId = group?.id ?: 0, groupSyncId = group?.syncId ?: payload.groupSyncId,
                updatedAt = now, syncId = local.syncId.ifEmpty { ClipboardOplog.newSyncId() },
                lastModifiedBy = ClipboardOplog.deviceId))
            ClipboardOplog.append(op.opType, local.syncId.ifEmpty { op.syncId }, payload.copy(deleted = payload.deleted), ts = now)
        }
        updateItemCount(); true
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
        clbDb.withTransaction { ensureDefaultImageGroupUnlocked() }
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
        clbDb.withTransaction {
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
        clbDb.withTransaction {
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

    suspend fun delete(id: Int) = withSyncLock {
        clbDb.withTransaction {
            val old = clbDao.get(id) ?: return@withTransaction
            val now = nextMutationTime(); val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
            clbDao.upsert(old.copy(syncId = stable, deleted = true, updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId))
            ClipboardOplog.append(ClipboardOplog.OP_DELETE, stable, entry = entryOp(old, true), ts = now)
        }
        updateItemCount()
    }

    suspend fun deleteAll(skipPinned: Boolean = true): IntArray = withSyncLock {
        val ids = if (skipPinned) clbDao.findUnpinnedIds() else clbDao.findAllIds()
        clbDb.withTransaction {
            ids.forEach { id ->
                val old = clbDao.get(id) ?: return@forEach
                val now = nextMutationTime(); val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
                clbDao.upsert(old.copy(syncId = stable, deleted = true, updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId))
                ClipboardOplog.append(ClipboardOplog.OP_DELETE, stable, entry = entryOp(old, true), ts = now)
            }
        }
        updateItemCount(); ids
    }

    suspend fun undoDelete(vararg ids: Int) = withSyncLock {
        clbDb.withTransaction {
            ids.forEach { id ->
                val old = clbDao.getAllIncludingDeleted().firstOrNull { it.id == id && it.deleted } ?: return@forEach
                val now = nextMutationTime(); val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
                val restored = old.copy(syncId = stable, deleted = false, updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId)
                clbDao.upsert(restored)
                ClipboardOplog.append(ClipboardOplog.OP_INSERT, stable, entry = entryOp(restored, false), ts = now)
            }
        }
        updateItemCount()
    }

    suspend fun realDelete() = withSyncLock {
        // 只物理清理“从未同步过”的本地残留（无 syncId）。
        // 带 syncId 的墓碑必须保留：它们是删除意图的传播载体，
        // 若删掉则本机快照不再包含这些条目，对端存活副本会把它们传回来造成“复活”。
        clbDb.withTransaction { clbDao.realDeleteLocalOnly() }
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
        clbDb.withTransaction {
            val groups = clbGroupDao.getAll(); val order = (groups.maxOfOrNull { it.sortOrder } ?: 0) + 1
            val now = nextMutationTime(); val syncId = ClipboardOplog.newSyncId()
            val id = clbGroupDao.insert(ClipboardGroup(name = name, sortOrder = order, syncId = syncId, updatedAt = now, deviceId = ClipboardOplog.deviceId)).toInt()
            ClipboardOplog.append(ClipboardOplog.OP_GROUP_ADD, syncId, group = ClipboardOplog.GroupOp(name, order), ts = now); id
        }
    }

    suspend fun renameGroup(id: Int, name: String) = withSyncLock {
        clbDb.withTransaction {
            val old = clbGroupDao.get(id) ?: return@withTransaction
            val now = nextMutationTime(); val stable = old.syncId.ifEmpty { ClipboardOplog.newSyncId() }
            val updated = old.copy(name = name, syncId = stable, updatedAt = now, deviceId = ClipboardOplog.deviceId)
            clbGroupDao.upsert(updated)
            ClipboardOplog.append(ClipboardOplog.OP_GROUP_RENAME, stable, group = ClipboardOplog.GroupOp(name, old.sortOrder), ts = now)
        }
    }

    suspend fun reorderGroup(id: Int, sortOrder: Int) = withSyncLock {
        clbDb.withTransaction {
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
        clbDb.withTransaction {
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
        clbDb.withTransaction {
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
    suspend fun ensureSyncData(): Int = withSyncLock { clbDb.withTransaction { ensureSyncDataUnlocked() } }

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

    /** 导出本机剪切板数据库文件（含条目 + 分组）为 zip；返回导出条数。 */
    suspend fun exportTo(output: java.io.OutputStream): Int {
        // Room 默认启用 WAL，最新数据可能在 clbdb-wal 而未合并回主文件。
        // 先强制 checkpoint 把 WAL 合并进主 clbdb，保证导出的 db 完整。
        clbDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        val dbFile = appContext.getDatabasePath(ClipboardDatabase.DB_NAME)
        val packageName = org.fcitx.fcitx5.android.BuildConfig.APPLICATION_ID
        val versionName = org.fcitx.fcitx5.android.BuildConfig.VERSION_NAME
        return ClipboardExportManager.export(dbFile, packageName, versionName, output)
    }

    /**
     * 从输入流导入剪切板数据（统一 zip 载体）。
     * 只读解析 zip 里的 `databases/clbdb`，逐条合并写入本机库（保留本机数据，跳过重复）。
     * 返回实际新增条数。
     */
    suspend fun importFrom(input: InputStream): Int = withSyncLock {
        val parsed = ClipboardExportManager.importFromDb(input) ?: return@withSyncLock 0
        val incoming = parsed.first
        val groups = parsed.second
        var added = 0
        clbDb.withTransaction {
            // 1. 分组：重建分组，映射 旧分组id -> 新groupId
            val groupIdByOldId = HashMap<Int, Int>()
            groups.forEach { g ->
                val now = nextMutationTime()
                val stable = ClipboardOplog.newSyncId()
                val newId = clbGroupDao.insert(ClipboardGroup(name = g.name, sortOrder = g.sortOrder, syncId = stable, updatedAt = now, deviceId = ClipboardOplog.deviceId)).toInt()
                ClipboardOplog.append(ClipboardOplog.OP_GROUP_ADD, stable,
                    group = ClipboardOplog.GroupOp(g.name, g.sortOrder), ts = now)
                groupIdByOldId[g.id] = newId
            }
            // 2. 条目：按 text+sensitive 去重合并，并恢复分组归属
            incoming.forEach { entry ->
                val isImage = entry.type.startsWith("image/")
                val existing = if (isImage) null else clbDao.find(entry.text, entry.sensitive)
                val insertedId: Int = if (isImage || existing == null) {
                    val now = nextMutationTime()
                    val group = groupIdByOldId[entry.groupId]?.let { clbGroupDao.get(it) }
                    val imported = entry.copy(id = 0, groupId = group?.id ?: 0, groupSyncId = group?.syncId.orEmpty(),
                        syncId = ClipboardOplog.newSyncId(), updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId)
                    val id = clbDao.insert(imported).toInt()
                    ClipboardOplog.append(ClipboardOplog.OP_INSERT, imported.syncId, entry = entryOp(imported), ts = now)
                    added++
                    id
                } else {
                    existing.id
                }
                // 已存在条目：补充分组归属
                val newGroupId = groupIdByOldId[entry.groupId]
                if (!isImage && newGroupId != null && newGroupId != 0) {
                    val group = clbGroupDao.get(newGroupId)
                    clbDao.get(insertedId)?.let { old ->
                        val now = nextMutationTime()
                        val updated = old.copy(groupId = newGroupId, groupSyncId = group?.syncId.orEmpty(),
                            updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId)
                        clbDao.upsert(updated)
                        ClipboardOplog.append(ClipboardOplog.OP_SET_GROUP, updated.syncId, entry = entryOp(updated), ts = now)
                    }
                }
            }
        }
        updateItemCount()
        added
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
            withSyncLock {
                val imageEntry = captureImageEntry(clip)
                val entry = imageEntry ?: ClipboardEntry.fromClipData(clip, transformer) ?: return@withSyncLock
                if (!entry.type.startsWith("image/") && entry.text.isBlank()) return@withSyncLock
                val isImage = entry.type.startsWith("image/") && entry.imagePath != null
                try {
                    val captured = clbDb.withTransaction {
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
                    val newEntry = entryToInsert.copy(syncId = syncId, updatedAt = now, lastModifiedBy = ClipboardOplog.deviceId)
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
            }
        }
    }

    private suspend fun removeOutdated() = withSyncLock { clbDb.withTransaction { removeOutdatedUnlocked() } }

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
            val now = nextMutationTime()
            // 先读完整条目（markAsDeleted 后 get(id) 会因 deleted=0 过滤返回 null）
            val removed = ArrayList<ClipboardEntry>()
            for (id in toRemove) clbDao.get(id)?.let { removed.add(it) }
            // Keep the generated sync id together with the row.  Generating it
            // again while writing the oplog would create a tombstone for a
            // different identity, allowing the original row to reappear from
            // another replica.
            val removedIds = HashMap<Int, String>(removed.size)
            // 自动清理也是删除：写完整墓碑，确保 syncId 与日志稳定一致。
            removed.forEach { e ->
                val stable = e.syncId.ifEmpty { ClipboardOplog.newSyncId() }
                removedIds[e.id] = stable
                clbDao.upsert(e.copy(syncId = stable, deleted = true, updatedAt = now,
                    lastModifiedBy = ClipboardOplog.deviceId))
            }
            // 为同步侧生成删除墓碑日志，使删除意图能传播到对端
            removed.forEach { e ->
                val stable = removedIds[e.id] ?: e.syncId
                if (stable.isBlank()) return@forEach
                ClipboardOplog.append(
                    ClipboardOplog.OP_DELETE,
                    stable,
                    ClipboardOplog.EntryOp(
                        text = e.text, pinned = e.pinned, timestamp = e.timestamp,
                        type = e.type, sensitive = e.sensitive, deleted = true,
                        groupName = groupNameById(e.groupId), imagePath = e.imagePath
                    ),
                    ts = now
                )
            }
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
