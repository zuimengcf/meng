/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.data.stats.InputStatsManager
import java.io.File

/** Internal coroutine backend. Java callers use SmartInputApi; no exported IPC component. */
object SmartInputControl {
    private lateinit var context: Context
    private lateinit var state: SharedPreferences
    private lateinit var preferences: SharedPreferences

    fun init(context: Context) {
        this.context = context.applicationContext
        ManualCorrectionRuleStore.init(this.context)
        state = context.getSharedPreferences("smart_input_api", Context.MODE_PRIVATE)
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
    }

    internal fun effectiveConfig(): Map<String, String> = linkedMapOf<String, String>().apply {
        SmartInputContract.booleanPreferences.forEach { (key, pref) ->
            put(key, if (preferences.getBoolean(pref, false)) "1" else "0")
        }
        SmartInputContract.extraDefaults.forEach { (key, fallback) -> put(key, state.getString(key, fallback) ?: fallback) }
        put("external_expires_at_ms", state.getLong("external_expires_at_ms", 0).toString())
    }

    suspend fun getConfig(): Map<String, String> = InputStatsManager.serialized { effectiveConfig() }

    internal fun persistConfigUnlocked(patch: Map<String, String>) {
        val validated = SmartInputContract.validatePatch(patch.toMap())
        val boolEditor = preferences.edit()
        val extraEditor = state.edit()
        validated.forEach { (key, value) ->
            val pref = SmartInputContract.booleanPreferences[key]
            if (pref != null) boolEditor.putBoolean(pref, value == "1") else extraEditor.putString(key, value)
        }
        check(boolEditor.commit()) { "cannot persist boolean configuration" }
        check(extraEditor.commit()) { "cannot persist configuration" }
    }
    suspend fun configure(patch: Map<String, String>): Map<String, String> = InputStatsManager.serialized {
        val old = effectiveConfig().filterKeys { it in SmartInputContract.configurableKeys }
        try {
            persistConfigUnlocked(patch)
            InputStatsManager.syncNativeFiles()
            effectiveConfig()
        } catch (error: Throwable) {
            runCatching { persistConfigUnlocked(old); InputStatsManager.syncNativeFiles() }
                .exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
    /** Replace both caller-supplied tables without automatically changing source/enable switches. */
    suspend fun replaceCandidates(
        suggestions: List<SmartInputContract.Candidate>,
        corrections: List<SmartInputContract.Candidate>,
        ttlMs: Long = 30_000
    ): Map<String, String> {
        SmartInputContract.validateTtl(ttlMs)
        val s = SmartInputContract.validateCandidates(suggestions.toList(), false)
        val c = SmartInputContract.validateCandidates(corrections.toList(), true)
        require(SmartInputContract.toTsv(s).toByteArray(Charsets.UTF_8).size +
            SmartInputContract.toTsv(c).toByteArray(Charsets.UTF_8).size <= SmartInputContract.MAX_PAYLOAD_BYTES) {
            "combined candidate payload exceeds 128 KiB"
        }
        return InputStatsManager.serialized {
            // Publish a disabled batch first, then both tables, then its activation deadline.
            check(state.edit().putLong("external_expires_at_ms", 0).commit())
            writeNativeConfig(rimeDir())
            atomicWrite(File(rimeDir(), "external-suggestion.dict"), SmartInputContract.toTsv(s))
            atomicWrite(File(rimeDir(), "external-correction.dict"), SmartInputContract.toTsv(c))
            check(state.edit().putLong("external_expires_at_ms", System.currentTimeMillis() + ttlMs).commit())
            writeNativeConfig(rimeDir())
            effectiveConfig()
        }
    }

    suspend fun clearCandidates(): Map<String, String> = InputStatsManager.serialized {
        check(state.edit().putLong("external_expires_at_ms", 0).commit())
        writeNativeConfig(rimeDir())
        atomicWrite(File(rimeDir(), "external-suggestion.dict"), "")
        atomicWrite(File(rimeDir(), "external-correction.dict"), "")
        effectiveConfig()
    }

    /** Restore defaults with all smart switches off; learned dictionaries are not erased. */
    suspend fun resetConfig(): Map<String, String> = configure(
        SmartInputContract.extraDefaults + SmartInputContract.booleanPreferences.keys.associateWith {
            "0"
        }
    )

    /** Called on the stats serial queue. The configuration is always the final published file. */
    internal fun writeNativeConfig(dir: File) {
        atomicWrite(File(dir, "smart-input.conf"), effectiveConfig().entries.joinToString("") { "${it.key}=${it.value}\n" })
    }

    private fun rimeDir() = File(context.getExternalFilesDir(null), "data/rime")

    private fun atomicWrite(target: File, text: String) {
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true) { "native directory unavailable" }
        val temporary = File(target.parentFile, target.name + ".api.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        try { Os.rename(temporary.absolutePath, target.absolutePath) }
        catch (e: Exception) { temporary.delete(); throw e }
    }
}
