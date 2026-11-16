/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SchemaMarketFileOpsTest {
    @Test
    fun zipRootAndCustomPathsAreNormalizedWithoutFlatteningCustomDirectory() = withTempDir { root ->
        val zip = File(root, "package.zip")
        writeZip(zip, linkedMapOf(
            "rime/wanxiang.schema.yaml" to "schema",
            "rime/custom/wanxiang.custom.yaml" to "custom",
            "rime/version.txt" to "18.2.0\n",
        ))
        val installRoot = File(root, "data/rime").apply { mkdirs() }
        val stage = File(root, "stage")

        val paths = SchemaMarketFileOps.extractValidatedZip(
            zip, stage, installRoot,
            expectedFiles = setOf("wanxiang.schema.yaml"),
            requireVersionFile = true,
            stripTopDir = true,
        )

        assertTrue(paths.contains("wanxiang.schema.yaml"))
        assertTrue(paths.contains("custom/wanxiang.custom.yaml"))
        assertEquals("custom", File(stage, "custom/wanxiang.custom.yaml").readText())
        assertFalse(File(stage, "rime").exists())
    }

    @Test
    fun existingGlobalAndCustomConfigurationAreNotOverwritten() = withTempDir { root ->
        val zip = File(root, "package.zip")
        writeZip(zip, linkedMapOf(
            "package/default.yaml" to "package-default",
            "package/custom/default.custom.yaml" to "package-custom",
            "package/wanxiang.schema.yaml" to "schema",
            "package/version.txt" to "18.2.0",
        ))
        val installRoot = File(root, "rime").apply { mkdirs() }
        File(installRoot, "default.yaml").writeText("user-default")
        File(installRoot, "custom").mkdirs()
        File(installRoot, "custom/default.custom.yaml").writeText("user-custom")
        val stage = File(root, "stage")

        val paths = SchemaMarketFileOps.extractValidatedZip(
            zip, stage, installRoot,
            expectedFiles = setOf("wanxiang.schema.yaml"),
            requireVersionFile = true,
            stripTopDir = true,
        )

        assertFalse(paths.contains("default.yaml"))
        assertFalse(paths.contains("custom/default.custom.yaml"))
        assertEquals("user-default", File(installRoot, "default.yaml").readText())
        assertEquals("user-custom", File(installRoot, "custom/default.custom.yaml").readText())
    }

    @Test
    fun protectedConfigurationIsStillReadAndCrcChecked() = withTempDir { root ->
        val zip = File(root, "damaged-config.zip")
        writeStoredZip(zip, linkedMapOf(
            "default.yaml" to "package-default",
            "wanxiang.schema.yaml" to "schema",
            "version.txt" to "18.2.0",
        ))
        corruptStoredEntry(zip, "default.yaml")
        val installRoot = File(root, "rime").apply { mkdirs() }
        File(installRoot, "default.yaml").writeText("user-default")

        val error = runCatching {
            SchemaMarketFileOps.extractValidatedZip(
                zip, File(root, "stage"), installRoot,
                expectedFiles = setOf("wanxiang.schema.yaml"),
                requireVersionFile = true,
                stripTopDir = false,
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("user-default", File(installRoot, "default.yaml").readText())
    }

    @Test
    fun badLaterZipEntryNeverTouchesLiveFiles() = withTempDir { root ->
        val zip = File(root, "damaged.zip")
        writeStoredZip(zip, linkedMapOf(
            "wanxiang.schema.yaml" to "valid-first-entry",
            "dict.txt" to "corrupt-second-entry",
            "version.txt" to "18.2.0",
        ))
        corruptStoredEntry(zip, "dict.txt")
        val live = File(root, "rime").apply { mkdirs() }
        File(live, "wanxiang.schema.yaml").writeText("old-schema")
        val stage = File(root, "stage")

        val error = runCatching {
            SchemaMarketFileOps.extractValidatedZip(
                zip, stage, live,
                expectedFiles = setOf("wanxiang.schema.yaml"),
                requireVersionFile = true,
                stripTopDir = false,
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("old-schema", File(live, "wanxiang.schema.yaml").readText())
        assertFalse(File(live, "dict.txt").exists())
        assertFalse(File(live, ".meng_schema_versions").exists())
    }

    @Test
    fun htmlAndNonZipResponsesAreRejectedBeforeInstall() = withTempDir { root ->
        val html = File(root, "download.zip").apply { writeText("<!doctype html><html>rate limited</html>") }
        val stage = File(root, "stage")
        val live = File(root, "rime").apply { mkdirs() }
        File(live, "wanxiang.schema.yaml").writeText("old")

        assertNotNull(runCatching {
            SchemaMarketFileOps.extractValidatedZip(html, stage, live, setOf("wanxiang.schema.yaml"), true, true)
        }.exceptionOrNull())
        assertNotNull(runCatching { SchemaMarketFileOps.validateSingleFile(html) }.exceptionOrNull())
        assertEquals("old", File(live, "wanxiang.schema.yaml").readText())
    }

    @Test
    fun missingExpectedSchemeOrVersionMarkerIsRejected() = withTempDir { root ->
        val zip = File(root, "wrong.zip")
        writeZip(zip, linkedMapOf("some-other.schema.yaml" to "wrong", "version.txt" to "1.0"))
        val error = runCatching {
            SchemaMarketFileOps.extractValidatedZip(
                zip, File(root, "stage"), File(root, "rime").apply { mkdirs() },
                setOf("wanxiang.schema.yaml"), true, false,
            )
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("预期方案文件"))

        val noVersion = File(root, "no-version.zip")
        writeZip(noVersion, mapOf("wanxiang.schema.yaml" to "schema"))
        val versionError = runCatching {
            SchemaMarketFileOps.extractValidatedZip(
                noVersion, File(root, "stage2"), File(root, "rime2").apply { mkdirs() },
                setOf("wanxiang.schema.yaml"), true, false,
            )
        }.exceptionOrNull()
        assertTrue(versionError?.message.orEmpty().contains("version.txt"))
    }

    @Test
    fun transactionalCommitRestoresEarlierFilesAfterLaterFailure() = withTempDir { root ->
        val live = File(root, "rime").apply { mkdirs() }
        val old = File(live, "schema.yaml").apply { writeText("old") }
        val stagedOne = File(root, "stage-one").apply { writeText("new") }
        val stagedTwo = File(root, "stage-two").apply { writeText("new-two") }
        val blockedDirectory = File(live, "blocked").apply { mkdirs() }

        val error = runCatching {
            SchemaMarketFileOps.applyAtomically(listOf(
                SchemaMarketFileOps.Replacement(stagedOne, old),
                SchemaMarketFileOps.Replacement(stagedTwo, blockedDirectory),
            ))
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("old", old.readText())
        assertTrue(blockedDirectory.isDirectory)
        assertFalse(File(root, "rime/schema.yaml.backup").exists())
    }

    @Test
    fun failedGramFingerprintValidationKeepsTheOldModel() = withTempDir { root ->
        val target = File(root, "wanxiang-lts-zh-hans.gram").apply { writeText("old-valid-model") }
        val staged = File(root, "new.gram").apply { writeText("new-but-unverified-model") }
        val oldHash = "sha256:${SchemaMarketFileOps.sha256(target)}"

        val error = runCatching {
            val newFingerprint = SchemaMarketFileOps.validateSingleFile(staged, oldHash)
            SchemaMarketFileOps.applyAtomically(listOf(SchemaMarketFileOps.Replacement(staged, target)))
            newFingerprint
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("old-valid-model", target.readText())
    }

    @Test
    fun ltsAssetSizeMismatchIsRejectedBeforeReplacingTheOldModel() = withTempDir { root ->
        val target = File(root, "wanxiang-lts-zh-hans.gram").apply { writeText("old-valid-model") }
        val staged = File(root, "new.gram").apply { writeText("truncated-model") }

        val error = runCatching {
            SchemaMarketFileOps.validateSingleFile(staged, "asset:123:2026-11-16T00:00:00Z:100")
            SchemaMarketFileOps.applyAtomically(listOf(SchemaMarketFileOps.Replacement(staged, target)))
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("old-valid-model", target.readText())
    }

    @Test
    fun failedGramReplacementTransactionRollsBackToTheOldModel() = withTempDir { root ->
        val live = File(root, "rime").apply { mkdirs() }
        val model = File(live, "wanxiang-lts-zh-hans.gram").apply { writeText("old-model") }
        val staged = File(root, "new.gram").apply { writeText("new-model") }
        val blocked = File(live, "blocked").apply { mkdirs() }

        val error = runCatching {
            SchemaMarketFileOps.applyAtomically(listOf(
                SchemaMarketFileOps.Replacement(staged, model),
                SchemaMarketFileOps.Replacement(File(root, "other.tmp").apply { writeText("other") }, blocked),
            ))
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("old-model", model.readText())
        assertTrue(blocked.isDirectory)
    }

    @Test
    fun ltsFingerprintChangeTriggersUpdateButFailedCheckDoesNotGuess() {
        val installedFingerprint = "sha256:${"a".repeat(64)}"
        val same = SchemaMarketFileOps.sourceWithAssetProbe(SchemaSources.lmdgSource(), installedFingerprint, true)
        val changed = SchemaMarketFileOps.sourceWithAssetProbe(
            SchemaSources.lmdgSource(), "sha256:${"b".repeat(64)}", true,
        )
        val unavailable = SchemaMarketFileOps.sourceWithAssetProbe(
            SchemaSources.lmdgSource(), installedFingerprint, false,
        )

        assertFalse(SchemaMarketFileOps.hasUpdate("LTS", same, installedFingerprint))
        assertTrue(SchemaMarketFileOps.hasUpdate("LTS", changed, installedFingerprint))
        assertFalse(SchemaMarketFileOps.hasUpdate("LTS", unavailable, installedFingerprint))
    }

    @Test
    fun uninstallRemovesOwnedModelButKeepsSharedFilesAndSuppressesStaleInstallDetection() = withTempDir { root ->
        val model = File(root, "wanxiang-lts-zh-hans.gram").apply { writeText("model") }
        val shared = File(root, "shared.dict.yaml").apply { writeText("shared") }
        val modelHash = "sha256:${SchemaMarketFileOps.sha256(model)}"
        val manifest = SchemaMarketFileOps.parseOwnershipManifest(
            listOf("$modelHash\t${model.name}", "sha256:unused\t${shared.name}"),
        )
        val sharedRefs = setOf(shared.name)
        manifest.filter { owned ->
            val target = File(root, owned.path)
            val current = if (target.isFile) "sha256:${SchemaMarketFileOps.sha256(target)}" else null
            SchemaMarketFileOps.shouldDeleteOwnedFile(owned, sharedRefs, current)
        }.forEach { File(root, it.path).delete() }

        assertFalse(model.exists())
        assertTrue(shared.exists())
        assertTrue(SchemaMarketFileOps.isExplicitlyUninstalled("wanxiang-lmdg", setOf("wanxiang-lmdg")))
        assertFalse(SchemaMarketFileOps.isExplicitlyUninstalled("wanxiang-lmdg", emptySet()))
    }

    @Test
    fun uninstallKeepsAnOwnedFileThatWasEditedAfterInstallation() = withTempDir { root ->
        val model = File(root, "wanxiang-lts-zh-hans.gram").apply { writeText("installed-by-market") }
        val recordedHash = "sha256:${SchemaMarketFileOps.sha256(model)}"
        val owned = SchemaMarketFileOps.OwnedFile(model.name, recordedHash)
        model.writeText("user-edited")

        assertFalse(
            SchemaMarketFileOps.shouldDeleteOwnedFile(
                owned, emptySet(), "sha256:${SchemaMarketFileOps.sha256(model)}",
            ),
        )
        assertEquals("user-edited", model.readText())
    }

    @Test
    fun mirrorAttemptsUseRankedOrderAndContinueAfterFailure() {
        val mirrors = listOf(
            SchemaMirror("failed", "https://failed", MirrorStatus.FAIL),
            SchemaMirror("backup", "https://backup", MirrorStatus.UNKNOWN),
            SchemaMirror("fast", "https://fast", MirrorStatus.OK),
        )
        val attempted = mutableListOf<String>()
        val result = SchemaMarketFileOps.firstSuccessfulMirror(mirrors) { mirror ->
            attempted += mirror.label
            if (mirror.label == "fast") null else if (mirror.label == "backup") "installed" else null
        }

        assertEquals(listOf("fast", "backup"), attempted)
        assertEquals("backup", result?.first?.label)
        assertEquals("installed", result?.second)
    }

    @Test
    fun releaseRefreshUsesVerifiedTagAndMarksCachedFallbackUnknown() {
        val fallback = SchemaSources.wanxiangLatest(SchemaSources.WANXIANG_FALLBACK_TAG)
        val verified = SchemaMarketFileOps.sourceWithReleaseProbe(fallback, "v18.2.0", true)
        val unavailable = SchemaMarketFileOps.sourceWithReleaseProbe(verified, "v18.2.0", false)

        assertEquals("18.2.0", verified.version)
        assertFalse(verified.versionCheckFailed)
        assertTrue(SchemaMarketFileOps.hasUpdate("18.1.3", verified))
        assertEquals("18.2.0", unavailable.version)
        assertTrue(unavailable.versionCheckFailed)
        assertFalse(SchemaMarketFileOps.hasUpdate("18.1.3", unavailable))
        assertEquals("v18.2.0", SchemaMarketFileOps.releaseTagFromUrl("https://github.com/org/repo/releases/tag/v18.2.0"))
        assertNull(SchemaMarketFileOps.releaseTagFromUrl("https://github.com/org/repo/releases/latest"))
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun writeStoredZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray()
                val crc = CRC32().apply { update(bytes) }
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun corruptStoredEntry(file: File, entryName: String) {
        val bytes = file.readBytes()
        val nameBytes = entryName.toByteArray()
        var header = -1
        for (index in 0 until bytes.size - 4) {
            if (bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4b.toByte() &&
                bytes[index + 2] == 0x03.toByte() && bytes[index + 3] == 0x04.toByte()) {
                val nameLength = littleEndianShort(bytes, index + 26)
                val nameStart = index + 30
                if (nameLength == nameBytes.size && bytes.copyOfRange(nameStart, nameStart + nameLength).contentEquals(nameBytes)) {
                    header = index
                    break
                }
            }
        }
        check(header >= 0) { "ZIP local header not found for $entryName" }
        val nameLength = littleEndianShort(bytes, header + 26)
        val extraLength = littleEndianShort(bytes, header + 28)
        val dataStart = header + 30 + nameLength + extraLength
        bytes[dataStart] = (bytes[dataStart].toInt() xor 1).toByte()
        file.writeBytes(bytes)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("schema-market-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
