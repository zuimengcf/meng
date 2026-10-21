/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.backup.BackupFileTransaction
import org.fcitx.fcitx5.android.data.backup.BackupValidation
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSyncSettings
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.smart.ManualCorrectionRuleContract
import org.fcitx.fcitx5.android.data.smart.ManualCorrectionRuleStore
import org.fcitx.fcitx5.android.data.stats.InputStatsManager
import org.fcitx.fcitx5.android.utils.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** All user settings, including room credentials; Keystore ciphertext is re-encrypted per install. */
object UserDataManager {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    @Volatile private var lastPendingImportFailure: String? = null
    private val pending get() = File(appContext.filesDir, ".backup-import-pending")
    @Serializable data class Metadata(
        val packageName: String, val versionCode: Long, val versionName: String, val exportTime: Long,
        val formatVersion: Int = 1, val components: List<String>? = null,
    )

    private fun dirs(context: Context) = linkedMapOf(
        "shared_prefs" to File(context.applicationInfo.dataDir, "shared_prefs"),
        "databases" to File(context.applicationInfo.dataDir, "databases"),
        "external" to (context.getExternalFilesDir(null) ?: error("External files unavailable")),
        "recently_used" to File(context.filesDir, RecentlyUsed.DIR_NAME),
        "clipboard_images" to File(context.filesDir, "clipboard_images"),
        "clipboard_oplog" to File(context.filesDir, "clipboard_oplog.jsonl"),
        "manual_corrections" to File(context.filesDir, "smart"),
    )

    /** Stream backups without application-imposed size or entry-count limits. */
    private class ArchiveWriter(val zip: ZipOutputStream) {
        fun entry(name: String, source: InputStream? = null) {
            zip.writeBackupEntry(name, source)
        }
        fun tree(root: File, prefix: String, protectedPreferences: Boolean = false) {
            entry("$prefix/")
            if (!root.exists()) return
            root.walkTopDown().forEach { file ->
                if (file == root || (protectedPreferences && BackupValidation.protectedPrefs(file.name))) return@forEach
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (file.isDirectory) entry("$prefix/$rel/")
                else if (file.isFile) entry("$prefix/$rel", file.inputStream())
            }
        }
    }

    /** Copy each SQLite database while a reserved writer transaction blocks competing writers.
     * Copy its WAL too, then checkpoint only the copied database. Source files are never overwritten.
     * This is a per-database snapshot, not one transaction spanning settings/native files/all DBs.
     */
    private fun snapshotDatabases(source: File, dest: File) {
        if (!source.exists()) return
        check(dest.mkdirs() || dest.isDirectory)
        source.listFiles()?.filter { it.isFile && !BackupFileTransaction.isDatabaseSidecar(it.name) }?.forEach { file ->
            val walMode = file.inputStream().use { input -> input.skip(18); input.read() == 2 }
            val flags = SQLiteDatabase.OPEN_READWRITE or
                (if (walMode || File(source, file.name + "-wal").exists()) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0)
            SQLiteDatabase.openDatabase(file.path, null, flags).use { live ->
                live.beginTransactionNonExclusive()
                try {
                    file.copyTo(File(dest, file.name), overwrite = true)
                    val wal = File(source, file.name + "-wal")
                    if (wal.isFile) wal.copyTo(File(dest, wal.name), overwrite = true)
                } finally { live.endTransaction() }
            }
        }
        prepareDatabases(dest)
    }

