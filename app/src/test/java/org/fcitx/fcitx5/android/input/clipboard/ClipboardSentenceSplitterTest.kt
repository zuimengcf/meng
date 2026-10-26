/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSentenceSplitterTest {

    private val splitter = ClipboardSentenceSplitter

    // --- 空文本 & 单段 ---

    @Test
    fun emptyReturnsEmpty() {
        assertTrue(splitter.split("").isEmpty())
    }

    @Test
    fun whitespaceOnlyReturnsEmpty() {
        assertTrue(splitter.split("   ").isEmpty())
    }

    @Test
    fun noPunctIsSingleSegment() {
        assertEquals(listOf("今天天气不错"), splitter.split("今天天气不错"))
    }

    // --- 中文标点切分（默认剥标点） ---

    @Test
    fun chineseCommaCuts() {
        assertEquals(
            listOf("今天天气很好", "我心情不错"),
            splitter.split("今天天气很好，我心情不错。")
        )
    }

    @Test
    fun chinesePeriodCuts() {
        assertEquals(
            listOf("第一段", "第二段", "第三段"),
            splitter.split("第一段。第二段。第三段。")
        )
    }

    @Test
    fun userExampleTwoSegments() {
        assertEquals(
            listOf("我身寄为天地客", "料君亦自沉浮人海中"),
            splitter.split("我身寄为天地客，料君亦自沉浮人海中。")
        )
    }

    @Test
    fun keepPunctuationWhenDisabled() {
        assertEquals(
            listOf("今天天气很好，", "我心情不错。"),
            splitter.split("今天天气很好，我心情不错。", stripPunctuation = false)
        )
    }

    // --- 引号/包裹符：切分边界（用户失败案例） ---

    @Test
    fun backtickCodeIsBoundary() {
        // 用户实际失败案例：反引号是切分边界
        assertEquals(
            listOf("排序完成后一次显示列表只改", "PlayerUi.kt"),
            splitter.split("排序完成后一次显示列表只改 `PlayerUi.kt`")
        )
    }

    @Test
    fun cornerBracketsAreBoundaries() {
        // 「第一段。」「第二段。」→ 内部句号不切（包裹保护），两个包裹块为边界
        assertEquals(
            listOf("第一段", "第二段"),
            splitter.split("「第一段。」「第二段。」")
        )
    }

    @Test
    fun doubleQuotesAreBoundaries() {
        assertEquals(
            listOf("他说", "你好。然后走了", "他离开了"),
            splitter.split("他说：\"你好。然后走了。\"他离开了。")
        )
    }

    @Test
    fun curlyDoubleQuoteInsideProtected() {
        // “你好。”内部句号不切，包裹整体为一段
        assertEquals(
            listOf("他说", "你好", "然后走了"),
            splitter.split("他说：“你好。”然后走了。")
        )
    }

    @Test
    fun parenthesesAreBoundaries() {
        assertEquals(
            listOf("括号内容", "后面的句子"),
            splitter.split("（括号内容。）后面的句子。")
        )
    }

    @Test
    fun mixedBracketsAllCut() {
        assertEquals(
            listOf("书名", "和", "括号", "都切"),
            splitter.split("《书名》和（括号）都切。")
        )
    }

    // --- 平衡预检：未闭合引号不保护，内部句读照常生效 ---

    @Test
    fun unclosedCornerBracketDegradesGracefully() {
        // 「 未闭合 → 无保护，内部句号照常切
        assertEquals(
            listOf("未闭合的引号", "后面还有"),
            splitter.split("「未闭合的引号。后面还有。")
        )
    }

    @Test
    fun balancedBackticksAreBoundaries() {
        // 4 个反引号（偶数平衡）→ 保护生效，反引号是切分边界
        assertEquals(
            listOf("代码", "x", "和", "y", "完成"),
            splitter.split("代码 `x` 和 `y` 完成")
        )
    }

    @Test
    fun oddCountStraightQuoteDegradesGracefully() {
        // 3 个反引号（奇数不平衡）→ 不作为切分边界，当普通文本；
        // 全串无句读标点 → 单段，反引号原样保留
        assertEquals(
            listOf("代码 `x` 和 `y 完成了啊"),
            splitter.split("代码 `x` 和 `y 完成了啊")
        )
    }

    // --- 省略号 ---

    @Test
    fun ellipsisCuts() {
        assertEquals(
            listOf("第一段", "第二段"),
            splitter.split("第一段……第二段。")
        )
    }

    @Test
    fun singleEllipsisCharCuts() {
        assertEquals(
            listOf("他说", "然后走了"),
            splitter.split("他说…然后走了。")
        )
    }

    // --- 单引号保护（don't / it’s） ---

    @Test
    fun apostropheDoesNotPair() {
        // 单引号不配对也不切；半角句点本来就不在切点集 → 单段
        assertEquals(
            listOf("it’s fine. don't stop"),
            splitter.split("it’s fine. don't stop")
        )
    }

    @Test
    fun apostropheWithChinesePunctCuts() {
        // 单引号不干扰，中文标点照常切
        assertEquals(
            listOf("it’s fine", "别停下"),
            splitter.split("it’s fine，别停下。")
        )
    }

    // --- 半角逗号/分号（条件切分） ---

    @Test
    fun asciiCommaCutsWhenNonAlnumNeighbor() {
        assertEquals(
            listOf("hello", "world", "how are you"),
            splitter.split("hello, world! how are you?")
        )
    }

    @Test
    fun asciiCommaPreservesThousands() {
        assertEquals(
            listOf("电话 1,234,567 和 12,34"),
            splitter.split("电话 1,234,567 和 12,34")
        )
    }

    @Test
    fun asciiSemicolonCutsBetweenWords() {
        assertEquals(
            listOf("a", "b", "c"),
            splitter.split("a; b; c.")
        )
    }

    // --- URL 保护 ---

    @Test
    fun bareHttpsUrlIsSingleSegment() {
        assertEquals(
            listOf("https://github.com/zuimengcf/meng.git"),
            splitter.split("https://github.com/zuimengcf/meng.git")
        )
    }

    @Test
    fun urlFollowedByTextCutsOnComma() {
        assertEquals(
            listOf("访问 https://a.com 和 https://b.com", "然后登录"),
            splitter.split("访问 https://a.com 和 https://b.com，然后登录")
        )
    }

    @Test
    fun urlThenChinesePeriodCuts() {
        assertEquals(
            listOf("https://a.com/?a=1&b=2", "第二段"),
            splitter.split("https://a.com/?a=1&b=2。第二段。")
        )
    }

    @Test
    fun urlInsideQuotesProtected() {
        // URL 在引号内：URL 整体保留；开符在 i=0 无切点，闭符后切但尾段为空 → 单段
        assertEquals(
            listOf("see https://a.com/x?q=1"),
            splitter.split("\"see https://a.com/x?q=1\"")
        )
    }

    // --- 混合中英文与标点 ---

    @Test
    fun mixedCnEnCutsOnBothFullAndHalfWidth() {
        assertEquals(
            listOf("混合中英", "Hello world", "第三行"),
            splitter.split("混合中英。Hello world! 第三行。")
        )
    }

    @Test
    fun emailAndPhonePreserveDotButCutsComma() {
        assertEquals(
            listOf("邮箱：user@example.com", "电话：13800138000"),
            splitter.split("邮箱：user@example.com，电话：13800138000")
        )
    }

    // --- 冒号也剥 ---

    @Test
    fun colonStrippedAtEnd() {
        assertEquals(
            listOf("他说", "你好", "他离开了"),
            splitter.split("他说：“你好。”他离开了。")
        )
    }

    // --- 数字保护 ---

    @Test
    fun decimalNumberKept() {
        assertEquals(
            listOf("3.14159 是圆周率"),
            splitter.split("3.14159 是圆周率")
        )
    }

    @Test
    fun ipWithCommaSplitsButIpPreserved() {
        assertEquals(
            listOf("IP: 192.168.1.1", "子网 255.255.255.0"),
            splitter.split("IP: 192.168.1.1, 子网 255.255.255.0")
        )
    }

    // --- 无末尾标点 ---

    @Test
    fun noEndingPunctuationIsStillSingle() {
        assertEquals(listOf("无标点结尾"), splitter.split("无标点结尾"))
    }

    // --- 换行切分 ---

    @Test
    fun newlineCuts() {
        assertEquals(
            listOf("hello world", "second line"),
            splitter.split("hello world!\nsecond line.")
        )
    }
}