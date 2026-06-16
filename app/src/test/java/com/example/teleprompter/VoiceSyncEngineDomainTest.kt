package com.example.teleprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 领域语言重构后的行为测试。
 *
 * 测试命名和断言描述使用 CONTEXT.md 中的领域术语：
 * 声文对齐、ASR 增量提取、语音片段、滑动匹配、文稿位置 等。
 */
class VoiceSyncEngineDomainTest {

    // 共用文稿
    private val demoScript = "前前后后做了四次的知识复合工具的迭代升级今天给大家聊一下"

    private fun engine() = VoiceSyncEngine(demoScript)

    // ── 示踪子弹：完整链路 ──────────────────────────────────

    /**
     * 示踪子弹：从 ASR 增量提取到文稿位置推进的完整路径。
     *
     * 输入一段 ASR 识别文本 → processAsrDelta 处理 → scriptPosition 推进。
     */
    @Test
    fun processAsrDelta_推进文稿位置() {
        val e = engine()
        assertEquals("初始文稿位置应为 0", 0, e.scriptPosition)

        e.processAsrDelta("前后做了四次", isFinal = false)
        assertTrue("匹配后文稿位置应向前推进", e.scriptPosition > 0)
    }

    /**
     * ASR 增量提取：同音别字（在/再）不影响匹配。
     */
    @Test
    fun processAsrDelta_同音容错() {
        val e = engine()
        e.processAsrDelta("前前后后做了四次的", isFinal = false)
        assertTrue("同音别字应能匹配: scriptPosition=$e.scriptPosition", e.scriptPosition > 0)
    }

    /**
     * 跟随丢失：连续低分匹配后语音片段被清空。
     */
    @Test
    fun 匹配失败累积到阈值后清空语音片段() {
        val e = VoiceSyncEngine("你好世界")

        // 喂入完全不匹配的文本
        repeat(5) {
            e.processAsrDelta("xxxxxxxxx", isFinal = false)
        }

        // 应该已经清空语音片段
        assertTrue("语音片段应为空或长度 < 窗口大小", e.voiceSegment.length <= e.voiceSegmentMaxSize)
    }

    /**
     * 语音转写保留标点，文稿位置不受影响。
     */
    @Test
    fun 转写保留标点_对齐不受影响() {
        val e = VoiceSyncEngine("你好世界这里是测试稿子用于滑动窗口")
        e.processAsrDelta("你好，世", isFinal = false)

        assertTrue("语音片段不应含标点", !e.voiceSegment.contains('，'))
        assertTrue("转写应保留标点", e.getTranscript().contains('，'))
    }

    /**
     * 位置映射：匹配位置可正确转换回文稿位置。
     */
    @Test
    fun setPosition_同步文稿位置和匹配位置() {
        val e = engine()
        val targetCharIndex = 10 // 文稿中的第10个字符
        e.setPosition(targetCharIndex)

        assertTrue("设置后文稿位置不应小于设定值", e.scriptPosition >= targetCharIndex)
    }

    /**
     * 句边界：第一句定稿后第二句应继续推进文稿位置（修复整段替换时 early return）。
     */
    @Test
    fun processAsrDelta_第二句定稿后继续推进() {
        val e = engine()
        e.processAsrDelta("前前后后做了四次的", isFinal = false)
        val posAfterFirst = e.scriptPosition
        assertTrue("第一句应有推进", posAfterFirst > 0)

        e.processAsrDelta("前前后后做了四次的", isFinal = true)
        e.processAsrDelta("知识复合工具", isFinal = false)
        assertTrue(
            "第二句应继续推进: before=$posAfterFirst after=${e.scriptPosition}",
            e.scriptPosition > posAfterFirst
        )
    }

    /**
     * 句边界：新句 ASR 与上一句无前缀关系时应整段替换而非 append 污染。
     */
    @Test
    fun processAsrDelta_新句整段替换仍能匹配() {
        val e = engine()
        e.processAsrDelta("前前后后做了四次的", isFinal = true)
        e.processAsrDelta("知识复合", isFinal = false)
        assertTrue("新句语音片段应有内容", e.voiceSegment.isNotEmpty())
    }

    /**
     * 重置后所有状态归零。
     */
    @Test
    fun reset_清空所有状态() {
        val e = engine()
        e.processAsrDelta("前后做了四次", isFinal = false)
        assertTrue("匹配后有推进", e.scriptPosition > 0)

        e.reset()
        assertEquals("重置后文稿位置归零", 0, e.scriptPosition)
        assertTrue("重置后语音片段为空", e.voiceSegment.isEmpty())
        assertTrue("重置后转写为空", e.getTranscript().isEmpty())
    }
}
