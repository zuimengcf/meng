/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.data.stats.InputStatsManager
import org.fcitx.fcitx5.android.data.stats.db.FreqWord
import org.fcitx.fcitx5.android.data.stats.db.InputDailyStats
import org.fcitx.fcitx5.android.data.stats.db.PinyinCorrect
import org.fcitx.fcitx5.android.data.stats.db.WordAssoc

/** Complete, bounded smart-input backup. Validation is side-effect free. */
object SmartInputBundle {
    const val FORMAT = "meng-smart-input"
    const val VERSION = 3
    internal const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_ROWS = 200_000
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  "; isLenient = false; ignoreUnknownKeys = false }

    data class Preview(val configCount: Int, val dailyCount: Int, val wordCount: Int,
        val associationCount: Int, val correctionCount: Int, val formatVersion: Int,
        val manualRuleCount: Int = 0, val enabledManualRuleCount: Int = 0,
        val preservesManualRules: Boolean = false) {
        val learningCount: Int get() = wordCount + associationCount + correctionCount
        val learningParams: Int get() = configCount
    }
    data class Payload(val config: Map<String, String>, val learning: InputStatsManager.LearningSnapshot,
        val preview: Preview, val manualRules: List<ManualCorrectionRule>? = null)

    suspend fun export(@Suppress("UNUSED_PARAMETER") context: Context): String = InputStatsManager.serialized {
        InputStatsManager.requireNoPendingRecoveryUnlocked()
        encode(SmartInputControl.effectiveConfig().filterKeys { it in SmartInputContract.configurableKeys },
            InputStatsManager.learningSnapshotUnlocked(), ManualCorrectionRuleStore.snapshotUnlocked())
    }

