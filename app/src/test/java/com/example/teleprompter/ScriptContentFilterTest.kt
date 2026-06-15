package com.example.teleprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
