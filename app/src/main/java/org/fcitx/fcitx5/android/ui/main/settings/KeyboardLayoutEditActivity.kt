/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.databinding.ActivityKeyboardLayoutEditorBinding
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.config.KeySpec
import org.fcitx.fcitx5.android.input.keyboard.config.KeyboardLayoutConfig
import org.fcitx.fcitx5.android.input.keyboard.config.KeyboardLayoutLoader
import org.fcitx.fcitx5.android.input.keyboard.config.ChineseKeyboardSymbols
import org.fcitx.fcitx5.android.utils.toast
import java.io.IOException
import splitties.dimensions.dp

/**
 * 键盘布局可视化编辑器。
 *
 * 复用真实输入法的绘制路径，一比一复刻真实键盘：
 * - 背景 = theme.backgroundDrawable(keyBorder)（与 InputView.customBackground 一致）
 * - 键盘 = TextKeyboard / NumberKeyboard 真实组件（与 KeyboardWindow 一致）
 * - 高度 = 屏高 * keyboardHeightPercent / 100（与真实键盘高度设置一致）
 *
 * 交互：直接在这把真实键盘上点按按键编辑（点击弹出编辑框，长按删除按键），
 * 顶部提供「添加行」「添加按键」以扩展现有布局。无任何自造假 UI。
 */
class KeyboardLayoutEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeyboardLayoutEditorBinding

    /** 布局名：Text 或 Number。 */
    private val layoutName: String by lazy {
        intent.getStringExtra(EXTRA_LAYOUT).orEmpty().ifEmpty { "Text" }
    }

    /** 当前内存中的配置（rows + keys 表）。 */
    private var config = KeyboardLayoutConfig()

    private var pendingExportConfig: KeyboardLayoutConfig? = null

    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importConfig(uri)
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeExport(uri, pendingExportConfig ?: config)
        pendingExportConfig = null
    }

    companion object {
        const val EXTRA_LAYOUT = "layout_name"
        private const val IMPORT_LIMIT_BYTES = 1024 * 1024
        private const val STATE_CONFIG = "keyboard_layout_editor_config"
        private const val STATE_EXPORT = "keyboard_layout_editor_export"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardLayoutEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdge()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val n = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.kbEditorTopbar.updatePadding(top = s.top)
            insets
        }

        binding.kbEditorTitle.text =
            getString(if (layoutName == "Number") R.string.keyboard_editor_numbers else R.string.keyboard_editor_letters)
        binding.kbEditorBack.setOnClickListener { finish() }
        binding.kbEditorSave.setOnClickListener { saveConfig() }
        binding.kbEditorReload.setOnClickListener { loadConfig() }
        binding.kbEditorImport.setOnClickListener { importDocument.launch(arrayOf("application/json", "text/plain", "*/*")) }
        binding.kbEditorExport.setOnClickListener { startExport(config, "${layoutName}-layout.json") }
        binding.kbEditorExportDefault.setOnClickListener {
            val defaults = KeyboardLayoutLoader.defaultConfig(layoutName)
            if (defaults == null) toast("没有可导出的默认配置")
            else startExport(defaults, "${layoutName}-layout-default.json")
        }

        // 点击预览空白处 → 添加按键菜单（作为「添加」入口，不破坏按键点按编辑）
        binding.kbEditorPreview.setOnClickListener { showActions() }

        loadConfig()
        pendingExportConfig = savedInstanceState?.getString(STATE_EXPORT)?.let {
            runCatching { KeyboardLayoutLoader.decodeConfig(it) }.getOrNull()
        }
        savedInstanceState?.getString(STATE_CONFIG)?.let { saved ->
            runCatching { KeyboardLayoutLoader.decodeConfig(saved) }.onSuccess {
                config = it
                renderPreview()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        runCatching { outState.putString(STATE_CONFIG, KeyboardLayoutLoader.encodeConfig(config)) }
        pendingExportConfig?.let { value ->
            runCatching { outState.putString(STATE_EXPORT, KeyboardLayoutLoader.encodeConfig(value)) }
        }
        super.onSaveInstanceState(outState)
    }

    /** 键盘高度（px）= 屏高基值 * percent / 100，与真实键盘一致。 */
    private fun keyboardHeightPx(): Int {
        val base = resources.displayMetrics.heightPixels
        val percent = AppPrefs.getInstance().keyboard.keyboardHeightPercent.getValue()
        return base * percent / 100
    }

    /** 加载配置并渲染真实键盘预览。 */
    private fun loadConfig() {
        config = KeyboardLayoutLoader.loadConfig(layoutName) ?: KeyboardLayoutConfig()
        if (config.rows.isEmpty()) config = config.copy(rows = listOf(emptyList()))
        binding.kbEditorPreview.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, keyboardHeightPx()
        )
        renderPreview()
    }

    private fun startExport(value: KeyboardLayoutConfig, filename: String) {
        try {
            KeyboardLayoutLoader.encodeConfig(value)
            pendingExportConfig = value
            exportDocument.launch(filename)
        } catch (e: Exception) {
            toast("无法导出：${e.message}")
        }
    }

    private fun importConfig(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > IMPORT_LIMIT_BYTES) throw IOException("文件超过 1MiB")
                    out.write(buffer, 0, count)
                }
                out.toByteArray().toString(Charsets.UTF_8)
            } ?: throw IOException("无法打开文件")
        } catch (e: Exception) {
            toast("导入失败：${e.message ?: "无法读取文件"}")
            return
        }
        val decoded = runCatching { KeyboardLayoutLoader.decodeConfig(text) }.getOrElse {
            toast("导入失败：${it.message ?: "JSON 配置校验不通过"}")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("替换当前内存配置？")
            .setMessage("导入只替换编辑器中的内存配置，点击保存后才会写入并生效。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("替换") { _, _ ->
                config = decoded
                if (config.rows.isEmpty()) config = config.copy(rows = listOf(emptyList()))
                renderPreview()
            }.show()
    }

    private fun writeExport(uri: Uri, value: KeyboardLayoutConfig) {
        try {
            val bytes = KeyboardLayoutLoader.encodeConfig(value).toByteArray(Charsets.UTF_8)
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(bytes)
            } ?: throw IOException("无法打开目标文件")
            toast("JSON 导出成功")
        } catch (e: Exception) {
            toast("导出失败：${e.message ?: "无法写入文件"}")
        }
    }

    /** 用真实输入法键盘一比一渲染当前布局。 */
    private fun renderPreview() {
        val preview = binding.kbEditorPreview
        preview.removeAllViews()
        val theme = ThemeManager.activeTheme
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        preview.background = theme.backgroundDrawable(keyBorder)
        val keyboard = try {
            if (layoutName == "Number") {
                NumberKeyboard(this, theme, KeyboardLayoutLoader.toKeyDefs(config))
            } else {
                TextKeyboard(this, theme, KeyboardLayoutLoader.toKeyDefs(config))
            }
        } catch (e: Throwable) {
            // 展示错误，便于定位为何真实键盘没渲染
            val err = TextView(this).apply {
                text = "键盘渲染失败：${e.message ?: e.javaClass.simpleName}"
                setTextSize(14f); setTextColor(0xFFB00020.toInt())
                setPadding(dp(16), dp(16), dp(16), dp(16))
                isClickable = false; isFocusable = false
            }
            preview.addView(err, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            return
        }
        preview.addView(keyboard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // 叠加透明触摸层：把点击/长按按坐标映射到 config 中对应的按键
        attachHitEditor(preview)
    }

    /**
     * 透明触摸层：按坐标(hitX%, hitY%)命中到 config.rows 的行列。
     * - 点按（立松手）：直接进入该键编辑对话框。
     * - 长按（按住 500ms 不移动）：弹出该键的「编辑/删除」操作菜单。
     * 行等分（BaseKeyboard 用约束链均分高度）；列按各键 percentWidth 比例累加。
     */
    private fun attachHitEditor(preview: FrameLayout) {
        val overlay = object : View(this) {
            private val touchSlop = resources.displayMetrics.density * 12f
            private val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout()
            private var downX = 0f
            private var downY = 0f
            private var tracking = false
            private var longPressFired = false
            private var moved = false
            private val longPressRunnable = Runnable {
                if (tracking && !moved) {
                    longPressFired = true
                    handleLongPress(downX, downY)
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x; downY = event.y
                        tracking = true
                        longPressFired = false
                        moved = false
                        postDelayed(longPressRunnable, longPressTimeout.toLong())
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!moved) {
                            moved = kotlin.math.abs(event.x - downX) > touchSlop ||
                                kotlin.math.abs(event.y - downY) > touchSlop
                            if (moved) removeCallbacks(longPressRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(longPressRunnable)
                        if (tracking) {
                            tracking = false
                            // 长按已触达则不再当点按重复弹框
                            if (!longPressFired && !moved) {
                                handlePress(downX, downY)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(longPressRunnable)
                        tracking = false
                        longPressFired = false
                    }
                }
                return super.onTouchEvent(event)
            }
        }
        overlay.setBackgroundColor(0x00000000) // 透明，不遮挡下面的真实键盘渲染
        preview.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    /** 长按某键：命中后弹出操作菜单（编辑 / 删除）。 */
    private fun handleLongPress(x: Float, y: Float) {
        val hit = resolvePress(x, y) ?: return
        val (rowIdx, keyName, spec) = hit
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.keyboard_editor_edit_key) + " · $keyName")
            .setItems(arrayOf(
                getString(R.string.keyboard_editor_edit),
                getString(R.string.keyboard_editor_delete_key)
            )) { _, which ->
                when (which) {
                    0 -> showKeyEditDialog(rowIdx, keyName, spec)
                    1 -> {
                        config = config.copy(
                            rows = config.rows.toMutableList().apply {
                                set(rowIdx, this[rowIdx].filter { it != keyName })
                            },
                            keys = config.keys - keyName
                        )
                        renderPreview()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 解析一次按压命中的键。返回改行号、键名与配置。 */
    private fun resolvePress(x: Float, y: Float): Triple<Int, String, KeySpec>? {
        if (config.rows.isEmpty()) return null
        val previewW = binding.kbEditorPreview.width.toFloat().coerceAtLeast(1f)
        val previewH = binding.kbEditorPreview.height.toFloat().coerceAtLeast(1f)
        val rowFrac = (y / previewH).coerceIn(0f, 1f)
        val colFrac = (x / previewW).coerceIn(0f, 1f)
        val rowIdx = (rowFrac * config.rows.size).toInt().coerceIn(0, config.rows.size - 1)
        val row = config.rows[rowIdx]
        if (row.isEmpty()) return null
        // 按各键 percentWidth（默认 0.1，width==0 视为剩余自动）累加定位列
        var acc = 0f
        var colIdx = row.lastIndex
        for (i in row.indices) {
            val spec = config.keys[row[i]]
            val w = specWidth(spec)
            acc += w
            if (colFrac <= acc) { colIdx = i; break }
        }
        val keyName = row.getOrNull(colIdx) ?: return null
        val spec = config.keys[keyName] ?: return null
        return Triple(rowIdx, keyName, spec)
    }

    private fun handlePress(x: Float, y: Float) {
        val hit = resolvePress(x, y) ?: run { showActions(); return }
        val (rowIdx, keyName, spec) = hit
        showKeyEditDialog(rowIdx, keyName, spec)
    }

    private fun specWidth(spec: KeySpec?): Float {
        if (spec == null) return 0.1f
        return when (spec) {
            is KeySpec.Letter -> 0.1f
            is KeySpec.Symbol -> spec.width.let { if (it <= 0f) 0.1f else it }
            is KeySpec.NumPad -> spec.width.let { if (it <= 0f) 0.1f else it }
            is KeySpec.Comma -> spec.width.let { if (it <= 0f) 0.1f else it }
            is KeySpec.Backspace -> spec.width.let { if (it <= 0f) 0.15f else it }
            is KeySpec.Return -> spec.width.let { if (it <= 0f) 0.15f else it }
            is KeySpec.LayoutSwitch -> spec.width.let { if (it <= 0f) 0.15f else it }
            is KeySpec.Space, is KeySpec.MiniSpace -> 0.5f
            is KeySpec.Caps -> 0.15f
            is KeySpec.Separator -> 0.1f
            is KeySpec.Lang -> 0.15f
        }
    }

    /** 弹出操作菜单：添加行 / 添加按键（附到某行），作为扩展入口。 */
    private fun showActions() {
        val items = mutableListOf(getString(R.string.keyboard_editor_add_row))
        // 让用户先选要加按键到哪一行
        config.rows.indices.forEach { r ->
            items.add(getString(R.string.keyboard_editor_add_key) + " → 第 ${r + 1} 行")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.keyboard_layout_editor)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) addRow()
                else promptAddKeyToRow(which - 1)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 追加一行（空行）。 */
    private fun addRow() {
        config = config.copy(rows = config.rows + listOf(emptyList()))
        renderPreview()
    }

    /** 往某行末尾追加一个按键。 */
    private fun promptAddKeyToRow(rowIdx: Int) {
        if (rowIdx !in config.rows.indices) return
        val types = arrayOf("letter", "symbol", "comma", "numpad", "caps", "separator", "backspace", "lang", "space", "mini_space", "return", "layout_switch")
        val labels = types.map { keyTypeLabel(it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.keyboard_editor_add_key)
            .setItems(labels.toTypedArray()) { _, which ->
                val spec: KeySpec = defaultSpecOf(types[which])
                val keyName = uniqueKeyName(types[which])
                config = config.copy(
                    keys = config.keys + (keyName to spec),
                    rows = config.rows.toMutableList().apply {
                        set(rowIdx, this[rowIdx] + keyName)
                    }
                )
                renderPreview()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 生成一个不与现有 key 冲突的按键名。 */
    private fun uniqueKeyName(base: String): String {
        var name = base
        var i = 1
        while (config.keys.containsKey(name)) { name = "${base}_$i"; i++ }
        return name
    }

    private fun keyTypeLabel(type: String): String = when (type) {
        "letter" -> "字母键（主字符 + 上滑字符）"
        "symbol" -> "符号键（提交一个字符）"
        "comma" -> "逗号键（Emoji/快捷短语菜单）"
        "numpad" -> "数字键（系统键码）"
        "caps" -> "大写切换"
        "separator" -> "隔音符（' 分词）"
        "backspace" -> "退格"
        "lang" -> "语言切换"
        "space" -> "空格"
        "mini_space" -> "迷你空格"
        "return" -> "回车"
        "layout_switch" -> "布局切换（?123 / ABC）"
        else -> type
    }

    private fun defaultSpecOf(type: String): KeySpec = when (type) {
        "letter" -> KeySpec.Letter(char = "a", alt = "1")
        "symbol" -> KeySpec.Symbol(char = ".")
        "comma" -> KeySpec.Comma()
        "numpad" -> KeySpec.NumPad(label = "1", code = "1", textSize = 30f)
        "caps" -> KeySpec.Caps
        "separator" -> KeySpec.Separator
        "backspace" -> KeySpec.Backspace()
        "lang" -> KeySpec.Lang
        "space" -> KeySpec.Space
        "mini_space" -> KeySpec.MiniSpace
        "return" -> KeySpec.Return()
        "layout_switch" -> KeySpec.LayoutSwitch(label = "?123", to = "")
        else -> KeySpec.Symbol(char = ".")
    }

    /** 根据按键类型弹编辑对话框。点按=编辑，长按=弹出操作菜单（含删除）。 */
    private fun showKeyEditDialog(rowIdx: Int, keyName: String, spec: KeySpec) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        var fChar: EditText? = null
        var fAlt: EditText? = null
        var lpReader: (() -> List<String>)? = null
        var fLabel: EditText? = null
        var fCode: EditText? = null
        var fWidth: EditText? = null
        var fTo: EditText? = null
        var fVariant: Spinner? = null
        var fType: Spinner? = null

        // 类型切换区：允许把当前键改成任意类型（含隔音符），放在最顶部
        val typeLabels = arrayOf("字母键", "符号键", "逗号键", "数字键", "大写切换", "隔音符（' 分词）", "退格", "语言切换", "空格", "迷你空格", "回车", "布局切换")
        val typeValues = arrayOf("letter", "symbol", "comma", "numpad", "caps", "separator", "backspace", "lang", "space", "mini_space", "return", "layout_switch")
        val currentType = when (spec) {
            is KeySpec.Letter -> "letter"
            is KeySpec.Symbol -> "symbol"
            is KeySpec.Comma -> "comma"
            is KeySpec.NumPad -> "numpad"
            is KeySpec.Caps -> "caps"
            is KeySpec.Separator -> "separator"
            is KeySpec.Backspace -> "backspace"
            is KeySpec.Lang -> "lang"
            is KeySpec.Space -> "space"
            is KeySpec.MiniSpace -> "mini_space"
            is KeySpec.Return -> "return"
            is KeySpec.LayoutSwitch -> "layout_switch"
        }
        fType = Spinner(this)
        fType!!.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        fType!!.setSelection(typeValues.indexOf(currentType).takeIf { it >= 0 } ?: 0)
        val typeTv = TextView(this).apply {
            text = getString(R.string.keyboard_editor_key_type)
            setTextSize(14f); setTextColor(0xFF666666.toInt())
        }
        layout.addView(typeTv)
        layout.addView(fType, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        val addField = { hint: String, text: String, field: EditText ->
            field.hint = hint
            field.setText(text)
            field.setPadding(dp(4), dp(6), dp(4), dp(6))
            layout.addView(field, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }

        when (spec) {
            is KeySpec.Letter -> {
                fChar = EditText(this).also { addField(getString(R.string.keyboard_editor_key_char), spec.char, it) }
                val lpInitial = ChineseKeyboardSymbols.resolve(spec.char, spec.alt, spec.popup)
                fAlt = EditText(this).also { addField(getString(R.string.keyboard_editor_key_alt), lpInitial.firstOrNull().orEmpty(), it) }
                // 编辑器与运行时使用同一候选来源。
                lpReader = buildLongPressChipEditor(layout, lpInitial)
            }
            is KeySpec.Symbol -> {
                fChar = EditText(this).also { addField(getString(R.string.keyboard_editor_key_char), spec.char, it) }
                fWidth = EditText(this).also {
                    addField(getString(R.string.keyboard_editor_key_width), spec.width.toString(), it)
                    it.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
            }
            is KeySpec.NumPad -> {
                fLabel = EditText(this).also { addField(getString(R.string.keyboard_editor_key_label), spec.label, it) }
                fCode = EditText(this).also { addField(getString(R.string.keyboard_editor_key_code), spec.code, it) }
                fWidth = EditText(this).also {
                    addField(getString(R.string.keyboard_editor_key_width), spec.width.toString(), it)
                    it.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
            }
            is KeySpec.Backspace, is KeySpec.Comma, is KeySpec.Return -> {
                val w = when (spec) {
                    is KeySpec.Backspace -> spec.width
                    is KeySpec.Comma -> spec.width
                    is KeySpec.Return -> spec.width
                    else -> 0.1f
                }
                fWidth = EditText(this).also {
                    addField(getString(R.string.keyboard_editor_key_width), w.toString(), it)
                    it.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
            }
            is KeySpec.LayoutSwitch -> {
                fLabel = EditText(this).also { addField(getString(R.string.keyboard_editor_key_label), spec.label, it) }
                fTo = EditText(this).also { addField(getString(R.string.keyboard_editor_key_to), spec.to, it) }
                fWidth = EditText(this).also {
                    addField(getString(R.string.keyboard_editor_key_width), spec.width.toString(), it)
                    it.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
            }
            else -> {}
        }

        val hasVariant = spec is KeySpec.Letter || spec is KeySpec.Symbol ||
            spec is KeySpec.NumPad || spec is KeySpec.Backspace ||
            spec is KeySpec.Comma || spec is KeySpec.LayoutSwitch
        if (hasVariant) {
            fVariant = Spinner(this)
            // 显示中文可读标签，内部仍映射到 variant 英文取值
            val variantLabels = arrayOf(
                "普通（主键配色）",
                "替代前景（文字用替代色）",
                "替代背景（按键底色用替代色）",
                "强调色（如回车键）",
            )
            val variantValues = arrayOf("normal", "alt", "alternative", "accent")
            val current = when (spec) {
                is KeySpec.Letter -> spec.variant
                is KeySpec.Symbol -> spec.variant
                is KeySpec.NumPad -> spec.variant
                is KeySpec.Backspace -> spec.variant
                is KeySpec.Comma -> spec.variant
                is KeySpec.LayoutSwitch -> spec.variant
                else -> "normal"
            }
            fVariant!!.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, variantLabels)
            val idx = variantValues.indexOf(current).takeIf { it >= 0 } ?: 0
            fVariant!!.setSelection(idx)
            val tv = TextView(this).apply {
                text = getString(R.string.keyboard_editor_key_variant)
                setTextSize(14f); setTextColor(0xFF666666.toInt())
            }
            layout.addView(tv)
            layout.addView(fVariant, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.keyboard_editor_edit_key) + " · $keyName")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val variant = fVariant?.let { arrayOf("normal", "alt", "alternative", "accent")[it.selectedItemPosition] } ?: "normal"
                val editedAlt = fAlt?.text?.toString()?.takeIf { it.isNotEmpty() }
                if (spec is KeySpec.Letter) {
                    val items = lpReader?.invoke().orEmpty()
                    // popup 只保存额外候选；空列表保留为 []，不恢复为旧版 null。
                    val alt = editedAlt ?: spec.alt
                    val popup = items.filter { it != alt }.distinct()
                    val letter = spec.copy(alt = alt, popup = popup)
                    config = config.copy(keys = config.keys + (keyName to letter))
                }
                val newType = fType?.let { typeValues[it.selectedItemPosition] } ?: currentType
                val newSpec: KeySpec = if (newType != currentType) {
                    // 用户切换了类型：按新类型默认配置整体替换
                    defaultSpecOf(newType)
                } else when (spec) {
                    is KeySpec.Letter -> (config.keys[keyName] as? KeySpec.Letter ?: spec).copy(
                        char = fChar?.text?.toString()?.ifEmpty { spec.char } ?: spec.char,
                        variant = variant
                    )
                    is KeySpec.Symbol -> spec.copy(
                        char = fChar?.text?.toString()?.ifEmpty { spec.char } ?: spec.char,
                        width = fWidth?.text?.toString()?.toFloatOrNull() ?: spec.width,
                        variant = variant
                    )
                    is KeySpec.NumPad -> spec.copy(
                        label = fLabel?.text?.toString()?.ifEmpty { spec.label } ?: spec.label,
                        code = fCode?.text?.toString()?.ifEmpty { spec.code } ?: spec.code,
                        width = fWidth?.text?.toString()?.toFloatOrNull() ?: spec.width,
                        variant = variant
                    )
                    is KeySpec.Backspace -> spec.copy(
                        width = fWidth?.text?.toString()?.toFloatOrNull() ?: spec.width,
                        variant = variant
                    )
                    is KeySpec.Comma -> spec.copy(
                        width = fWidth?.text?.toString()?.toFloatOrNull() ?: spec.width,
                        variant = variant
                    )
                    is KeySpec.Return -> spec.copy(
                        width = fWidth?.text?.toString()?.toFloatOrNull() ?: spec.width
                    )
                    is KeySpec.LayoutSwitch -> spec.copy(
                        label = fLabel?.text?.toString()?.ifEmpty { spec.label } ?: spec.label,
                        to = fTo?.text?.toString() ?: spec.to,
                        width = fWidth?.text?.toString()?.toFloatOrNull() ?: spec.width,
                        variant = variant
                    )
                    else -> spec
                }
                config = config.copy(keys = config.keys + (keyName to newSpec))
                renderPreview()
            }
            .setNeutralButton(R.string.keyboard_editor_delete_key) { _, _ ->
                config = config.copy(
                    rows = config.rows.toMutableList().apply {
                        set(rowIdx, this[rowIdx].filter { it != keyName })
                    },
                    keys = config.keys - keyName
                )
                renderPreview()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 构造「长按候选」胶囊式编辑器并加入 [container]。
     *
     * 交互（满足「强制分割 + 不显示分割符号 + 避免误删 + 可任意位置插入」）：
     * - 每个候选 = 一个独立胶囊（字符区 + 右侧 × 删除），胶囊之间无任何分隔符。
     * - 点胶囊【字符区】= 设为插入锚点（此位置高亮，下次添加插入到它前面）；再点取消。
     * - 点胶囊【×】= 删除该候选，剩余保持原相对顺序。
     * - 底部输入一串字符点「添加」：有锚点则【逐字符】插到锚点前，否则追加到末尾。
     *   （如 `abc` 删除 `a` 得 `bc`，点 `b` 设锚点、输入 `a` 添加 → 得回 `abc`）
     * - 返回一个读取器，调用时返回当前胶囊列表。
     */
    private fun buildLongPressChipEditor(
        container: LinearLayout,
        initial: List<String>,
    ): () -> List<String> {
        val items = initial.toMutableList()
        // 插入锚点：null 表示追加到末尾；否则表示插到该索引之前
        var insertBefore: Int? = null

        // 标题
        val title = TextView(this).apply {
            text = getString(R.string.keyboard_editor_key_longpress)
            setTextSize(14f); setTextColor(0xFF666666.toInt())
            setPadding(dp(0), dp(6), dp(0), dp(4))
        }
        container.addView(title)

        // 胶囊容器
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(0), dp(2), dp(0), dp(2))
        }
        container.addView(chipRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 锚点提示（显示在胶囊行下方，说明当前插入位置）
        val anchorHint = TextView(this).apply {
            setTextSize(12f)
            setPadding(dp(0), dp(2), dp(0), dp(4))
        }
        container.addView(anchorHint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        fun updateAnchorHint() {
            val pos = insertBefore
            anchorHint.text = when {
                pos == null -> getString(R.string.keyboard_editor_longpress_add_hint)
                pos == 0 -> getString(R.string.keyboard_editor_longpress_anchor_start)
                pos >= items.size -> getString(R.string.keyboard_editor_longpress_anchor_end)
                else -> getString(R.string.keyboard_editor_longpress_anchor_before) + "「" + items[pos] + "」"
            }
        }

        fun renderChips() {
            chipRow.removeAllViews()
            // 超出范围则视为取消锚点
            if (insertBefore != null && (insertBefore!! < 0 || insertBefore!! > items.size)) insertBefore = null
            items.indices.forEach { idx ->
                val item = items[idx]
                // 每个胶囊 = 水平容器（字符区 + ×）
                val chipContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // 字符区：设为插入锚点
                    setOnClickListener {
                        insertBefore = if (insertBefore == idx) null else idx
                        renderChips()
                    }
                }
                val charArea = TextView(this).apply {
                    text = item
                    setTextSize(14f)
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(dp(10), dp(6), dp(2), dp(6))
                }
                val delArea = TextView(this).apply {
                    text = "×"
                    setTextSize(14f)
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(dp(2), dp(6), dp(10), dp(6))
                    setOnClickListener {
                        // 删除：按索引移除，剩余保持原顺序；锚点在删除点之后则前移
                        items.removeAt(idx)
                        if (insertBefore != null) {
                            if (insertBefore!! > idx) insertBefore = insertBefore!! - 1
                            else if (insertBefore == idx) insertBefore = null
                        }
                        renderChips()
                    }
                }
                val isAnchor = insertBefore == idx
                val bgColor = if (isAnchor) 0xFFE67E22.toInt() else 0xFF3A6EA5.toInt()
                chipContainer.setBackgroundColor(bgColor)
                chipContainer.addView(charArea)
                chipContainer.addView(delArea)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(4); bottomMargin = dp(4) }
                chipRow.addView(chipContainer, lp)
            }
            // 空状态提示
            if (items.isEmpty()) {
                val empty = TextView(this).apply {
                    text = getString(R.string.keyboard_editor_longpress_empty)
                    setTextSize(12f); setTextColor(0xFFAAAAAA.toInt())
                }
                chipRow.addView(empty)
            }
            updateAnchorHint()
        }
        renderChips()

        // 底部：输入 + 添加
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(0), dp(2), dp(0), dp(2))
        }
        container.addView(addRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val input = EditText(this).apply {
            hint = getString(R.string.keyboard_editor_longpress_add_hint)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val addBtn = TextView(this).apply {
            text = getString(R.string.add)
            setTextSize(14f)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2E7D32.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        addRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6)
        })
        addRow.addView(addBtn)

        addBtn.setOnClickListener {
            val raw = input.text.toString()
            if (raw.isNotEmpty()) {
                // 强制逐字符分割：把输入串每个字符（含空格被跳过）拆成独立候选
                val chars = mutableListOf<String>()
                raw.forEach { ch -> if (!ch.isWhitespace()) { val s = ch.toString(); if (!chars.contains(s)) chars.add(s) } }
                // 插入到锚点之前，否则追加到末尾。倒序插入保证 chars 顺序正确。
                if (insertBefore == null) {
                    items.addAll(chars)
                } else {
                    val at = insertBefore!!
                    for (i in chars.indices.reversed()) items.add(at, chars[i])
                    insertBefore = at + chars.size
                }
                input.setText("")
                renderChips()
            }
        }

        return { items.toList() }
    }

    /** 写回外部 JSON。 */
    private fun saveConfig() {
        val ok = KeyboardLayoutLoader.saveConfig(layoutName, config)
        toast(if (ok) R.string.keyboard_editor_saved else R.string.keyboard_editor_save_failed)
    }
}
