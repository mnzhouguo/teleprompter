package com.example.teleprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptContentFilterTest {

    @Test
    fun dropsDiscussionFocusAndMaterialCountLines() {
        val raw = """
            讨论侧重：核心导图
            素材个数：12
            正文第一句。
            正文第二句。
        """.trimIndent()
        val out = ScriptContentFilter.forDisplay(raw)
        assertFalse(out.contains("讨论侧重"))
        assertFalse(out.contains("素材个数"))
        assertEquals("正文第一句。\n正文第二句。", out)
    }

    @Test
    fun forDisplay_dropsBlankLinesAndMarkdownHeadings() {
        val raw = """
            # 开场

            大家好，今天聊聊提词器。

            ## 要点

            第一点不要空行。
        """.trimIndent()
        val out = ScriptContentFilter.forDisplay(raw)
        assertEquals("大家好，今天聊聊提词器。\n第一点不要空行。", out)
        assertFalse(out.contains("开场"))
        assertFalse(out.contains("要点"))
        assertFalse(out.contains("\n\n"))
    }

    @Test
    fun stripMarkdown_dropsHeadingsAndBlankLines_keepsSpokenText() {
        val raw = """
            # 开场

            **大家** 好，今天聊聊提词器。

            ## 要点

            - 第一点
            - 第二点

            1. 有序一
            > 引用行
        """.trimIndent()
        val out = ScriptContentFilter.stripMarkdown(raw)
        assertEquals(
            """
            大家 好，今天聊聊提词器。
            第一点
            第二点
            有序一
            引用行
            """.trimIndent(),
            out
        )
        assertFalse(out.contains("开场"))
        assertFalse(out.contains("要点"))
        assertFalse(out.contains("#"))
    }

    @Test
    fun stripMarkdown_collapsesParagraphBreaksToSingleNewline() {
        val raw = """
            第一段。

            第二段。
        """.trimIndent()
        assertEquals("第一段。\n第二段。", ScriptContentFilter.stripMarkdown(raw))
        assertEquals("", ScriptContentFilter.stripMarkdown(""))
    }

    @Test
    fun shouldDropLine_recognizesHeadingsAndBlanks() {
        assertTrue(ScriptContentFilter.shouldDropLine(""))
        assertTrue(ScriptContentFilter.shouldDropLine("   "))
        assertTrue(ScriptContentFilter.shouldDropLine("# 标题"))
        assertTrue(ScriptContentFilter.shouldDropLine("## 二级"))
        assertTrue(ScriptContentFilter.shouldDropLine("  ###  带空格"))
        assertTrue(ScriptContentFilter.shouldDropLine("####"))
        assertFalse(ScriptContentFilter.shouldDropLine("##二级")) // 无空格不算 ATX 标题
        assertFalse(ScriptContentFilter.shouldDropLine("正文一行"))
        assertFalse(ScriptContentFilter.shouldDropLine("不是#标题"))
    }
}
