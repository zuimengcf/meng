/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.toast

/**
 * 剪切板分组管理弹窗：列出全部分组，支持新建、重命名、删除。
 * 分组仅用于快速寻找，不影响 fcitx 数据兼容。
 */
object ClipboardGroupDialog {

    /** [onChanged] 仅在分组操作成功后调用；保留无回调的旧调用方式。 */
    fun show(ctx: Context, onChanged: () -> Unit = {}) {
        val parent = (ctx as? LifecycleOwner)?.lifecycleScope
        val scope = CoroutineScope(
            (parent?.coroutineContext ?: Dispatchers.Main.immediate) +
                SupervisorJob(parent?.coroutineContext?.get(Job))
        )
        scope.launch {
            try {
                val groups = withContext(Dispatchers.IO) { ClipboardManager.getGroups() }
                if (!isActive) return@launch
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(R.string.clipboard_groups)
                    .setItems(
                        (listOf(ctx.getString(R.string.group_new)) + groups.map { it.name })
                            .toTypedArray()
                    ) { _, which ->
                        if (which == 0) {
                            promptNewGroup(ctx, onChanged)
                        } else {
                            val group = groups[which - 1]
                            promptManage(ctx, group.id, group.name, onChanged)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                dialog.setOnDismissListener { scope.cancel() }
                dialog.show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) ctx.toast(e)
                scope.cancel()
            }
        }
    }

    private fun promptNewGroup(ctx: Context, onChanged: () -> Unit) {
        val input = EditText(ctx)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.group_new)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveName(ctx, dialog, input, null, onChanged)
            }
        }
        dialog.show()
    }

    private fun promptManage(ctx: Context, groupId: Int, name: String, onChanged: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(name)
            .setItems(
                arrayOf(
                    ctx.getString(R.string.group_rename),
                    ctx.getString(R.string.group_delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> promptRename(ctx, groupId, name, onChanged)
                    1 -> promptDelete(ctx, groupId, name, onChanged)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptRename(
        ctx: Context,
        groupId: Int,
        oldName: String,
        onChanged: () -> Unit,
    ) {
        val input = EditText(ctx).apply { setText(oldName); setSelection(length()) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.group_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveName(ctx, dialog, input, groupId, onChanged, oldName)
            }
        }
        dialog.show()
    }

    private fun promptDelete(
        ctx: Context,
        groupId: Int,
        name: String,
        onChanged: () -> Unit,
    ) {
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.group_delete)
            .setMessage(ctx.getString(R.string.group_delete_confirm, name))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val scope = bindScope(dialog)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { ClipboardManager.deleteGroup(groupId) }
                        if (!isActive) return@launch
                        onChanged()
                        dialog.dismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isActive) {
                            ctx.toast(e)
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun saveName(
        ctx: Context,
        dialog: AlertDialog,
        input: EditText,
        groupId: Int?,
        onChanged: () -> Unit,
        oldName: String? = null,
    ) {
        val name = input.text.toString().trim()
        if (name.isEmpty()) {
            input.error = "名称不能为空"
            return
        }
        if (name == oldName) {
            input.error = "名称未改变"
            return
        }
        val scope = bindScope(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        scope.launch {
            try {
                val duplicate = withContext(Dispatchers.IO) {
                    ClipboardManager.getGroups().any { it.name == name && it.id != groupId }
                }
                if (duplicate) {
                    if (isActive) {
                        input.error = "名称已存在"
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    if (groupId == null) ClipboardManager.addGroup(name)
                    else ClipboardManager.renameGroup(groupId, name)
                }
                if (!isActive) return@launch
                onChanged()
                dialog.dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    ctx.toast(e)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                }
            }
        }
    }

    /** 每个弹窗拥有自己的可取消作用域，关闭后不会再更新 UI 或弹错误提示。 */
    private fun bindScope(dialog: AlertDialog): CoroutineScope {
        var context: Context? = dialog.context
        while (context is android.content.ContextWrapper && context !is LifecycleOwner) {
            context = context.baseContext
        }
        val parent = (context as? LifecycleOwner)?.lifecycleScope?.coroutineContext?.get(Job)
        val scope = CoroutineScope(SupervisorJob(parent) + Dispatchers.Main.immediate)
        dialog.setOnDismissListener { scope.cancel() }
        return scope
    }
}