    internal fun encode(config: Map<String, String>, snapshot: InputStatsManager.LearningSnapshot, manualRules: List<ManualCorrectionRule> = emptyList()): String {
        require(config.keys == SmartInputContract.configurableKeys) { "incomplete configuration" }
        SmartInputContract.validatePatch(config)
        validateSnapshot(snapshot)
        ManualCorrectionRuleContract.validate(manualRules)
        val encoded = json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("format", FORMAT); put("version", VERSION); put("created_at_ms", System.currentTimeMillis())
            put("config", buildJsonObject { config.forEach { (k, v) -> put(k, v) } })
            put("learning_snapshot", snapshotJson(snapshot))
            put("manual_rules", json.parseToJsonElement(ManualCorrectionRuleContract.encode(manualRules)).jsonObject.getValue("rules"))
        })
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "smart backup is too large" }
        return encoded
    }

    fun validate(text: String): Payload {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "smart backup is too large" }
        val root = json.parseToJsonElement(text).jsonObject
        require(root["format"]?.jsonPrimitive?.content == FORMAT) { "unsupported smart backup format" }
        val versionValue = root["version"]?.jsonPrimitive ?: error("missing format version")
        require(!versionValue.isString) { "format version must be a number" }
        val version = versionValue.content.toIntOrNull() ?: error("invalid format version")
        require(version == VERSION || version == 2) { "unsupported smart backup version: $version" }
        requireKeys(root, setOf("format", "version", "created_at_ms", "config", "learning_snapshot") +
            if (version == VERSION) setOf("manual_rules") else emptySet(), "backup")
        val created = root["created_at_ms"]?.jsonPrimitive?.long ?: error("missing creation time")
        require(created >= 0) { "invalid creation time" }
        val configObj = root["config"]?.jsonObject ?: error("missing config")
        require(configObj.size <= SmartInputContract.configurableKeys.size) { "too many configuration keys" }
        val config = linkedMapOf<String, String>()
        configObj.forEach { (k, v) ->
            require(v.jsonPrimitive.isString) { "configuration values must be strings" }
            require(config.put(k, v.jsonPrimitive.content) == null) { "duplicate configuration key" }
        }
        require(config.keys == SmartInputContract.configurableKeys) { "incomplete configuration" }
        val validatedConfig = SmartInputContract.validatePatch(config)
        val snapshot = parseSnapshot(root["learning_snapshot"] ?: error("missing learning_snapshot"))
        validateSnapshot(snapshot)
        val rules = if (version == VERSION) ManualCorrectionRuleContract.decode(buildJsonObject {
            put("format", "meng-manual-corrections"); put("version", 1)
            put("rules", root.getValue("manual_rules"))
        }.toString()) else null
        return Payload(validatedConfig, snapshot, Preview(validatedConfig.size, snapshot.daily.size,
            snapshot.words.size, snapshot.associations.size, snapshot.corrections.size, version,
            rules?.size ?: 0, rules?.count { it.enabled } ?: 0, rules == null), rules)
    }

    /** A durable rollback journal precedes any mutation. All learning/config operations share one queue. */
    suspend fun apply(context: Context, payload: Payload) = withContext(NonCancellable) {
        // Payload is public: validate it again even when not created by the preview UI.
        InputStatsManager.serialized {
            ManualCorrectionRuleStore.recoverPendingUnlocked()
            recoverPendingUnlocked(context)
            val checked = validate(encode(payload.config, payload.learning,
                payload.manualRules ?: ManualCorrectionRuleStore.snapshotUnlocked()))
            val journal = SmartInputBundleJournal(context.filesDir)
            val original = encode(SmartInputControl.effectiveConfig().filterKeys { it in SmartInputContract.configurableKeys },
                InputStatsManager.learningSnapshotUnlocked(), ManualCorrectionRuleStore.snapshotUnlocked())
            try {
                journal.apply(original, checked, ::publishUnlocked)
            } catch (error: Throwable) {
                if (journal.hasPending) InputStatsManager.markImportRecoveryPending()
                throw error
            }
        }
    }

    /** Invoked before normal learning/native publication on startup, and before another import. */
    internal suspend fun recoverPendingUnlocked(context: Context) {
        try {
            SmartInputBundleJournal(context.filesDir).recover(::publishUnlocked)
            InputStatsManager.markImportRecoveryComplete()
        } catch (error: Throwable) {
            InputStatsManager.markImportRecoveryPending()
            throw error
        }
    }
    private suspend fun publishUnlocked(payload: Payload) {
        InputStatsManager.learningTransactionUnlocked {
            SmartInputControl.persistConfigUnlocked(payload.config)
            InputStatsManager.replaceLearningSnapshotUnlocked(payload.learning)
            // A v2 rollback journal never contained manual rules; preserve them rather than guess.
            payload.manualRules?.let { ManualCorrectionRuleStore.replaceUnlocked(it) }
            InputStatsManager.syncNativeFiles()
        }
    }

    private fun snapshotJson(s: InputStatsManager.LearningSnapshot) = buildJsonObject {
        put("daily", buildJsonArray { s.daily.forEach { add(buildJsonObject {
            put("date", it.date); put("chinese_chars", it.chineseChars); put("english_words", it.englishWords)
            put("digits", it.digits); put("punctuation", it.punctuation); put("total_chars", it.totalChars)
            put("commit_count", it.commitCount); put("updated_at", it.updatedAt)
        }) } })
        put("words", buildJsonArray { s.words.forEach { add(buildJsonObject {
            put("word", it.word); put("count", it.count); put("updated_at", it.updatedAt)
        }) } })
        put("associations", buildJsonArray { s.associations.forEach { add(buildJsonObject {
            put("key", it.key); put("prev", it.prev); put("next", it.next); put("count", it.count); put("updated_at", it.updatedAt)
        }) } })
        put("corrections", buildJsonArray { s.corrections.forEach { add(buildJsonObject {
            put("key", it.key); put("wrong", it.wrong); put("correct", it.correct); put("type", it.type)
            put("count", it.count); put("updated_at", it.updatedAt)
        }) } })
    }

    private fun parseSnapshot(element: JsonElement): InputStatsManager.LearningSnapshot {
        val obj = element.jsonObject
        requireKeys(obj, setOf("daily", "words", "associations", "corrections"), "learning_snapshot")
        fun array(name: String): JsonArray = obj[name] as? JsonArray ?: error("$name must be an array")
        val daily = array("daily").map { row(it, "daily") as InputDailyStats }
        val words = array("words").map { row(it, "words") as FreqWord }
        val assoc = array("associations").map { row(it, "associations") as WordAssoc }
        val corrections = array("corrections").map { row(it, "corrections") as PinyinCorrect }
        require(daily.size <= MAX_ROWS && words.size <= MAX_ROWS && assoc.size <= MAX_ROWS && corrections.size <= MAX_ROWS) { "learning snapshot is too large" }
        return InputStatsManager.LearningSnapshot(daily, words, assoc, corrections)
    }

    private fun row(e: JsonElement, kind: String): Any = when (kind) {
        "daily" -> { val o = e.jsonObject; requireKeys(o, setOf("date","chinese_chars","english_words","digits","punctuation","total_chars","commit_count","updated_at"), kind); InputDailyStats(o.req("date"), o.nonNegative("chinese_chars"), o.nonNegative("english_words"), o.nonNegative("digits"), o.nonNegative("punctuation"), o.nonNegative("total_chars"), o.nonNegative("commit_count"), o.nonNegative("updated_at")) }
        "words" -> { val o = e.jsonObject; requireKeys(o, setOf("word","count","updated_at"), kind); FreqWord(o.req("word").also { require(it.isNotEmpty() && safe(it)) }, o.nonNegative("count"), o.nonNegative("updated_at")) }
        "associations" -> { val o = e.jsonObject; requireKeys(o, setOf("key","prev","next","count","updated_at"), kind); val p=o.req("prev"); val n=o.req("next"); require(o.req("key") == WordAssoc.key(p,n)); WordAssoc(o.req("key"), p,n,o.nonNegative("count"),o.nonNegative("updated_at")) }
        else -> { val o = e.jsonObject; requireKeys(o, setOf("key","wrong","correct","type","count","updated_at"), kind); val w=o.req("wrong"); val c=o.req("correct"); val t=o.int("type"); require(t in 1..3); require(o.req("key") == PinyinCorrect.key(w,t,c) || o.req("key") == PinyinCorrect.key(w,t)); PinyinCorrect(o.req("key"),w,c,t,o.nonNegative("count"),o.nonNegative("updated_at")) }
    }

    @Suppress("UNCHECKED_CAST") private fun validateSnapshot(s: InputStatsManager.LearningSnapshot) {
        require(s.daily.size <= MAX_ROWS && s.words.size <= MAX_ROWS && s.associations.size <= MAX_ROWS && s.corrections.size <= MAX_ROWS) { "learning snapshot is too large" }
        require(s.daily.map { it.date }.toSet().size == s.daily.size) { "duplicate daily key" }
        require(s.words.map { it.word }.toSet().size == s.words.size) { "duplicate word key" }
        require(s.associations.map { it.key }.toSet().size == s.associations.size) { "duplicate association key" }
        require(s.corrections.map { it.key }.toSet().size == s.corrections.size) { "duplicate correction key" }
        s.daily.forEach { require(runCatching { java.time.LocalDate.parse(it.date) }.isSuccess && it.chineseChars >= 0 && it.englishWords >= 0 && it.digits >= 0 && it.punctuation >= 0 && it.totalChars >= 0 && it.commitCount >= 0 && it.updatedAt >= 0) }
        s.words.forEach { require(it.word.isNotEmpty() && safe(it.word) && it.count >= 0 && it.updatedAt >= 0) }
        s.associations.forEach { require(it.key == WordAssoc.key(it.prev,it.next) && safe(it.prev) && safe(it.next) && it.count >= 0 && it.updatedAt >= 0) }
        s.corrections.forEach { require((it.key == PinyinCorrect.key(it.wrong,it.type,it.correct) || it.key == PinyinCorrect.key(it.wrong,it.type)) && it.type in 1..3 && safe(it.wrong) && safe(it.correct) && it.count >= 0 && it.updatedAt >= 0) }
    }
    private fun safe(s: String) = s.isNotEmpty() && s.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
    private fun requireKeys(o: JsonObject, keys: Set<String>, what: String) { require(o.keys == keys) { "invalid $what fields" } }
    private fun JsonObject.req(k: String) = jsonPrimitive(k).content
    private fun JsonObject.nonNegative(k: String) = jsonPrimitive(k).long.also { require(it >= 0) { "$k must be non-negative" } }
    private fun JsonObject.int(k: String) = jsonPrimitive(k).content.toIntOrNull() ?: error("$k must be an integer")
    private fun JsonObject.jsonPrimitive(k: String) = this[k]?.jsonPrimitive ?: error("missing $k")
}
