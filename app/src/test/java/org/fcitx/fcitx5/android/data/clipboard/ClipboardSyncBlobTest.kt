/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** blob/1 能力协商与内容寻址图片：协议兼容性与哈希校验（纯 JVM）。 */
class ClipboardSyncBlobTest {
    private val wire = Json { ignoreUnknownKeys = true }
    private val inventoryJson = Json { encodeDefaults = true }
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
    private val pngSha = ClipboardImageBlobs.sha256Hex(png)

    private fun entry(sha: String? = null) = SyncEntry(syncId = "a", text = "图片", pinned = false, timestamp = 1,
        type = "image/png", sensitive = false, deleted = false, updatedAt = 2, deviceId = "d", imageSha256 = sha)

    @Test fun nullHashIsNeverEncodedSoLegacyInventoriesMatch() {
        val s = inventoryJson.encodeToString(entry())
        assertFalse(s.contains("imageSha256"))
        val op = ClipboardOplog.EntryOp("t", false, 1, "image/png", false, false)
        assertFalse(inventoryJson.encodeToString(op).contains("imageSha256"))
    }

    @Test fun emptyCapabilitiesKeepRequestsByteIdentical() {
        val r = ClipboardSyncWireRequest("heartbeat", "c", "h")
        val s = wire.encodeToString(r)
        assertFalse(s.contains("capabilities")); assertFalse(s.contains("blobHash"))
    }

    @Test fun oldDecoderIgnoresNewFields() {
        val s = wire.encodeToString(ClipboardSyncWireResponse(true, "r", "heartbeat", capabilities = listOf("blob/1"), blobData = "AA=="))
        val parsed = wire.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(s)
        assertTrue("capabilities" in parsed)
        // Re-decode as the response type with an unknown extra key, like an old client would see.
        val withExtra = s.dropLast(1) + ",\"futureField\":1}"
        assertEquals(listOf("blob/1"), wire.decodeFromString<ClipboardSyncWireResponse>(withExtra).capabilities)
    }

    @Test fun negotiationNeedsBothSides() {
        assertFalse(ClipboardSyncCapabilities.blobMode(null))
        assertFalse(ClipboardSyncCapabilities.blobMode(emptyList()))
        assertFalse(ClipboardSyncCapabilities.blobMode(listOf("blob/2", "x")))
        assertTrue(ClipboardSyncCapabilities.blobMode(listOf("x", "blob/1")))
        assertEquals(setOf("blob/1"), ClipboardSyncCapabilities.negotiated(listOf("blob/1", "unknown/9")))
    }

    @Test fun storeVerifiesHashAndFindsByName() {
        val dir = Files.createTempDirectory("blobs").toFile()
        val f = ClipboardImageBlobs.store(dir, pngSha, png, "image/png")
        assertEquals("$pngSha.png", f.name)
        assertEquals(f.absolutePath, ClipboardImageBlobs.find(dir, pngSha)?.absolutePath)
        assertNull(ClipboardImageBlobs.find(dir, "0".repeat(64)))
        try { ClipboardImageBlobs.store(dir, "0".repeat(64), png, "image/png"); fail("hash mismatch accepted") }
        catch (e: IllegalArgumentException) { }
        assertEquals(1, dir.listFiles()!!.size) // no temp file left behind
    }

    @Test fun tamperedFileIsNotReturned() {
        val dir = Files.createTempDirectory("blobs").toFile()
        File(dir, "$pngSha.png").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(ClipboardImageBlobs.find(dir, pngSha))
    }

    @Test fun inlineHashMatchesFileHash() {
        val b64 = Base64.getEncoder().encodeToString(png)
        assertEquals(pngSha, ClipboardImageBlobs.sha256OfBase64(b64, "history/x"))
        assertEquals(pngSha, ClipboardImageBlobs.sha256OfBase64(b64, "history/x")) // cached path
        assertNull(ClipboardImageBlobs.sha256OfBase64("!!not base64!!"))
        val dir = Files.createTempDirectory("blobs").toFile()
        assertEquals(pngSha, ClipboardImageBlobs.sha256OfFile(File(dir, "x.png").apply { writeBytes(png) }))
    }

    @Test fun referencedHashesSkipDeletedAndBadHashes() {
        val p = ClipboardSyncPacket(deviceId = "h", entries = listOf(entry(pngSha), entry("bad").copy(syncId = "b"),
            entry(pngSha.reversed()).copy(syncId = "c", deleted = true)),
            history = listOf(SyncRevision("history/1", "a", 1, "history/1", "HISTORY_ENTRY",
                ClipboardOplog.EntryOp("", false, 1, "image/png", false, false, imageSha256 = pngSha.reversed()))))
        assertEquals(setOf(pngSha, pngSha.reversed()), ClipboardImageBlobs.referencedHashes(p))
    }

    @Test fun blobInventoriesAgreeAcrossDevicesAndDifferFromLegacy() {
        val a = ClipboardSyncPacket(deviceId = "x", entries = listOf(entry(pngSha)))
        val b = ClipboardSyncPacket(deviceId = "y", entries = listOf(entry(pngSha)))
        assertEquals(ClipboardSyncDelta.inventory(a), ClipboardSyncDelta.inventory(b))
        assertTrue(ClipboardSyncDelta.isEmpty(ClipboardSyncDelta.difference(a, ClipboardSyncDelta.inventory(b))))
        val legacy = ClipboardSyncPacket(deviceId = "y", entries = listOf(entry().copy(imageData = "AAAA")))
        assertNotNull(ClipboardSyncDelta.difference(a, ClipboardSyncDelta.inventory(legacy)).entries.singleOrNull())
    }
}
