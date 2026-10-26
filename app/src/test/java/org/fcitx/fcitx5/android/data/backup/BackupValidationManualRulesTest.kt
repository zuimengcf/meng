/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.backup

import java.io.File
import java.nio.file.Files
import org.fcitx.fcitx5.android.data.smart.ManualCorrectionRule
import org.fcitx.fcitx5.android.data.smart.ManualCorrectionRuleContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupValidationManualRulesTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun version2BackupWithoutManualRulesRemainsCompatible() {
        val root = newRoot()
        addBaseComponents(root)
        val declared = listOf("shared_prefs", "databases", "external", "recently_used", "clipboard_images", "clipboard_oplog")

        assertEquals(declared, BackupValidation.fullBackupComponents(root, 2, declared))
        assertFalse(File(root, "manual_corrections").exists())
    }

    @Test
    fun version3RequiresManualRulesFile() {
        val root = newRoot()
        addBaseComponents(root)
        val declared = listOf("shared_prefs", "databases", "external", "recently_used", "clipboard_images", "clipboard_oplog", "manual_corrections")

        assertThrows(IllegalArgumentException::class.java) {
            BackupValidation.fullBackupComponents(root, 3, declared)
        }
    }

    @Test
    fun version3AcceptsManualRulesFile() {
        val root = newRoot()
        addBaseComponents(root)
        val rules = listOf(ManualCorrectionRule("rule-1", "nihao", "你好", true, 10, 1, 2))
        File(root, "manual_corrections").apply { check(mkdirs()) }
            .resolve("manual-corrections.json").writeText(ManualCorrectionRuleContract.encode(rules))
        val declared = listOf("shared_prefs", "databases", "external", "recently_used", "clipboard_images", "clipboard_oplog", "manual_corrections")

        assertEquals(declared, BackupValidation.fullBackupComponents(root, 3, declared))
        assertEquals(rules, ManualCorrectionRuleContract.decode(BackupValidation.manualRulesFile(root).readText()))
        File(root, "manual_corrections/obsolete.journal").writeText("bad")
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.manualRulesFile(root) }
    }

    @Test
    fun transactionCanRestoreManualRulesIntoANewDirectory() {
        val root = newRoot()
        val pending = File(root, "pending").apply { check(mkdirs()) }
        File(pending, "journal").writeText("READY")
        val contents = "{\"format\":\"meng-manual-corrections\",\"version\":1,\"rules\":[]}"
        File(pending, "manual_corrections").apply { check(mkdirs()) }
            .resolve("manual-corrections.json").writeText(contents)
        val target = File(root, "app/files/smart")
        val transaction = BackupFileTransaction(
            pending, File(root, "rollback"), File(root, "state"), mapOf("manual_corrections" to target)
        )

        assertEquals(BackupFileTransaction.Result.APPLIED, transaction.apply())
        assertTrue(File(target, "manual-corrections.json").isFile)
        assertEquals(contents, File(target, "manual-corrections.json").readText())
    }

    @Test
    fun failedTransactionRemovesNewManualRulesFile() {
        val root = newRoot()
        val pending = File(root, "pending").apply { check(mkdirs()) }
        File(pending, "journal").writeText("READY")
        File(pending, "manual_corrections").apply { check(mkdirs()) }
            .resolve("manual-corrections.json").writeText("rules")
        val target = File(root, "app/files/smart")
        val transaction = BackupFileTransaction(
            pending, File(root, "rollback"), File(root, "state"), mapOf("manual_corrections" to target),
            fault = { stage -> if (stage == "applied:manual_corrections") error("simulated interruption") }
        )

        assertEquals(BackupFileTransaction.Result.ORIGINAL_RETAINED, transaction.apply())
        assertFalse(target.exists())
    }

    private fun newRoot(): File = Files.createTempDirectory("backup-components-").toFile().also(roots::add)

    private fun addBaseComponents(root: File) {
        listOf("shared_prefs", "databases", "external", "recently_used", "clipboard_images").forEach {
            check(File(root, it).mkdirs())
        }
        File(root, "clipboard_oplog").writeText("")
    }
}
