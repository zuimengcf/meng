package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Exact content inventories: never infer deletion from an absent ID or a clock watermark. */
@Serializable
data class ClipboardSyncInventory(
    val entries: Map<String, String> = emptyMap(),
    val groups: Map<String, String> = emptyMap(),
    val history: Map<String, String> = emptyMap(),
)

object ClipboardSyncDelta {
    private val json = Json { encodeDefaults = true }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).let { bytes ->
            val hex = "0123456789abcdef"
            buildString(bytes.size * 2) { bytes.forEach { b -> val v = b.toInt() and 255; append(hex[v ushr 4]); append(hex[v and 15]) } }
        }
    fun inventory(packet: ClipboardSyncPacket) = ClipboardSyncInventory(
        entries = packet.entries.associate { it.syncId to hash(json.encodeToString(it)) },
        groups = packet.groups.associate { it.syncId to hash(json.encodeToString(it)) },
        history = packet.history.associate { it.id to hash(json.encodeToString(it)) },
    )
    fun difference(packet: ClipboardSyncPacket, peer: ClipboardSyncInventory?, local: ClipboardSyncInventory = inventory(packet)): ClipboardSyncPacket {
        if (peer == null) return packet // An older peer still receives the complete durable snapshot.
        return packet.copy(
            entries = packet.entries.filter { local.entries[it.syncId] != peer.entries[it.syncId] },
            groups = packet.groups.filter { local.groups[it.syncId] != peer.groups[it.syncId] },
            history = packet.history.filter { local.history[it.id] != peer.history[it.id] },
        )
    }
    fun isEmpty(packet: ClipboardSyncPacket) = packet.entries.isEmpty() && packet.groups.isEmpty() && packet.history.isEmpty()
}
