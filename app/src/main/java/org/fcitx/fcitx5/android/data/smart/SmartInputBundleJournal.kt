/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

import java.io.File
import java.io.FileOutputStream

/** A single durable journal covers configuration, learning tables and persistent manual rules. */
internal class SmartInputBundleJournal(directory: File) {
    private val journal = File(directory, "smart-input-import.pending.json")

    val hasPending: Boolean get() = journal.exists()

    suspend fun apply(previous: String, replacement: SmartInputBundle.Payload,
                      publish: suspend (SmartInputBundle.Payload) -> Unit) {
        // The caller supplies a validated replacement; check the complete previous snapshot too.
        SmartInputBundle.validate(previous)
        check(!journal.exists()) { "unrecovered import journal" }
        val temporary = File(journal.parentFile, journal.name + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(previous.toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        check(temporary.renameTo(journal)) { "cannot persist import rollback journal" }
        SmartImportRollback.run(
            publish = { publish(replacement) },
            complete = { check(journal.delete()) { "cannot complete import journal" } },
            rollback = { recover(publish) },
        )
    }

    suspend fun recover(publish: suspend (SmartInputBundle.Payload) -> Unit) {
        if (!journal.exists()) return
        require(journal.length() <= SmartInputBundle.MAX_BYTES) { "invalid rollback journal" }
        val previous = SmartInputBundle.validate(journal.readText(Charsets.UTF_8))
        publish(previous)
        check(journal.delete()) { "cannot finish rollback" }
    }
}
