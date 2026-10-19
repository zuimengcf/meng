/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroupDao
import timber.log.Timber

/**
 * 局域网同步的**合并引擎**。
 *
 * 语义（绝不整库覆盖，绝不丢本地更新）：
 * - 以 oplog 操作日志为增量载体，两条规则保证不覆盖：
 *   1. 同一操作（deviceId, seq）幂等，重复应用无害；
 *   2. 每条数据以 `updatedAt`（数据最近变更时刻）比较，**较新者胜**，
 *      仅当远程操作时间戳晚于本机该数据最近变更时间才落地。
 * - 删除以墓碑（op.log entry.deleted=true）记录，避免被旧日志复活；
 *   但若本机在墓碑之后又更新过该条目（本地 updatedAt 更新），则保留本地版本。
 *
 * 图片条目不在局域网间传二进制，只传占位（imagePath 置空、type 保留 "image/..."），
 * 由目标设备按“缺图占位”处理（列表仍可显示条目与操作，缩略图待本机复制）。
 */
object ClipboardSyncMerger {

    private val mutex = Mutex()

    /**
     * 应用一批来自远程设备的操作日志（增量）。
     * 返回实际新增/更新条数（用于 UI 反馈）。
     */
    suspend fun applyOps(
        dao: ClipboardDao,
        groupDao: ClipboardGroupDao,
        remoteOps: List<ClipboardOplog.Op>,
    ): Int = mutex.withLock {
        // 删除墓碑守卫：先从本地 oplog 提取所有删除墓碑，形成 syncId -> 最晚墓碑ts 记忆。
        // 若某条 syncId 曾被删除，而收到的非删除操作时间戳不晚于墓碑，则判定为「旧日志复活」予以拒绝；
        // 只有时间戳严格晚于墓碑的非删除操作才视为「用户重新复制/新建」，允许建立新生命周期。
        val entryTombstones = HashMap<String, Long>()
        val groupTombstones = HashMap<String, Long>()
        ClipboardOplog.readAll().forEach { op ->
            when (op.opType) {
                ClipboardOplog.OP_DELETE -> if (op.syncId.isNotBlank()) {
                    entryTombstones[op.syncId] = maxOf(entryTombstones[op.syncId] ?: 0L, op.ts)
                }
                ClipboardOplog.OP_GROUP_DELETE -> if (op.syncId.isNotBlank()) {
                    groupTombstones[op.syncId] = maxOf(groupTombstones[op.syncId] ?: 0L, op.ts)
                }
                else -> {}
            }
        }

        var applied = 0
        // 按时间序应用，保证同一条目的多个源操作得到正确的最终态
        remoteOps.sortedBy { it.ts }.forEach { op ->
            when (op.opType) {
                ClipboardOplog.OP_INSERT,
                ClipboardOplog.OP_UPDATE_TEXT,
                ClipboardOplog.OP_PIN,
                ClipboardOplog.OP_SET_GROUP,
                ClipboardOplog.OP_DELETE -> {
                    if (applyEntryOp(dao, groupDao, op, op.entry ?: return@forEach, entryTombstones)) applied++
                }
                ClipboardOplog.OP_GROUP_ADD,
                ClipboardOplog.OP_GROUP_RENAME -> {
                    if (applyGroupUpsert(groupDao, op, op.group ?: return@forEach, groupTombstones)) applied++
                }
                ClipboardOplog.OP_GROUP_DELETE -> {
                    if (applyGroupDelete(groupDao, op, op.group ?: return@forEach)) applied++
                }
            }
        }
        applied
    }

    private suspend fun applyEntryOp(
        dao: ClipboardDao,
        groupDao: ClipboardGroupDao,
        op: ClipboardOplog.Op,
        payload: ClipboardOplog.EntryOp,
        entryTombstones: HashMap<String, Long>,
    ): Boolean {
        val tombTs = if (payload.deleted) 0L else entryTombstones[op.syncId] ?: 0L
        // 墓碑守卫：该 syncId 已被删除，若非删除操作不晚于墓碑 → 拒绝复活
        if (!payload.deleted && tombTs > 0L && op.ts <= tombTs) return false

        val local = dao.getBySyncId(op.syncId)
        // 远程操作比本机此数据新才落地，否则保留本机（较新者胜）
        if (local != null && local.updatedAt >= op.ts) return false

        // 解析组名 -> 本机分组 id（按组名匹配，跨设备稳定；未找到则归未分组）
        val groupId = payload.groupName.takeIf { it.isNotBlank() }
            ?.let { groupDao.getByName(it)?.id } ?: 0

        if (payload.deleted) {
            if (local == null) {
                // 无本地条目但收到删除墓碑：无需落地（本就不存在）
                return false
            }
            // 删除不覆盖本机在此之后的新编辑
            dao.markAsDeleted(local.id)
            dao.touchUpdatedAt(local.id, op.ts)
            return true
        }

        // INSERT / UPDATE / PIN / SET_GROUP 的合并：统一落到当前条目字段
        if (local == null) {
            dao.insert(
                ClipboardEntry(
                    text = payload.text,
                    pinned = payload.pinned,
                    timestamp = payload.timestamp,
                    type = payload.type,
                    sensitive = payload.sensitive,
                    imagePath = null, // 图片不跨设备传二进制，由目标设备按占位显示
                    groupId = groupId,
                    syncId = op.syncId,
                    updatedAt = op.ts,
                )
            )
            return true
        }

        // 本地存在且远程更新：覆盖远端语义指示的字段
        dao.updateText(local.id, payload.text)
        dao.updatePinStatus(local.id, payload.pinned)
        dao.setGroup(local.id, groupId)
        dao.touchUpdatedAt(local.id, op.ts)
        // 若远程是删除，标记删除
        if (payload.deleted) dao.markAsDeleted(local.id)
        return true
    }

    private suspend fun applyGroupUpsert(
        groupDao: ClipboardGroupDao,
        op: ClipboardOplog.Op,
        payload: ClipboardOplog.GroupOp,
        groupTombstones: HashMap<String, Long>,
    ): Boolean {
        // 墓碑守卫：该分组 syncId 曾被删除，若本次非删除操作不晚于墓碑 → 拒绝复活
        val groupTomb = groupTombstones[op.syncId] ?: 0L
        if (groupTomb > 0L && op.ts <= groupTomb) return false
        val local = groupDao.getBySyncId(op.syncId)
        if (local != null) {
            if (local.updatedAt >= op.ts) return false
            groupDao.rename(local.id, payload.name)
            groupDao.updateSortOrder(local.id, payload.sortOrder)
            groupDao.setSyncInfo(local.id, op.syncId, op.ts, op.deviceId)
            return true
        }
        // 按组名去重（同组名视为同一分组）
        val byName = groupDao.getByName(payload.name)
        if (byName != null) {
            if (byName.updatedAt >= op.ts) return false
            groupDao.setSyncInfo(byName.id, op.syncId, op.ts, op.deviceId)
            return true
        }
        groupDao.insert(
            ClipboardGroup(
                name = payload.name,
                sortOrder = payload.sortOrder,
                syncId = op.syncId,
                updatedAt = op.ts,
            )
        )
        return true
    }

    private suspend fun applyGroupDelete(
        groupDao: ClipboardGroupDao,
        op: ClipboardOplog.Op,
        payload: ClipboardOplog.GroupOp,
    ): Boolean {
        val local = groupDao.getBySyncId(op.syncId) ?: groupDao.getByName(payload.name)
        if (local == null) return false
        if (local.updatedAt >= op.ts) return false
        groupDao.delete(local.id, op.ts, op.deviceId)
        return true
    }
}
