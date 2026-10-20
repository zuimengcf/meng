/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.ClipboardManageActivity
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.toast

/**
 * 剪切板设置页。
 *
 * 在原有监听设置之外，额外提供剪切板数据的：
 * - 管理：全屏卡片列表，点击条目编辑 / 删除 / 移到分组 / 拖动排序；
 * - 导出：备份全部剪切板条目、分组、同步身份、历史与图片到 ZIP；
 * - 导入：按稳定身份和版本合并 ZIP，兼容旧数据库备份；
 * - 分组：入口统一在全屏剪切板，包含筛选和管理。
 * 同步入口已移至「管理剪切板」页内的房间同步（顶部按钮 + 右下 FAB），本页不再放置外部同步入口。
 */
class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {

    // 导入（用于「导入」与「同步→导入」）
    private var importUri: Uri? = null
    private val importResult = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        importUri = uri
        importUri?.let { doImport(it) }
    }

    // 导出（统一 zip 载体：剪切板数据库文件 + 来源标记）
    private val exportResult = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) doExport(uri)
    }

    private fun doImport(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            try {
                val added = withContext(Dispatchers.IO) {
                    val input = ctx.contentResolver.openInputStream(uri) ?: error("无法打开导入文件")
                    input.use { ClipboardManager.importFrom(it) }
                }
                ctx.toast(ctx.getString(R.string.clipboard_import_result, added))
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            }
        }
    }

    private fun doExport(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            try {
                val n = withContext(Dispatchers.IO) {
                    val output = ctx.contentResolver.openOutputStream(uri) ?: error("无法创建导出文件")
                    output.use { ClipboardManager.exportTo(it) }
                }
                ctx.toast(ctx.getString(R.string.clipboard_export_result, n))
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            }
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()

        // 管理剪切板：全屏列表，点击条目直接编辑（无需长按）
        screen.addPreference(R.string.clipboard_manage_fullscreen, R.string.clipboard_manage_fullscreen_hint) {
            ctx.startActivity(android.content.Intent(ctx, ClipboardManageActivity::class.java))
        }

        screen.addPreference(R.string.clipboard_export) {
            // Visible count can be zero while groups, deletion tombstones and history still need backup.
            exportResult.launch("clipboard_${iso8601UTCDateTime()}.zip")
        }

        screen.addPreference(R.string.clipboard_import) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.clipboard_import)
                .setMessage(R.string.clipboard_import_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    importResult.launch("*/*")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }
}
