/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.stats

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors
import android.system.Os
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.stats.db.FreqWord
import org.fcitx.fcitx5.android.data.stats.db.InputDailyStats
import org.fcitx.fcitx5.android.data.stats.db.InputStatsDao
import org.fcitx.fcitx5.android.data.stats.db.InputStatsDatabase
import org.fcitx.fcitx5.android.data.stats.db.PinyinCorrect
import org.fcitx.fcitx5.android.data.stats.db.WordAssoc
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 「梦」输入统计管理器：功能 1 的地基。
 *
 * - 在 [commitText] 上屏处由 [record] 异步记录文本统计（绝不阻塞主输入路径）。
 * - 统计按自然日聚合，另维护高频词汇表（功能 2/3 常用词库的数据来源）。
 *
 * 数据独立于 fcitx 兼容层、剪切板，且不并入 UserDataManager 导出覆盖，保护用户配置。
 */
object InputStatsManager {

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private lateinit var db: InputStatsDatabase
    private lateinit var dao: InputStatsDao
    private lateinit var appContext: Context

    private val serialDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "meng-input-stats") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + serialDispatcher)

    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("smart_")) enqueue { syncNativeFiles() }
    }
    init {
        scope.launch {
            for (operation in operations) {
                try { operation() } catch (e: Exception) { Timber.w(e, "smart input operation failed") }
            }
        }
    }
    private fun enqueue(operation: suspend () -> Unit) {
        operations.trySend(operation).getOrThrow()
    }
    internal suspend fun <T> serialized(operation: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        enqueue {
            try { result.complete(operation()) } catch (e: Exception) { result.completeExceptionally(e) }
        }
        return result.await()
    }

    @Volatile
    private var initialized = false

    /** 由 Application 初始化一次。 */
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        // 数据库结构随版本演进（v3 新增 pinyin_correct 等表）。旧库版本与当前代码
        // 不符时，仅靠 fallbackToDestructiveMigration() 在某些 Room 版本/数据库状态下
        // 不会触发（实测会抛 IllegalStateException "migration X to Y required"）。
        // 这里显式检测并删除版本过旧的库文件，保证统计/联想/纠错在新 schema 下干净重建。
        deleteOutdatedDbIfNeeded(context)
        db = Room.databaseBuilder(context, InputStatsDatabase::class.java, InputStatsDatabase.DB_NAME)
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        dao = db.statsDao()
        initialized = true
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        // SharedPreferences keeps listeners weakly; retain a strong object field.
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        enqueue { syncNativeFiles() }
    }

    /**
     * 检测旧统计库的 PRAGMA user_version：若低于当前代码要求版本则删除整个库文件
     * （含 -wal/-shm），让 Room 以新 schema 重建。避免升级路径缺失导致的启动崩溃。
     */
    private fun deleteOutdatedDbIfNeeded(context: Context) {
        val currentVersion = InputStatsDatabase.CURRENT_VERSION
        val dbFile = context.getDatabasePath(InputStatsDatabase.DB_NAME)
        if (!dbFile.exists()) {
            return
        }
        try {
            // 只读方式打开并读取 user_version；不触发 Room 的迁移逻辑。
            val ver = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { it.version }
            if (ver < currentVersion) {
                Timber.i("meng_stats legacy version=$ver < current=$currentVersion; deleting to rebuild")
                context.deleteDatabase(InputStatsDatabase.DB_NAME)
            }
        } catch (e: Exception) {
            // 打开失败（损坏/锁）也删除重建，避免残留坏库
            Timber.w(e, "check legacy meng_stats failed; deleting")
            context.deleteDatabase(InputStatsDatabase.DB_NAME)
        }
    }

    /** 数据开关只控制是否新增学习；已有库和冷启动词表仍可导出。 */
    fun isEnabled(): Boolean =
        initialized && org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().smart.statsEnabled.getValue()

    /** 内部日期字符串 yyyy-MM-dd（取系统当前自然日）。 */
    private fun today(): String = LocalDate.now().format(dateFormat)

    /** 一周起点（本周一）。 */
    private fun weekStart(): String {
        val today = LocalDate.now()
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return monday.format(dateFormat)
    }

    /** 本月起点（1 号）。 */
    private fun monthStart(): String {
        val today = LocalDate.now()
        val first = today.withDayOfMonth(1)
        return first.format(dateFormat)
    }

    /**
     * 记录一次上屏文本。由 [FcitxInputMethodService.commitText] 调用。
     * 纯内存分析 + 异步落库，不阻塞输入。
     *
     * @param code 用户为产生本次上屏实际敲入的输入码（含纯首字母简拼/全拼），
     *             用于自学习「习惯码 → 上屏词」映射（功能 3 纠错依据之一）。
     */
    fun record(text: String, code: String = "") {
        val now = System.currentTimeMillis()
        val date = today()
        val (chinese, english, digits, punct, total) = analyze(text)
        enqueue {
            try {
                updateLastEndWordForSuggest(text)
                if (!isEnabled()) {
                    lastEndWord = null
                    lastEndAt = 0L
                    return@enqueue
                }
                // 不存在则插入当日行，再累加（避免并发下插入/更新竞态）
                val existed = dao.addDaily(date, chinese, english, digits, punct, total, now)
                if (existed == 0) {
                    dao.insertDaily(
                        InputDailyStats(
                            date = date,
                            chineseChars = chinese,
                            englishWords = english,
                            digits = digits,
                            punctuation = punct,
                            totalChars = total,
                            commitCount = 1,
                            updatedAt = now
                        )
                    )
                }
                recordWords(text, code)
                // 每笔学习后立即在同一串行队列导出，避免最后一笔滞留
                syncNativeFiles(rimeUserDir())
            } catch (e: Exception) {
                Timber.w(e, "InputStatsManager.record failed")
            }
        }
    }

    /**
     * 上一次上屏文本的最后一个词（跨上屏关联的起点）。
     * 用于把「上一次上屏尾词 → 本次上屏首词」的转移也记录下来，
     * 这是联想预测真正依赖的连续性数据。
     * 只在非空白上屏时更新，避免空格/标点破坏关联链。
     */
    @Volatile
    private var lastEndWord: String? = null
    private var lastEndAt: Long = 0L
    private const val SESSION_TIMEOUT_MS = 2 * 60 * 1000L

    /** 联想查询用的「最近上屏尾词」，在 [record] 同步入口更新（不等异步落库），
     * 供联想功能立即以它作为 prev 查询下一个候选词。与 [lastEndWord] 隔离，
     * 避免干扰跨上屏关联的学习链路（[lastEndWord] 仅供悄然自学习使用）。 */
    @Volatile
    private var lastEndWordForSuggest: String? = null

    /**
     * 提取高频词 + 连续性词汇关联（bigram 共现）。
     *
     * 高频词先做「连续汉字片段」与「常用单字」统计；
     * 连续性关联则是联想预测的【根本数据】：
     * - 同段文本内部的相邻词（滑动窗口 bigram）；
     * - 跨上屏：上一次上屏尾词 → 本次上屏首词。
     *
     * @param code 产生本次上屏敲入的输入码（可为纯简拼/全拼），用于学习「习惯码→上屏词」。
     */
    private suspend fun recordWords(text: String, code: String = "") {
        val now = System.currentTimeMillis()
        val runs = Regex("[\\u4e00-\\u9fff]+").findAll(text).map { it.value }.toList()
        if (runs.isEmpty()) { lastEndWord = null; lastEndWordForSuggest = null; return }
        val first = runs.first()
        val prev = if (text.all { it in '\u4e00'..'\u9fff' } &&
            now - lastEndAt <= SESSION_TIMEOUT_MS) lastEndWord else null
        // 每个完整汉字片段是一个词；不做 2-gram 滑窗，标点/换行已由片段边界断链。
        for (word in runs) {
            val bumped = dao.bumpWord(word, now)
            if (bumped == 0) dao.insertWord(FreqWord(word = word, count = 1, updatedAt = now))
        }
        if (prev != null && prev != first) bumpAssoc(prev.takeLast(2), first, now)
        if (text.all { it in '\u4e00'..'\u9fff' }) {
            lastEndWord = runs.last().takeLast(2)
            lastEndAt = now
        } else {
            lastEndWord = null
            lastEndAt = 0L
        }
        val normalizedCode = normalizeCode(code)
        if (normalizedCode != null && SmartInputText.isHanWord(text)) {
            learnCode(text, normalizedCode, now)
        }
    }

    /** 归一化输入码：仅保留小写字母，非纯字母串（含数字/符号）视为无效输入码返回 null。 */
    private fun normalizeCode(code: String): String? = SmartInputText.normalizeCode(code)

    /**
     * 学习一条「输入码 → 上屏词」映射。
     * 同一 (码,type) 累加出现次数，未命中则新建。
     * 这里统一记 type=3（习惯码）；邻键/模糊音的具体纠正交给 [SuggestCorrect] 在查询时判定，
     * 学习阶段只负责在「用户确实用了吗打出某个字/词」时固化下来。
     */
    private suspend fun learnCode(word: String, code: String, now: Long) {
        val key = PinyinCorrect.key(code, PinyinCorrect.TYPE_HABIT, word)
        val bumped = dao.bumpPinyinCorrect(key, now)
        if (bumped == 0) {
            dao.insertPinyinCorrect(
                PinyinCorrect(key = key, wrong = code, correct = word, type = PinyinCorrect.TYPE_HABIT, count = 1, updatedAt = now)
            )
        }
    }

    /** 按输入码查自学习的纠错候选（count 倒序）。 */
    suspend fun correctFor(code: String, limit: Int = 10): List<PinyinCorrect> {
        val c = normalizeCode(code) ?: return emptyList()
        return dao.pinyinCorrectFor(c, limit)
    }

    /** 自学习映射总量（供设置页展示）。 */
    suspend fun pinyinCorrectCount(): Long = dao.pinyinCorrectCount()

    /**
     * 从一次上屏文本提取「尾词」用于联想预测（与学习链 [lastEndWord] 独立）。
     * 取末尾连续的汉字片段作为尾词（长度 ≤ 2，超长取最后 2 字），便于作为 prev 命中更多关联。
     */
    private fun updateLastEndWordForSuggest(text: String) {
        lastEndWordForSuggest = SmartInputText.tail(text)
    }

    /** 
     * 联想预测入口：以「最近上屏尾词」为 prev，取联想候选（count 倒序）。
     * 由候选条/设置页调用；需要统计开关开启（数据为空时自然无候选）。
     */
    suspend fun suggestNext(limit: Int = 10): List<String> {
        if (!AppPrefs.getInstance().smart.suggestEnabled.getValue()) return emptyList()
        val prev = lastEndWordForSuggest ?: return emptyList()
        return dao.assocFor(prev, limit).map { it.next }
    }

    /** 联想预测入口（显式 prev），供测试/调试。 */
    suspend fun suggestFor(prev: String, limit: Int = 10): List<String> {
        if (!AppPrefs.getInstance().smart.suggestEnabled.getValue()) return emptyList()
        return dao.assocFor(prev, limit).map { it.next }
    }

    /** 累加一条 (prev → next) 连续性关联。 */
    private suspend fun bumpAssoc(prev: String, next: String, now: Long) {
        val key = WordAssoc.key(prev, next)
        val bumped = dao.bumpAssoc(key, now)
        if (bumped == 0) {
            dao.insertAssoc(WordAssoc(key = key, prev = prev, next = next, count = 1, updatedAt = now))
        }
    }

    /**
     * 文本分类统计。返回 (中文字符数, 英文单词数, 数字字符数, 标点符号数, 总字符数)。
     * 总字符数 = 中文字符 + 英文字母总字符数 + 数字 + 标点（不含空白）。
     */
    private fun analyze(text: String): Stats {
        var chinese = 0L
        var englishWords = 0L
        var englishChars = 0L
        var digits = 0L
        var punct = 0L
        var letterRun = false

        for (ch in text) {
            when {
                ch in '\u4e00'..'\u9fff' -> chinese++
                ch.isLetter() -> {
                    englishChars++
                    letterRun = true
                }
                ch.isDigit() -> digits++
                !ch.isWhitespace() -> punct++  // 非空白且非字母数字汉字的视为标点/符号
            }
            // 连续字母段结束时，英文单词数 +1
            if (letterRun && !(ch.isLetter())) {
                englishWords++
                letterRun = false
            }
        }
        if (letterRun) englishWords++
        val total = chinese + englishChars + digits + punct
        return Stats(chinese, englishWords, digits, punct, total)
    }

    data class Stats(
        val chineseChars: Long,
        val englishWords: Long,
        val digits: Long,
        val punctuation: Long,
        val totalChars: Long
    )

    /* ===== 对外查询接口（供 UI 页使用） ===== */

    suspend fun dailySummary(): List<InputDailyStats> = dao.getAllDaily()

    suspend fun todayStats(): InputDailyStats? = dao.getDaily(today())

    suspend fun weekStats(): List<InputDailyStats> = dao.getDailyFrom(weekStart())

    suspend fun monthStats(): List<InputDailyStats> = dao.getDailyFrom(monthStart())

    suspend fun topWords(limit: Int = 20): List<FreqWord> = dao.topWords(limit)

    /** 按「最近上屏词」取联想候选（连续性关联），功能 2 预测的数据入口。 */
    suspend fun assocFor(prev: String, limit: Int = 10): List<WordAssoc> = dao.assocFor(prev, limit)

    suspend fun assocCount(): Long = dao.assocCount()

    suspend fun clearAll() = serialized { clearAllInternal() }

    private suspend fun clearAllInternal() {
        dao.deleteAllDaily()
        dao.deleteAllWords()
        dao.deleteAllSessions()
        dao.deleteAllAssoc()
        dao.deleteAllPinyinCorrect()
        lastEndWord = null
        lastEndAt = 0L
        lastEndWordForSuggest = null
        syncNativeFiles()
    }

    /** 清空会话链，并与 record/clear 串行，供 Service 在编辑器切换时调用。 */
    fun resetSession() { enqueue { lastEndWord = null; lastEndAt = 0L; lastEndWordForSuggest = null } }

    /**
     * 异步清空全部统计（供设置页一键清空，不阻塞 UI 线程）。
     */
    fun clearAllAsync() {
        enqueue {
            try {
                clearAllInternal()
            } catch (e: Exception) {
                Timber.w(e, "InputStatsManager.clearAllAsync failed")
            }
        }
    }

    /* ===== native 词表导出 ===== */
    private const val SUGGESTION_FILE = "suggestion.dict"
    private const val CORRECTION_FILE = "correction.dict"
    private const val CONF_FILE = "smart-input.conf"

    private fun rimeUserDir(): java.io.File =
        java.io.File(appContext.getExternalFilesDir(null), "data/rime")

    suspend fun exportSuggestions(rimeUserDir: java.io.File) =
        serialized { syncNativeFiles(rimeUserDir) }

    fun requestNativeSync() { enqueue { syncNativeFiles() } }

    internal suspend fun syncNativeFiles(dir: java.io.File = rimeUserDir()) {
        if (!initialized) return
        dir.mkdirs()
        val assoc = dao.allAssoc(limit = 10000)
        val habits = dao.habitMappings(limit = 10000)
        val seedSuggestion = readSeed("suggestion.tsv")
        val seedCorrection = readSeed("correction.tsv")
        atomicWrite(java.io.File(dir, SUGGESTION_FILE), mergeSeed(seedSuggestion, assoc.map { "${it.prev}\t${it.next}\t${it.count}" }))
        atomicWrite(java.io.File(dir, CORRECTION_FILE), mergeSeed(seedCorrection, habits.map { "${it.wrong}\t${it.correct}\t${it.count}" }))
        org.fcitx.fcitx5.android.data.smart.SmartInputControl.writeNativeConfig(dir)
    }

    private fun readSeed(name: String): List<String> = runCatching { appContext.assets.open("smart-input/$name").bufferedReader().readLines().filter { it.isNotBlank() && !it.trim().startsWith("#") } }.getOrDefault(emptyList())
    private fun mergeSeed(seed: List<String>, learned: List<String>): String {
        val merged = LinkedHashMap<String, String>()
        (seed + learned).forEach { line ->
            val fields = line.split('\t')
            if (fields.size >= 3) merged[fields[0] + "\t" + fields[1]] = line
        }
        return merged.values.joinToString("\n", postfix = "\n")
    }
    private fun atomicWrite(target: java.io.File, content: String) {
        val tmp = java.io.File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        try {
            // POSIX rename replaces atomically on the same filesystem (API 21+).
            Os.rename(tmp.absolutePath, target.absolutePath)
        } catch (e: Exception) {
            tmp.delete()
            throw java.io.IOException("atomic rename failed: ${target.path}", e)
        }
    }
}
