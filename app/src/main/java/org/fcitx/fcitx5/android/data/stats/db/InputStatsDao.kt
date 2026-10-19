/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.stats.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 输入统计数据访问层。
 * 统计维度：日/周/月/全部；字数（中文/英文单词/数字/标点/总字符）；最常用词汇。
 */
@Dao
interface InputStatsDao {

    /* ===== 每日聚合 ===== */

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDaily(stats: InputDailyStats)

    /** 累加更新某日各项计数（不存在则插入）。 */
    @Query("""
        UPDATE ${InputDailyStats.TABLE_NAME}
        SET chineseChars = chineseChars + :chinese,
            englishWords = englishWords + :english,
            digits = digits + :digits,
            punctuation = punctuation + :punct,
            totalChars = totalChars + :total,
            commitCount = commitCount + 1,
            updatedAt = :updatedAt
        WHERE date = :date
    """)
    suspend fun addDaily(date: String, chinese: Long, english: Long, digits: Long, punct: Long, total: Long, updatedAt: Long): Int

    @Query("SELECT * FROM ${InputDailyStats.TABLE_NAME} WHERE date = :date LIMIT 1")
    suspend fun getDaily(date: String): InputDailyStats?

    @Query("SELECT * FROM ${InputDailyStats.TABLE_NAME} WHERE date >= :from AND date <= :to")
    suspend fun getDailyRange(from: String, to: String): List<InputDailyStats>

    @Query("SELECT * FROM ${InputDailyStats.TABLE_NAME} WHERE date >= :from")
    suspend fun getDailyFrom(from: String): List<InputDailyStats>

    @Query("SELECT * FROM ${InputDailyStats.TABLE_NAME}")
    suspend fun getAllDaily(): List<InputDailyStats>

    /* ===== 高频词汇 ===== */

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWord(word: FreqWord)

    @Query("""
        UPDATE ${FreqWord.TABLE_NAME}
        SET count = count + 1, updatedAt = :updatedAt
        WHERE word = :word
    """)
    suspend fun bumpWord(word: String, updatedAt: Long): Int

    /** 读取高频词 TOP N（按次数倒序）。 */
    @Query("SELECT * FROM ${FreqWord.TABLE_NAME} ORDER BY count DESC LIMIT :limit")
    suspend fun topWords(limit: Int): List<FreqWord>

    @Query("SELECT COUNT(*) FROM ${FreqWord.TABLE_NAME}")
    suspend fun wordCount(): Long

    /* ===== 会话（预留） ===== */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: InputSession)

    @Query("SELECT COUNT(*) FROM ${InputSession.TABLE_NAME}")
    suspend fun sessionCount(): Long

    @Query("SELECT COALESCE(SUM(charCount), 0) FROM ${InputSession.TABLE_NAME}")
    suspend fun totalSessionChars(): Long

    /* ===== 清空 ===== */

    @Query("DELETE FROM ${InputDailyStats.TABLE_NAME}")
    suspend fun deleteAllDaily()

    @Query("DELETE FROM ${FreqWord.TABLE_NAME}")
    suspend fun deleteAllWords()

    @Query("DELETE FROM ${InputSession.TABLE_NAME}")
    suspend fun deleteAllSessions()

    /* ===== 连续性词汇关联（联想预测根本数据） ===== */

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssoc(assoc: WordAssoc)

    @Query("""
        UPDATE ${WordAssoc.TABLE_NAME}
        SET count = count + 1, updatedAt = :updatedAt
        WHERE key = :key
    """)
    suspend fun bumpAssoc(key: String, updatedAt: Long): Int

    /** 按前一词查联想候选（count 倒序），功能 2 预测主查询。 */
    @Query("""
        SELECT * FROM ${WordAssoc.TABLE_NAME}
        WHERE prev = :prev
        ORDER BY count DESC, updatedAt DESC
        LIMIT :limit
    """)
    suspend fun assocFor(prev: String, limit: Int): List<WordAssoc>

    @Query("SELECT * FROM ${WordAssoc.TABLE_NAME} ORDER BY count DESC, updatedAt DESC LIMIT :limit")
    suspend fun allAssoc(limit: Int): List<WordAssoc>

    @Query("SELECT COUNT(*) FROM ${WordAssoc.TABLE_NAME}")
    suspend fun assocCount(): Long

    @Query("DELETE FROM ${WordAssoc.TABLE_NAME}")
    suspend fun deleteAllAssoc()

    /* ===== 自学习错拼/习惯码映射（功能 3 纠错） ===== */

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPinyinCorrect(pc: PinyinCorrect)

    @Query("""
        UPDATE ${PinyinCorrect.TABLE_NAME}
        SET count = count + 1, updatedAt = :updatedAt
        WHERE key = :key
    """)
    suspend fun bumpPinyinCorrect(key: String, updatedAt: Long): Int

    /** 按输入码查自学习纠错候选（count 倒序，跨类型合并靠 type 列区分）。 */
    @Query("""
        SELECT * FROM ${PinyinCorrect.TABLE_NAME}
        WHERE wrong = :wrong
        ORDER BY count DESC, updatedAt DESC
        LIMIT :limit
    """)
    suspend fun pinyinCorrectFor(wrong: String, limit: Int): List<PinyinCorrect>

    @Query("SELECT COUNT(*) FROM ${PinyinCorrect.TABLE_NAME}")
    suspend fun pinyinCorrectCount(): Long

    /** 读取全部自学习习惯码映射（供导出联想词表；count 倒序）。 */
    @Query("""
        SELECT * FROM ${PinyinCorrect.TABLE_NAME}
        WHERE type = ${PinyinCorrect.TYPE_HABIT}
        ORDER BY count DESC, updatedAt DESC
        LIMIT :limit
    """)
    suspend fun habitMappings(limit: Int): List<PinyinCorrect>

    @Query("DELETE FROM ${PinyinCorrect.TABLE_NAME}")
    suspend fun deleteAllPinyinCorrect()
}
