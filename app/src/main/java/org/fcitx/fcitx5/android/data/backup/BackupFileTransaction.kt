/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.backup

import java.io.File
import java.io.IOException

/** Process-interruption recovery, not a power-loss-atomic filesystem/database transaction. */
internal class BackupFileTransaction(
    private val pending: File,
    private val rollback: File,
    private val state: File,
    private val targets: Map<String, File>,
    private val fault: (String) -> Unit = {},
) {
    enum class Result { NONE, APPLIED, ORIGINAL_RETAINED }

    fun apply(): Result {
        if (state.isFile) {
            when (state.readText()) {
                "COMMITTED" -> { cleanup(); return Result.APPLIED }
                "RECOVERED", "PREPARING" -> { cleanup(); return Result.ORIGINAL_RETAINED }
                "READY" -> { restoreOriginal(); return Result.ORIGINAL_RETAINED }
                else -> error("Unknown backup recovery state; refusing to open live databases")
            }
        }
        if (!pending.exists()) return Result.NONE
        require(File(pending, "journal").readText() == "READY") { "Invalid pending backup" }
        // Any prior directory without a state marker predates all live changes.
        remove(rollback)
        check(rollback.mkdirs()) { "Cannot create backup rollback directory" }
        writeState("PREPARING")
        val names = targets.keys.filter { File(pending, it).isDirectory }
        try {
            File(rollback, "targets").writeText(names.joinToString("\n"))
            names.forEach { name ->
                fault("backup:$name")
                val original = targets.getValue(name)
                if (original.exists()) {
                    original.copyRecursively(File(rollback, name), overwrite = true)
                    File(rollback, "$name.present").writeText("1")
                } else File(rollback, "$name.absent").writeText("1")
            }
            writeState("READY")
        } catch (e: Exception) {
            // PREPARING has never modified any live target. Never call rollback here.
            cleanup()
            return Result.ORIGINAL_RETAINED
        }
        try {
            names.forEach { name ->
                fault("apply:$name")
                val source = File(pending, name)
                val target = targets.getValue(name)
                if (name == "databases") applyDatabases(source, target)
                else {
                    if (name == "shared_prefs") source.listFiles().orEmpty()
                        .filter { it.isFile && it.extension == "xml" }
                        .forEach { remove(File(target, it.name + ".bak")) }
                    source.copyRecursively(target, overwrite = true)
                }
                fault("applied:$name")
            }
            writeState("COMMITTED")
        } catch (e: Exception) {
            try { restoreOriginal() }
            catch (recovery: Exception) {
                throw IOException("Backup application and rollback failed; recovery files retained", recovery)
            }
            return Result.ORIGINAL_RETAINED
        }
        cleanup()
        return Result.APPLIED
    }

    private fun applyDatabases(source: File, target: File) {
        check(target.isDirectory || target.mkdirs()) { "Cannot create database directory" }
        source.listFiles()?.forEach { incoming ->
            require(incoming.isFile && !isDatabaseSidecar(incoming.name)) { "Unprepared database backup" }
            // Keep unrelated databases, but never combine an imported main file with old WAL.
            listOf("", "-wal", "-shm", "-journal").forEach { suffix -> remove(File(target, incoming.name + suffix)) }
            incoming.copyTo(File(target, incoming.name), overwrite = true)
        }
    }

    private fun restoreOriginal() {
        val names = File(rollback, "targets").readLines().filter { it.isNotEmpty() }
        require(names.distinct().size == names.size && names.all { it in targets }) { "Invalid recovery target manifest" }
        // Preflight all originals before deleting any live file.
        names.forEach { name ->
            val present = File(rollback, "$name.present").isFile
            val absent = File(rollback, "$name.absent").isFile
            require(present != absent && (!present || File(rollback, name).isDirectory)) { "Incomplete rollback copy: $name" }
        }
        names.forEach { name ->
            fault("restore:$name")
            val target = targets.getValue(name)
            remove(target)
            if (File(rollback, "$name.present").isFile) File(rollback, name).copyRecursively(target, overwrite = true)
        }
        writeState("RECOVERED")
        cleanup()
    }

    private fun writeState(value: String) {
        state.parentFile?.mkdirs()
        val temp = File(state.parentFile, state.name + ".tmp")
        temp.outputStream().use { out -> out.write(value.toByteArray()); out.fd.sync() }
        check(temp.renameTo(state)) { "Cannot persist backup journal" }
    }
    private fun cleanup() {
        // The external state marker is deleted last, so cleanup itself is restart-safe.
        remove(pending)
        remove(rollback)
        remove(state)
    }
    private fun remove(file: File) {
        if (file.exists() && !file.deleteRecursively()) throw IOException("Cannot remove ${file.name}")
    }

    companion object {
        fun isDatabaseSidecar(name: String) = listOf("-wal", "-shm", "-journal").any(name::endsWith)
    }
}
