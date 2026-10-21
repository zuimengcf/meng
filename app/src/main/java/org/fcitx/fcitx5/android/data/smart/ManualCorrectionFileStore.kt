/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Pure file implementation used by the app and JVM tests; no Android or in-memory-only fallback. */
class ManualCorrectionFileStore(
    private val directory: File,
    private val publish: suspend (String) -> Unit = {}
) {
    val rulesFile: File get() = File(directory, "manual-corrections.json")
    val journalFile: File get() = File(directory, "manual-corrections.journal")

    private fun readDocument(file: File): String {
        require(file.isFile && file.length() <= ManualCorrectionRuleContract.MAX_BYTES) { "invalid or oversized rules file" }
        val bytes = file.inputStream().use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(result.size() + read <= ManualCorrectionRuleContract.MAX_BYTES) { "rules file grew beyond size limit" }
                result.write(buffer, 0, read)
            }
            result.toByteArray()
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun read(): List<ManualCorrectionRule> = if (!rulesFile.exists()) emptyList()
        else ManualCorrectionRuleContract.decode(readDocument(rulesFile))

    /** Only file state changes here; the startup coordinator publishes native files after all recovery. */
    fun recoverPendingUnlocked() {
        if (!journalFile.exists()) return
        val previous = ManualCorrectionRuleContract.decode(readDocument(journalFile))
        writeSnapshot(previous)
        check(journalFile.delete()) { "cannot finish manual-rule recovery" }
    }

    /** Raw atomic replacement for the full bundle transaction, which already owns a durable journal. */
    fun writeSnapshot(rules: List<ManualCorrectionRule>) {
        atomicWrite(rulesFile, ManualCorrectionRuleContract.encode(rules))
    }

    suspend fun replace(rules: List<ManualCorrectionRule>) {
        val encoded = ManualCorrectionRuleContract.encode(rules) // complete validation before any mutation
        check(!journalFile.exists()) { "recover previous manual-rule edit before starting another" }
        val previous = read() // malformed existing data must never be overwritten by an empty fallback
        atomicWrite(journalFile, ManualCorrectionRuleContract.encode(previous))
        SmartImportRollback.run(
            publish = { atomicWrite(rulesFile, encoded); publish(ManualCorrectionRuleContract.toNativeTsv(rules)) },
            complete = { check(journalFile.delete()) { "cannot complete manual-rule edit" } },
            rollback = {
                writeSnapshot(previous)
                publish(ManualCorrectionRuleContract.toNativeTsv(previous))
                check(journalFile.delete()) { "cannot finish manual-rule rollback" }
            },
        )
    }

    private fun atomicWrite(target: File, content: String) {
        check(directory.isDirectory || directory.mkdirs()) { "manual-rule directory unavailable" }
        val temporary = File(directory, target.name + ".tmp")
        FileOutputStream(temporary).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8)); out.fd.sync()
        }
        // Both paths are in the same private directory; Android/Linux rename replaces atomically.
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("cannot atomically replace manual-rule file")
        }
    }
}
