/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.stats.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 按「自然日」聚合的输入统计。`date` 形如 yyyy-MM-dd。
 */
@Entity(tableName = InputDailyStats.TABLE_NAME)
data class InputDailyStats(
    @PrimaryKey
    val date: String,
    /** 中文字符数（含汉字/中文标点等 CJK 区段）。 */
    val chineseChars: Long = 0,
    /** 英文字母单词数（连续字母段计 1 词）。 */
    val englishWords: Long = 0,
    /** 数字字符数。 */
    val digits: Long = 0,
    /** 其它标点/符号数。 */
    val punctuation: Long = 0,
    /** 上屏总字符数（chineseChars + 英文字母数 + digits + punctuation）。 */
    val totalChars: Long = 0,
    /** 上屏次数（commitText 调用次数，粗略会话量）。 */
    val commitCount: Long = 0,
    /** 最近一次记录时间（毫秒）。 */
    val updatedAt: Long = 0
) {
    companion object {
        const val TABLE_NAME = "input_daily_stats"
    }
}

/**
 * 高频词汇统计。`word` 为提取出的词（先做单字/连续汉字片段）。
 * 作为功能 1 的「最常用词汇」数据源，也是功能 2/3 常用词库的候选基础。
 */
@Entity(tableName = FreqWord.TABLE_NAME)
data class FreqWord(
    @PrimaryKey
    val word: String,
    val count: Long = 1,
    /** 最近一次出现时间（毫秒），用于 LRU/热度排序。 */
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TABLE_NAME = "freq_words"
    }
}

/**
 * 自学习错拼/习惯码映射（功能 3 拼音纠错数据层）。
 *
 * 记录「用户实际敲的输入码 → 最终上屏的词」的关系，用于纠错：
 * - 输入码可能是全拼，也可能只是首字母简拼（如输入 "w" 上屏 "我们"）；
 * - `wrong` 存用户实际敲的编码（全拼/简拼/模糊错拼均可）；
 * - `correct` 存最终上屏的词（热度高即在纠错时优先作为修正候补）。
 *
 * `type` 区分来源：1=邻键错位触发学习、2=模糊音触发学习、3=用户习惯码（简拼/全拼）直接学习。
 * 统计开关开启时，在 [InputStatsManager.record] 同步学习（纯内存过滤 + 异步落库）。
 */
@Entity(tableName = PinyinCorrect.TABLE_NAME)
data class PinyinCorrect(
    /** 复合主键：wrong + type（同一错拼可被多类规则命中）。 */
    @PrimaryKey
    val key: String,
    /** 用户实际敲的输入码（小写，可为简拼/全拼）。 */
    val wrong: String,
    /** 最终上屏的词（纠错修正目标）。 */
    val correct: String,
    /** 来源：1=邻键错位、2=模糊音、3=习惯码（含简拼）。 */
    val type: Int,
    val count: Long = 1,
    /** 最近一次出现时间（毫秒），用于热度/LRU 排序。 */
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TABLE_NAME = "pinyin_correct"

        /** 来源类型常量。 */
        const val TYPE_NEIGHBOR = 1
        const val TYPE_FUZZY = 2
        const val TYPE_HABIT = 3

        /** 兼容旧键；新学习键包含 correct，避免同码多词互相覆盖。 */
        fun key(wrong: String, type: Int, correct: String? = null): String =
            if (correct == null) "$wrong\u0001$type" else "$wrong\u0001$type\u0001$correct"
    }
}

/**
 * 输入会话：一段时间内的连续输入聚合（可作「会话数」统计）。
 * 目前仅记录自然日维度即可，此表预留会话切分能力。
 */
@Entity(tableName = InputSession.TABLE_NAME)
data class InputSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val commitCount: Long = 0,
    val charCount: Long = 0
) {
    companion object {
        const val TABLE_NAME = "input_sessions"
    }
}

/**
 * 连续性词汇关联（bigram 共现）：前一上屏词 → 后一上屏词 的转移统计。
 *
 * 这是功能 2 联想预测的【根本数据】：
 * - 当用户上屏「我」之后又上屏「爱你」，就在 (我 → 爱你) 上累加计数；
 * - 预测时查「最近上屏词作为 prev」，取 count 最高的 next 作为联想候选。
 *
 * 记录来源分两类：
 * 1. 同一段上屏文本内部的相邻词（滑动窗口 bigram）；
 * 2. 跨上屏：上一次上屏的尾词 → 本次上屏的首词（由 [InputStatsManager] 维护的 lastEndWord 拼接）。
 */
@Entity(tableName = WordAssoc.TABLE_NAME)
data class WordAssoc(
    @PrimaryKey
    val key: String,
    /** 前一词（关联的起点）。 */
    val prev: String,
    /** 后一词（关联的终点）。 */
    val next: String,
    val count: Long = 1,
    /** 最近一次出现时间（毫秒），LRU/热度排序。 */
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TABLE_NAME = "word_assoc"

        /** 复合主键：prev + '\u0001' + next。 */
        fun key(prev: String, next: String): String = "$prev\u0001$next"
    }
}