    private fun prepareDatabases(dir: File) {
        if (!dir.exists()) return
        require(dir.listFiles()?.none { it.isDirectory } != false) { "Nested database directories are unsupported" }
        val files = dir.listFiles().orEmpty().filter { it.isFile && !BackupFileTransaction.isDatabaseSidecar(it.name) }
        val mainNames = files.map { it.name }.toSet()
        dir.listFiles().orEmpty().filter { BackupFileTransaction.isDatabaseSidecar(it.name) }.forEach { sidecar ->
            require(listOf("-wal", "-shm", "-journal").any { suffix -> sidecar.name.endsWith(suffix) && sidecar.name.removeSuffix(suffix) in mainNames }) { "Orphan database sidecar" }
        }
        files.forEach { file ->
            val header = ByteArray(16)
            file.inputStream().use { require(it.read(header) == header.size && header.contentEquals("SQLite format 3\u0000".toByteArray())) { "Invalid database header: ${file.name}" } }
            // Opening the staged main file with its staged WAL recovers committed WAL data.
            // Never delete WAL before this successful checkpoint/integrity check.
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
                copy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { q ->
                    require(q.moveToFirst() && q.getInt(0) == 0) { "Cannot checkpoint backup database: ${file.name}" }
                }
                copy.rawQuery("PRAGMA integrity_check", null).use { q ->
                    require(q.moveToFirst() && q.getString(0).equals("ok", true)) { "Database integrity check failed: ${file.name}" }
                }
            }
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val sidecar = File(dir, file.name + suffix)
                require(!sidecar.exists() || sidecar.delete()) { "Cannot normalize backup database" }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = ClipboardManager.withSyncLock { runCatching {
        withTempDir { temp ->
            val sources = dirs(appContext).toMutableMap()
            val manualRulesJson = InputStatsManager.serialized {
                InputStatsManager.requireNoPendingRecoveryUnlocked()
                ManualCorrectionRuleStore.recoverPendingUnlocked()
                ManualCorrectionRuleContract.encode(ManualCorrectionRuleStore.snapshotUnlocked())
            }
            val manualRulesSnapshot = File(temp, "manual_corrections")
            check(manualRulesSnapshot.mkdirs()) { "Cannot stage manual correction rules" }
            File(manualRulesSnapshot, "manual-corrections.json").writeText(manualRulesJson)
            sources["manual_corrections"] = manualRulesSnapshot
            // A synchronous commit waits for earlier SharedPreferences.apply writes.
            sources.getValue("shared_prefs").listFiles().orEmpty()
                .filter { it.isFile && it.extension == "xml" }
                .forEach { file -> check(appContext.getSharedPreferences(file.nameWithoutExtension, Context.MODE_PRIVATE).edit().commit()) { "Cannot flush preferences" } }
            val dbSnapshot = File(temp, "databases")
            snapshotDatabases(sources.getValue("databases"), dbSnapshot)
            ZipOutputStream(dest.buffered()).use { zip ->
                val writer = ArchiveWriter(zip)
                sources.forEach { (name, dir) ->
                    if (name == "shared_prefs") {
                        writer.entry("shared_prefs/")
                        dir.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                            if (file.name == "clipboard_sync_secure.xml") {
                                writer.entry("shared_prefs/${file.name}", ClipboardSyncSettings.exportPortablePreferences().byteInputStream())
                            } else {
                                writer.entry("shared_prefs/${file.name}", file.inputStream())
                            }
                        }
                    } else if (name == "clipboard_oplog") {
                        if (dir.isFile) writer.entry(name, dir.inputStream()) else writer.entry(name)
                    } else writer.tree(if (name == "databases") dbSnapshot else dir, name)
                }
                val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                val metadata = Metadata(info.packageName, info.versionCodeCompat, Const.versionName, timestamp,
                    formatVersion = 3, components = sources.keys.toList())
                writer.entry("metadata.json", json.encodeToString(Metadata.serializer(), metadata).byteInputStream())
            }
        }
    } }

