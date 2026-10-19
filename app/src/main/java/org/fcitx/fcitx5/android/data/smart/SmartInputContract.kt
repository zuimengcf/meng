/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

import org.fcitx.fcitx5.android.data.stats.SmartInputText

/** Stable v1 wire contract; no Android types, suitable for host-side unit tests. */
object SmartInputContract {
    const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 128 * 1024
    const val MAX_ROWS = 1000
    const val MAX_TTL_MS = 600_000L
    val booleanPreferences = linkedMapOf(
        "suggestion" to "smart_suggest_enabled",
        "correction" to "smart_pinyin_correct_enabled",
        "neighbor" to "smart_correct_neighbor",
        "fuzzy" to "smart_correct_fuzzy",
        "habit" to "smart_correct_habit",
        "learning" to "smart_stats_enabled"
    )
    val extraDefaults = linkedMapOf(
        "suggestion_limit" to "15", "correction_limit" to "5",
        "suggestion_label" to "", "correction_label" to "",
        "suggestion_source" to "builtin", "correction_source" to "builtin",
        "correction_placement" to "append"
    )
    val configurableKeys: Set<String> = booleanPreferences.keys + extraDefaults.keys

    fun validatePatch(patch: Map<String, String>): Map<String, String> {
        require(patch.size <= configurableKeys.size) { "too many configuration keys" }
        return patch.mapValues { (key, value) ->
            require(key in configurableKeys) { "unknown configuration key: $key" }
            when {
                key in booleanPreferences -> when (value) {
                    "1", "true" -> "1"
                    "0", "false" -> "0"
                    else -> throw IllegalArgumentException("$key requires 0/1 or true/false")
                }
                key.endsWith("_limit") -> {
                    val n = value.toIntOrNull()
                    require(n != null && n in 0..50) { "$key must be 0..50" }
                    n.toString()
                }
                key.endsWith("_label") -> {
                    require(value.toByteArray(Charsets.UTF_8).size <= 64 && safeText(value, true)) {
                        "$key must be a single line of at most 64 UTF-8 bytes"
                    }
                    value
                }
                key.endsWith("_source") -> {
                    require(value in setOf("builtin", "external", "merge")) { "invalid $key" }; value
                }
                else -> {
                    require(value in setOf("append", "prepend")) { "invalid correction_placement" }; value
                }
            }
        }
    }

    data class Candidate(val key: String, val text: String, val weight: Int = 1)

    private fun safeText(value: String, allowEmpty: Boolean = false): Boolean =
        (allowEmpty || value.isNotEmpty()) && value.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }

    /** Suggestions are scoped to the same final 1–2 Han characters used by native. */
    fun validateCandidates(rows: List<Candidate>, correction: Boolean): List<Candidate> {
        require(rows.size <= MAX_ROWS) { "at most $MAX_ROWS candidates per kind" }
        val seen = HashSet<Pair<String, String>>()
        val result = rows.map { row ->
            require(row.key.length <= 256 && safeText(row.key)) { "invalid candidate key" }
            val key = if (correction) SmartInputText.normalizeCode(row.key)
                else SmartInputText.tail(row.key)
            require(key != null) { "candidate key must be pinyin or end in Han characters" }
            require(row.text.toByteArray(Charsets.UTF_8).size <= 512 && safeText(row.text)) {
                "candidate text must be a non-empty single line of at most 512 UTF-8 bytes"
            }
            require(row.weight in 1..1_000_000) { "weight must be 1..1000000" }
            row.copy(key = key)
        }.filter { seen.add(it.key to it.text) }
        require(toTsv(result).toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "candidate table too large" }
        return result
    }

    fun parseTsv(tsv: String, correction: Boolean): List<Candidate> {
        require(tsv.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "candidate table too large" }
        val lines = tsv.lineSequence().filter { it.isNotEmpty() }.toList()
        require(lines.size <= MAX_ROWS) { "too many candidate rows" }
        return validateCandidates(lines.map { line ->
            val parts = line.removeSuffix("\r").split('\t')
            require(parts.size == 3) { "each row must be key<TAB>text<TAB>weight" }
            Candidate(parts[0], parts[1], parts[2].toIntOrNull()
                ?: throw IllegalArgumentException("weight must be an integer"))
        }, correction)
    }

    fun toTsv(rows: List<Candidate>): String = rows.joinToString("") { "${it.key}\t${it.text}\t${it.weight}\n" }

    fun validateTtl(ttlMs: Long) {
        require(ttlMs in 1..MAX_TTL_MS) { "ttl_ms must be 1..$MAX_TTL_MS" }
    }
}
