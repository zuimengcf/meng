/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.util.Base64
import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.FileOutputStream

/**
 * 同步快照包：设备间直接交换 Room 条目（不走 oplog），合并规则「较新者胜」。
 *
 * - 请求：客户端把本地全部条目 + 分组发给主机
 * - 响应：主机把合并后的全部条目 + 分组回传
 */
@Serializable
data class ClipboardSyncPacket(
    val deviceId: String = "",
    val protocolVersion: Int = 2,
    val onlineDeviceIds: List<String> = emptyList(),
    val entries: List<SyncEntry> = emptyList(),
    val groups: List<SyncGroup> = emptyList(),
    /** 各设备的同步水位线（deviceId -> 该设备已同步到的时刻）。
     *  随快照分发，使每台设备都掌握全网所有设备的水位线，墓碑确认不依赖固定主机。 */
    val watermarks: Map<String, Long> = emptyMap(),
    /** 已被覆盖或拒绝的历史版本；只用于恢复/审计，不直接复活到可见列表。 */
    val history: List<SyncRevision> = emptyList(),
)

@Serializable
data class SyncRevision(
    val id: String,
    val syncId: String,
    val ts: Long,
    val deviceId: String,
    val opType: String,
    val entry: ClipboardOplog.EntryOp? = null,
    val group: ClipboardOplog.GroupOp? = null,
)

@Serializable
data class SyncEntry(
    val syncId: String,
    val text: String,
    val pinned: Boolean,
    val timestamp: Long,
    val type: String,
    val sensitive: Boolean,
    val deleted: Boolean,
    val groupName: String = "",
    val updatedAt: Long,
    /** 最后修改该条目的设备 id（多设备区分用；空表示未知/本机旧数据） */
    val deviceId: String = "",
    /** 图片条目的二进制（Base64）。仅图片条目且本机存在图片文件时携带，用于跨设备同步图片本身。 */
    val imageData: String? = null,
    val groupSyncId: String = "",
    val sortOrder: Long = 0,
)

@Serializable
data class SyncGroup(
    val syncId: String,
    val name: String,
    val sortOrder: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deviceId: String = "",
)

/**
 * 把本地图片文件编码为 Base64 字符串；非图片条目或文件不存在/读取失败返回 null。
 * 仅用于跨设备同步图片二进制。
 */
fun imageFileToBase64(imagePath: String?): String? {
    if (imagePath.isNullOrBlank()) return null
    return try {
        val file = File(imagePath)
        if (!file.exists()) return null
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}

/**
 * 把远程传来的 Base64 图片数据落盘到本机剪切板图片目录，返回落盘后的绝对路径。
 * 返回 null 表示无数据或写入失败（此时调用方仍按缺图占位处理）。
 */
fun imageBase64ToFile(imageData: String?, mimeType: String): String? {
    if (imageData.isNullOrBlank()) return null
    return try {
        val dir = appContext.getExternalFilesDir(null)
            ?: return null
        val imgDir = File(dir, "clipboard_images").apply { mkdirs() }
        val ext = when {
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            else -> "png"
        }
        val bytes = Base64.decode(imageData, Base64.NO_WRAP)
        require(bytes.isNotEmpty()) { "empty clipboard image" }
        fun digest(data: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(data).joinToString("") { "%02x".format(it) }
        val hash = digest(bytes)
        val file = File(imgDir, "$hash.$ext")
        if (!file.isFile || digest(file.readBytes()) != hash) {
            val temporary = File.createTempFile("sync-", ".tmp", imgDir)
            try {
                FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
                check(temporary.renameTo(file)) { "unable to commit clipboard image" }
            } finally { temporary.delete() }
        }
        file.absolutePath
    } catch (e: Exception) {
        null
    }
}