    fun import(src: InputStream) = runCatching {
        check(!pending.exists()) { "A backup restore is already pending" }
        val staging = File(appContext.filesDir, ".backup-import-staging-${System.nanoTime()}")
        try {
            ZipInputStream(src.buffered()).use { it.extract(staging) }
            val metadataFile = File(staging, "metadata.json")
            if (!metadataFile.isFile) errorRuntime(R.string.exception_user_data_metadata)
            val metadataObject = json.parseToJsonElement(metadataFile.readText()).jsonObject
            require(!File(staging, "clipboard-snapshot.json").exists() && "versionCode" in metadataObject) {
                "这是剪贴板专用备份，请到“剪切板设置 → 导入剪切板”恢复，不要使用“导入用户数据”"
            }
            val metadata = json.decodeFromJsonElement<Metadata>(metadataObject)
            require(BackupValidation.isCompatiblePackage(metadata.packageName, BuildConfig.APPLICATION_ID)) {
                "备份来源标识 ${metadata.packageName} 与当前应用 ${BuildConfig.APPLICATION_ID} 不兼容，请核对备份文件与导入入口"
            }
            BackupValidation.fullBackupComponents(staging, metadata.formatVersion, metadata.components)
            if (metadata.formatVersion >= 3) {
                ManualCorrectionRuleContract.decode(BackupValidation.manualRulesFile(staging).readText())
            }
            val prefs = File(staging, "shared_prefs")
            prefs.walkTopDown().filter { it.isFile }.forEach { file ->
                if (file.name.endsWith(".xml.bak")) {
                    val primary = File(file.parentFile, file.name.removeSuffix(".bak"))
                    if (primary.exists()) require(file.delete())
                    else require(file.renameTo(primary))
                }
            }
            prefs.walkTopDown().filter { it.isFile && it.extension == "xml" }
                .forEach { file ->
                    val xml = file.readText()
                    BackupValidation.preferencesXml(xml)
                    if (file.name == "clipboard_sync_secure.xml") {
                        file.writeText(ClipboardSyncSettings.reencryptPortablePreferences(xml))
                    }
                }
            BackupValidation.mapDefaultPreferences(prefs, metadata.packageName, BuildConfig.APPLICATION_ID)
            prepareDatabases(File(staging, "databases"))
            BackupValidation.remapThemePaths(File(staging, "external/theme"), File(dirs(appContext).getValue("external"), "theme"))
            val imageSources = listOf("external/clipboard_images", "files/clipboard_images", "clipboard_images")
                .map { File(staging, it) }.filter { it.isDirectory }
            val stagedImages = File(staging, "external/clipboard_images")
            BackupValidation.coalesceClipboardImages(imageSources, stagedImages)
            val finalImageDirectory = File(dirs(appContext).getValue("external"), "clipboard_images")
            remapClipboardDatabase(File(staging, "databases/clbdb"), listOf(stagedImages), finalImageDirectory)
            BackupValidation.remapOplogImagePaths(File(staging, "clipboard_oplog"), listOf(stagedImages), finalImageDirectory)
            File(staging, "journal").writeText("READY")
            check(staging.renameTo(pending)) { "Cannot stage backup restore" }
            metadata
        } catch (e: Exception) { staging.deleteRecursively(); throw e }
    }

    /** Returns an explicit result for the startup notification; failures to roll back propagate. */
    internal fun applyPendingImport(context: Context): BackupFileTransaction.Result {
        val transaction = BackupFileTransaction(
            File(context.filesDir, ".backup-import-pending"),
            File(context.filesDir, ".backup-import-rollback"),
            File(context.filesDir, ".backup-import-state"),
            dirs(context),
        )
        return try {
            transaction.apply().also { lastPendingImportFailure = transaction.failureReason }
        } catch (e: Exception) {
            lastPendingImportFailure = e.message ?: e.javaClass.simpleName
            throw e
        }
    }

    internal fun remapClipboardDatabase(database: File, imageSources: List<File>, finalImageDirectory: File) {
        if (!database.isFile) return
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val hasTable = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='clipboard'", null).use { it.moveToFirst() }
            if (!hasTable) return
            val hasImagePath = db.rawQuery("SELECT * FROM clipboard LIMIT 0", null).use { it.getColumnIndex("imagePath") >= 0 }
            if (!hasImagePath) return
            val updates = ArrayList<Pair<Int, String>>()
            db.rawQuery("SELECT id, imagePath FROM clipboard WHERE imagePath IS NOT NULL", null).use { c ->
                while (c.moveToNext()) updates += c.getInt(0) to BackupValidation.remapClipboardPath(c.getString(1), imageSources, finalImageDirectory)
            }
            db.beginTransaction()
            try {
                db.compileStatement("UPDATE clipboard SET imagePath=? WHERE id=?").use { statement ->
                    updates.forEach { (id, path) -> statement.clearBindings(); statement.bindString(1, path); statement.bindLong(2, id.toLong()); statement.executeUpdateDelete() }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    internal fun pendingImportFailureReason(): String? = lastPendingImportFailure
}
