/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

import android.content.Context
import java.io.File
import org.fcitx.fcitx5.android.data.stats.InputStatsManager

/** All callers share the same serial queue as learning, backups and native publication. */
object ManualCorrectionRuleStore {
    private lateinit var fileStore: ManualCorrectionFileStore

    fun init(context: Context) {
        fileStore = ManualCorrectionFileStore(File(context.applicationContext.filesDir, "smart")) {
            InputStatsManager.syncNativeFiles()
        }
    }

    private fun requireInit() { check(::fileStore.isInitialized) { "manual-rule store unavailable" } }
    private fun prepareMutation() {
        requireInit()
        InputStatsManager.requireReadyUnlocked()
        fileStore.recoverPendingUnlocked()
    }

    suspend fun list(query: String = ""): List<ManualCorrectionRule> = InputStatsManager.serialized {
        requireInit()
        val term = query.trim()
        fileStore.read().filter { term.isEmpty() || it.input.contains(term, true) ||
            it.output.contains(term, true) || it.id == term }
            .sortedWith(compareByDescending<ManualCorrectionRule> { it.enabled }
                .thenByDescending { it.priority }.thenBy { it.input }.thenBy { it.id })
    }

    suspend fun save(rule: ManualCorrectionRule): ManualCorrectionRule = InputStatsManager.serialized {
        prepareMutation()
        val previous = fileStore.read()
        val existing = previous.firstOrNull { it.id == rule.id }
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val created = existing?.createdAt ?: rule.createdAt
        val saved = rule.copy(createdAt = created, updatedAt = maxOf(now, created, existing?.updatedAt ?: 0L))
        fileStore.replace(previous.filterNot { it.id == saved.id } + saved)
        saved
    }

    suspend fun delete(id: String): Boolean = InputStatsManager.serialized {
        prepareMutation()
        val previous = fileStore.read()
        if (previous.none { it.id == id }) return@serialized false
        fileStore.replace(previous.filterNot { it.id == id })
        true
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = InputStatsManager.serialized {
        prepareMutation()
        val previous = fileStore.read()
        val found = previous.firstOrNull { it.id == id } ?: return@serialized false
        val now = maxOf(System.currentTimeMillis(), found.createdAt, found.updatedAt)
        fileStore.replace(previous.map { if (it.id == id) it.copy(enabled = enabled, updatedAt = now) else it })
        true
    }

    internal fun snapshotUnlocked(): List<ManualCorrectionRule> { requireInit(); return fileStore.read() }
    internal fun replaceUnlocked(rules: List<ManualCorrectionRule>) { requireInit(); fileStore.writeSnapshot(rules) }
    internal fun recoverPendingUnlocked() { requireInit(); fileStore.recoverPendingUnlocked() }
}
