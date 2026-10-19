/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.market.MirrorStatus
import org.fcitx.fcitx5.android.market.SchemaListAdapter
import org.fcitx.fcitx5.android.market.SchemaMarketViewModel
import org.fcitx.fcitx5.android.market.SchemaSource
import org.fcitx.fcitx5.android.ui.main.MainViewModel

/**
 * 方案商店——展示可安装的 Rime 方案，弹窗给出下载链接（用户自行下载），
 * 并支持从本地 zip 导入安装 / 更新 / 卸载。
 */
class SchemaMarketFragment : Fragment() {

    private val viewModel: SchemaMarketViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SchemaListAdapter

    private var pendingImportSource: SchemaSource? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_schema_market, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.attachFcitx(mainViewModel.fcitx)

        val list = view.findViewById<RecyclerView>(R.id.schema_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = SchemaListAdapter(
            onShowLinks = { source -> showDownloadDialog(source) },
            onImport = { source -> launchFilePicker(source) },
            onUninstall = { source -> viewModel.uninstall(source) },
        )
        list.adapter = adapter

        viewModel.sources.observe(viewLifecycleOwner) { adapter.submitList(it) }
        viewModel.installedVersions.observe(viewLifecycleOwner) { adapter.notifyInstalled(it) }
        viewModel.busy.observe(viewLifecycleOwner) { adapter.notifyBusy(it) }
        viewModel.message.observe(viewLifecycleOwner) { msg ->
            if (msg.isNotEmpty()) {
                android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 弹出下载对话框：显示镜像连通状态、可点击链接，并提供「应用内下载」「导入」「复制全部」 */
    private fun showDownloadDialog(source: SchemaSource) {
        val ctx = requireContext()
        val title = ctx.getString(R.string.schema_download_title)
        val hint = ctx.getString(R.string.schema_link_hint, source.displayName, source.version)

        // 构建视图：提示 + 每个镜像一行（标签 · 状态 · 可点击链接）。
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 8)
        }
        container.addView(TextView(ctx).apply {
            text = hint
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        })

        // 附加组件（大模型语法包）：独立下载/更新/卸载入口（与主包分开更新）
        source.extraAsset?.let { extra ->
            container.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.schema_extra_asset_title, extra.displayName)
                textSize = 15f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(0xFF6A1B9A.toInt())
                setPadding(0, dp(12), 0, dp(4))
            })
            val installedVersion = viewModel.installedVersions.value?.get(extra.schemaId)
            val installed = installedVersion != null
            val hasUpdate = installed && installedVersion != extra.version
            val statusText = when {
                !installed -> ctx.getString(R.string.schema_extra_download)
                hasUpdate -> ctx.getString(R.string.schema_extra_download_update)
                else -> ctx.getString(R.string.schema_extra_installed, installedVersion)
            }
            container.addView(TextView(ctx).apply {
                text = statusText
                textSize = 14f
                setTextColor(0xFF37474F.toInt())
                setPadding(0, dp(2), 0, dp(4))
            })
            // 语言模型操作行：下载 / 更新 / 卸载
            container.addView(TextView(ctx).apply {
                text = when {
                    installed && hasUpdate -> ctx.getString(R.string.schema_extra_action_update)
                    installed -> ctx.getString(R.string.schema_extra_action_uninstall)
                    else -> ctx.getString(R.string.schema_extra_action_download)
                }
                textSize = 16f
                setTextColor(0xFF0277BD.toInt())
                setPadding(0, dp(2), 0, dp(4))
                setOnClickListener {
                    if (installed && !hasUpdate) {
                        viewModel.uninstallExtra(extra)
                    } else {
                        viewModel.downloadAndInstallExtra(extra, source)
                    }
                }
            })
        }
        source.mirrors.forEach { mirror ->
            val statusText = ctx.getString(when (mirror.status) {
                MirrorStatus.OK -> R.string.schema_mirror_status_ok
                MirrorStatus.SLOW -> R.string.schema_mirror_status_slow
                MirrorStatus.FAIL -> R.string.schema_mirror_status_fail
                MirrorStatus.UNKNOWN -> R.string.schema_mirror_status_unknown
            })
            // 探测到延迟时追加「xx ms」
            val latencyText = mirror.latencyMs?.let {
                " · ${it}ms"
            } ?: ""
            val color = when (mirror.status) {
                MirrorStatus.OK -> "#2E7D32"
                MirrorStatus.SLOW -> "#F9A825"
                MirrorStatus.FAIL -> "#C62828"
                MirrorStatus.UNKNOWN -> "#9E9E9E"
            }
            container.addView(TextView(ctx).apply {
                // 用 <a> 包住可点击链接，整行带状态前缀与延迟
                text = HtmlCompat.fromHtml(
                    "<b>${mirror.label}</b> <font color=\"$color\">($statusText$latencyText)</font> — <a href=\"${mirror.url}\">下载</a>",
                    HtmlCompat.FROM_HTML_MODE_LEGACY
                )
                textSize = 16f
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(Color.parseColor("#212121"))
                setPadding(0, dp(6), 0, dp(6))
            })
        }

        val scroll = ScrollView(ctx).apply { addView(container) }

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.schema_download_inapp) { _, _ ->
                // 应用内直接下载（带进度通知），无需用户手动导入
                viewModel.downloadAndInstall(source)
            }
            .setNeutralButton(R.string.schema_import) { _, _ ->
                launchFilePicker(source)
            }
            .setNegativeButton(R.string.schema_copy) { _, _ ->
                copyLinks(source.allUrls)
            }
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun copyLinks(links: List<String>) {
        val text = links.joinToString("\n")
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("schema links", text))
        android.widget.Toast.makeText(requireContext(), R.string.schema_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 拉起系统文件选择器，让用户选中本地下好的 zip */
    private fun launchFilePicker(source: SchemaSource) {
        pendingImportSource = source
        // 用 */* 避免部分文件管理器未识别 application/zip 导致无法选择
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching {
            startActivityForResult(intent, REQ_IMPORT_ZIP)
        }.onFailure {
            android.widget.Toast.makeText(requireContext(), R.string.schema_picker_failed, android.widget.Toast.LENGTH_SHORT).show()
            pendingImportSource = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_ZIP && resultCode == Activity.RESULT_OK) {
            val source = pendingImportSource ?: run {
                android.widget.Toast.makeText(requireContext(), R.string.schema_import_failed, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            pendingImportSource = null
            val uri: Uri? = data?.data
            if (uri == null) {
                android.widget.Toast.makeText(requireContext(), R.string.schema_import_failed, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            viewModel.importZip(source, uri)
        }
    }

    companion object {
        private const val REQ_IMPORT_ZIP = 3001
    }
}