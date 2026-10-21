/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.smart

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.smart.ManualCorrectionRule
import org.fcitx.fcitx5.android.data.smart.ManualCorrectionRuleContract
import org.fcitx.fcitx5.android.data.smart.ManualCorrectionRuleStore
import splitties.dimensions.dp
import java.util.UUID

/** Long-lived, explicit pinyin -> output mappings; these are not an automatic replacement preview. */
class ManualCorrectionRulesFragment : Fragment() {
    private var rows: LinearLayout? = null
    private var countView: TextView? = null
    private var errorView: TextView? = null
    private var query = ""
    private var loadJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16)) }
        content.addView(TextView(ctx).apply {
            setText(R.string.manual_correction_rules_explanation)
            setPadding(0, 0, 0, ctx.dp(8))
        })
        content.addView(Button(ctx).apply {
            setText(R.string.manual_correction_add)
            setOnClickListener { showEditor(null) }
        })
        content.addView(EditText(ctx).apply {
            setHint(R.string.manual_correction_search)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            addTextChangedListener(SimpleTextWatcher { query = it; refresh() })
        })
        countView = TextView(ctx).apply { setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray)); content.addView(this) }
        errorView = TextView(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_red_dark)); content.addView(this)
        }
        rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
        return ScrollView(ctx).apply { addView(content) }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroyView() {
        loadJob?.cancel(); loadJob = null; rows = null; countView = null; errorView = null
        super.onDestroyView()
    }

    private fun refresh() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val rules = ManualCorrectionRuleStore.list(query.trim())
                render(rules)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(getString(R.string.manual_correction_load_failed)) }
        }
    }

    private fun render(rules: List<ManualCorrectionRule>) {
        val ctx = context ?: return
        rows?.removeAllViews()
        countView?.text = getString(R.string.manual_correction_count, rules.size)
        errorView?.text = ""
        if (rules.isEmpty()) {
            rows?.addView(TextView(ctx).apply {
                setText(R.string.manual_correction_empty)
                setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
            })
            return
        }
        rules.forEach { rule ->
            rows?.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, ctx.dp(8), 0, ctx.dp(8))
                addView(TextView(ctx).apply { text = "${rule.input} → ${rule.output}"; textSize = 16f })
                addView(TextView(ctx).apply { text = getString(R.string.manual_correction_priority, rule.priority) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Switch(ctx).apply {
                        setText(R.string.manual_correction_enabled)
                        isChecked = rule.enabled
                        setOnCheckedChangeListener { _, enabled -> setEnabled(rule, enabled) }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Button(ctx).apply { setText(R.string.edit); setOnClickListener { showEditor(rule) } })
                    addView(Button(ctx).apply { setText(R.string.manual_correction_delete); setOnClickListener { confirmDelete(rule) } })
                })
            })
        }
    }

    private fun showEditor(existing: ManualCorrectionRule?) {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20)) }
        val input = EditText(ctx).apply { hint = getString(R.string.manual_correction_input); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true); setText(existing?.input.orEmpty()) }
        val output = EditText(ctx).apply { hint = getString(R.string.manual_correction_output); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setSingleLine(true); setText(existing?.output.orEmpty()) }
        val priority = EditText(ctx).apply { hint = getString(R.string.manual_correction_priority_label); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true); setText(existing?.priority?.toString() ?: "100") }
        val enabled = Switch(ctx).apply { setText(R.string.manual_correction_enabled); isChecked = existing?.enabled ?: true }
        box.addView(input); box.addView(output); box.addView(priority); box.addView(enabled)
        val dialog = AlertDialog.Builder(ctx).setTitle(if (existing == null) R.string.manual_correction_add else R.string.manual_correction_edit).setView(box)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        var saveButton: Button? = null
        dialog.setOnDismissListener { saveButton?.isEnabled = true }
        dialog.setOnShowListener {
            saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton?.setOnClickListener {
                val inputText = input.text.toString()
                val outputText = output.text.toString()
                val priorityValue = priority.text.toString().toIntOrNull()
                val error = validate(inputText, outputText, priorityValue, existing)
                if (error != null) { Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val rule = existing?.copy(input = inputText, output = outputText, enabled = enabled.isChecked, priority = priorityValue!!, updatedAt = System.currentTimeMillis())
                    ?: ManualCorrectionRule(UUID.randomUUID().toString(), inputText, outputText, enabled.isChecked, priorityValue!!, System.currentTimeMillis(), System.currentTimeMillis())
                saveButton?.isEnabled = false
                save(rule, dialog, saveButton!!)
            }
        }
        dialog.show()
    }

    private fun validate(input: String, output: String, priority: Int?, existing: ManualCorrectionRule?): String? {
        if (!Regex("[a-z]{1,64}").matches(input)) return getString(R.string.manual_correction_input_invalid)
        if (output.isEmpty() || output.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } ||
            output.toByteArray(Charsets.UTF_8).size > 512) return getString(R.string.manual_correction_output_invalid)
        if (priority == null || priority !in 0..1_000_000) return getString(R.string.manual_correction_priority_invalid)
        if (existing == null) {
            // Store is authoritative; this gives an immediate duplicate error without dismissing the form.
            return null
        }
        return null
    }

    private fun save(rule: ManualCorrectionRule, dialog: AlertDialog, saveButton: Button) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val existing = ManualCorrectionRuleStore.list("")
                if (existing.any { it.input == rule.input && it.output == rule.output && it.id != rule.id }) {
                    Toast.makeText(requireContext(), R.string.manual_correction_duplicate, Toast.LENGTH_SHORT).show(); return@launch
                }
                ManualCorrectionRuleContract.validate(existing.filter { it.id != rule.id } + rule)
                ManualCorrectionRuleStore.save(rule)
                dialog.dismiss(); refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: getString(R.string.manual_correction_save_failed), Toast.LENGTH_SHORT).show()
            } finally {
                saveButton.isEnabled = true
            }
        }
    }

    private fun setEnabled(rule: ManualCorrectionRule, enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try { ManualCorrectionRuleStore.setEnabled(rule.id, enabled); refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(getString(R.string.manual_correction_save_failed)); refresh() }
        }
    }

    private fun confirmDelete(rule: ManualCorrectionRule) {
        AlertDialog.Builder(requireContext()).setMessage(getString(R.string.manual_correction_delete_confirm, rule.input, rule.output))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.manual_correction_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try { ManualCorrectionRuleStore.delete(rule.id); refresh() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { showError(getString(R.string.manual_correction_delete_failed)) }
                }
            }.show()
    }

    private fun showError(message: String) { if (isAdded) { errorView?.text = message; Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show() } }
    private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
}
