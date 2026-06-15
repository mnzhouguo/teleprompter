package com.example.teleprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSyncEngineTranscriptTest {

    private fun engine() = VoiceSyncEngine(
        "前前后后做了四次的知识复合工具的迭代升级今天给大家聊一下"
    )

    @Test
    fun collapseTranscriptStaircaseLines_mergesPrefixLadder() {
        val raw = """
            毕竟现在自己去构建这个工具是完全可以
            毕竟现在自己去构建这个工具是完全可以实现的
            毕竟现在自己去构建这个工具是完全可以实现的首先说
        """.trimIndent()
        val out = collapseTranscriptStaircaseLines(raw)
        assertEquals("毕竟现在自己去构建这个工具是完全可以实现的首先说", out)
    }

    @Test
    fun collapseTranscriptStaircaseLines_keepsUnrelatedLines() {
        val raw = "第一句\n第二句完全不同的内容"
        assertEquals(raw, collapseTranscriptStaircaseLines(raw))
    }

    /** 模拟 full：每包为上一包的真前缀延长，转写应为单行当前假设 */
    @Test
    fun accumulatedTranscript_fullStyleHypothesisReplacesInterim() {
        val e = engine()
        e.onAsrIncrement("毕竟现在", false)
        e.onAsrIncrement("毕竟现在自己去构建", false)
        e.onAsrIncrement("毕竟现在自己去构建这个工具是完全可以实现的", false)
        assertEquals(
            "毕竟现在自己去构建这个工具是完全可以实现的",
            e.accumulatedAsrTranscript().replace("\n", "").trim()
        )
    }

    @Test
    fun accumulatedTranscript_finalThenNewInterim() {
        val e = engine()
        e.onAsrIncrement("第一段话说完了", true)
        e.onAsrIncrement("第二段开", false)
        e.onAsrIncrement("第二段开始讲", false)
        val t = e.accumulatedAsrTranscript()
        assertTrue(t.contains("第一段话说完了"))
        assertTrue(t.contains("第二段开始讲"))
    }

    /**
     * 模拟 single 下两包互不前缀时 interim 会带换行；输出端应压掉阶梯重复，
     * 且无关的两句仍保留。
     */
    @Test
    fun accumulatedTranscript_collapseRecoversFromStaircaseNoise() {
        val staircase = """
            毕竟现在自己去构建这个工具是完全可以
            毕竟现在自己去构建这个工具是完全可以实现的
            毕竟现在自己去构建这个工具是完全可以实现的首先说
        """.trimIndent()
        val collapsed = collapseTranscriptStaircaseLines(staircase)
        assertEquals("毕竟现在自己去构建这个工具是完全可以实现的首先说", collapsed)

        val mixed = "短句甲\n$staircase\n短句乙"
        val out = collapseTranscriptStaircaseLines(mixed)
        assertEquals("短句甲\n毕竟现在自己去构建这个工具是完全可以实现的首先说\n短句乙", out)
    }

    /** 对齐滑动窗口不含识别标点；转写仍保留 ASR 原文(含标点)。 */
    @Test
    fun alignmentStripsPunctuation_transcriptKeepsPunctuation() {
        val e = VoiceSyncEngine("你好世界这里是测试稿子用于滑动窗口")
        e.onAsrIncrement("你好，世", false)
        assertTrue(!e.lastBuffer.contains('，'))
        assertTrue(e.accumulatedAsrTranscript().contains('，'))
    }

    /** fullAccumulated：新包为旧串超集时应整段替换而非重复拼接 */
    @Test
    fun onAsrIncrement_alignmentBufferReplacesOnPrefixExtension() {
        val e = engine()
        e.onAsrIncrement("abc", false)
        e.onAsrIncrement("abcdef", false)
        // 通过再次喂入应基于 def 前缀算 delta，不崩溃且位置合法
        val pos = e.onAsrIncrement("abcdefghij", false)
        assert(pos in 0..e.scriptChars.size)
    }

    /** 验证修复：full模式下不会重复累积已有的内容 */
    @Test
    fun accumulatedTranscript_noDuplicationInFullMode() {
        val e = engine()
        
        // 模拟用户描述的重复累积场景
        e.onAsrIncrement("大家有没有。", false)
        e.onAsrIncrement("大家有没有今天？今天的话。", false)
        e.onAsrIncrement("大家有没有今天？今天的话听到一句话，大概看了一篇文章，然后。比较。", false)
        e.onAsrIncrement("大家有没有今天？今天的话听到一句话，大概看了一篇文章，然后。比较比较震惊啊？就是讲的。他说当当面粉充足的时候，那么我们最好的方式就是作为。", false)
        e.onAsrIncrement("大家有没有今天？今天的话听到一句话，大概看了一篇文章，然后。比较比较震惊啊？就是讲的。他说当当面粉充足的时候，那么我们最好的方式就是作为做面包的人", true)
        
        val result = e.accumulatedAsrTranscript()
        // 验证没有重复内容
        val occurrences = result.split("大家有没有").size - 1
        assertEquals("不应有重复的内容", 1, occurrences)
        
        // 验证内容是完整的
        assertTrue(result.contains("做面包的人"))
    }
}
