package com.example.teleprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSyncEngineDomainTest {

    private val demoScript = "前前后后做了四次的知识复合工具的迭代升级今天给大家聊一下"

    @Test
    fun onAsrIncrement_advancesPositionForNormalFullAsrUpdates() {
        val engine = VoiceSyncEngine(demoScript)

        assertEquals(0, engine.currentPosition)

        engine.onAsrIncrement("前前后后做了四次")

        assertTrue("position should advance", engine.currentPosition > 0)
        assertTrue("normal update should be marked matched", engine.lastMatched)
    }

    @Test
    fun onAsrIncrement_toleratesHomophones() {
        val engine = VoiceSyncEngine("我们在这里做一次测试")

        engine.onAsrIncrement("我们再这里")

        assertTrue("homophone should still match", engine.currentPosition > 0)
    }

    @Test
    fun onAsrIncrement_ignoresWhitespaceInScriptWhenMatching() {
        val engine = VoiceSyncEngine("你好\n世界这里是测试")

        engine.onAsrIncrement("你好世界这里")

        assertTrue("matching should cross line breaks", engine.currentPosition >= 4)
    }

    @Test
    fun onAsrIncrement_usesNewAsrSegmentWhenFullTextStopsSharingPrefix() {
        val engine = VoiceSyncEngine(demoScript)
        engine.onAsrIncrement("前前后后做了四次的")
        val firstPosition = engine.currentPosition

        engine.onAsrIncrement("知识复合工具")

        assertTrue(
            "new ASR segment should continue matching instead of being discarded",
            engine.currentPosition > firstPosition
        )
    }

    @Test
    fun onAsrIncrement_reanchorsWhenReaderSkipsFarAhead() {
        val skippedText = "中间这些内容会被用户直接跳过我们不应该卡在这里继续等待"
            .repeat(1)
        val targetText = "后面重点内容现在开始讲解"
        val script = "开头内容先正常朗读" + skippedText + targetText
        val engine = VoiceSyncEngine(script)

        engine.onAsrIncrement("开头内容先正常")
        val firstPosition = engine.currentPosition
        assertTrue(firstPosition > 0)

        engine.onAsrIncrement("后面重点内容现在开始")
        assertTrue("first far packet should not jump immediately", engine.currentPosition < script.indexOf(targetText))
        engine.onAsrIncrement("后面重点内容现在开始讲解")

        assertTrue(
            "jump reading should re-anchor near the later spoken text",
            engine.currentPosition >= script.indexOf(targetText)
        )
    }

    @Test
    fun onAsrIncrement_reanchorsOnShortJumpPhraseWhenLocalMatchFails() {
        val skippedText = "这里还有一段铺垫内容".repeat(1)
        val targetText = "重点来了呀"
        val script = "开头内容先正常朗读" + skippedText + targetText + "继续说明"
        val engine = VoiceSyncEngine(script)

        engine.onAsrIncrement("开头内容先正常")
        val firstPosition = engine.currentPosition
        assertTrue(firstPosition > 0)

        engine.onAsrIncrement(targetText)
        engine.onAsrIncrement(targetText + "继续")

        assertTrue(
            "short jump phrase within 50 chars should re-anchor when it is a strong match",
            engine.currentPosition >= script.indexOf(targetText)
        )
    }

    @Test
    fun onAsrIncrement_keepsNearbyMatchInsteadOfJumpingToSimilarLaterWords() {
        val weakLocalText = "今日我们看看普通内容"
        val skippedText = "中间这些内容会被用户直接跳过".repeat(3)
        val targetText = "今天重点内容现在开始讲解"
        val script = "今天我们看看开头内容" + weakLocalText + skippedText + targetText
        val engine = VoiceSyncEngine(script)

        engine.onAsrIncrement("今天我们看看")
        val firstPosition = engine.currentPosition
        assertTrue(firstPosition > 0)

        engine.onAsrIncrement("今天重点内容现在开始")

        assertTrue(
            "similar later words should not steal the position while nearby matching is still possible",
            engine.currentPosition < script.indexOf(targetText)
        )
    }

    @Test
    fun onAsrIncrement_rejectsWeakVeryFarJumpMatch() {
        val filler = "fillercontent".repeat(30)
        val farSimilarText = "abcdefgxy later text"
        val script = "start reading normally " + filler + farSimilarText
        val engine = VoiceSyncEngine(script)

        engine.onAsrIncrement("start reading")
        assertTrue(engine.currentPosition > 0)

        engine.onAsrIncrement("abcdefgzz")
        engine.onAsrIncrement("abcdefgzzh")

        assertTrue(
            "weak far matches should not re-anchor hundreds of chars away",
            engine.currentPosition < script.indexOf(farSimilarText)
        )
        assertTrue("far jump search should have been rejected", engine.lastDiagnostics.jumpAttempted)
        assertFalse(engine.lastDiagnostics.jumpAccepted)
    }

    @Test
    fun onAsrIncrement_rejectsExactJumpBeyondFiftyChars() {
        val filler = "fillercontent".repeat(6)
        val farText = "exact target phrase"
        val script = "start reading normally " + filler + farText
        val engine = VoiceSyncEngine(script)

        engine.onAsrIncrement("start reading")
        assertTrue(engine.currentPosition > 0)

        engine.onAsrIncrement(farText)
        engine.onAsrIncrement("$farText now")

        assertTrue(
            "automatic voice sync should never re-anchor beyond 50 chars",
            engine.currentPosition < script.indexOf(farText)
        )
        assertTrue(engine.lastDiagnostics.jumpAttempted)
        assertFalse(engine.lastDiagnostics.jumpAccepted)
    }

    @Test
    fun setPosition_clearsOldAsrStateBeforeContinuingFromManualPosition() {
        val engine = VoiceSyncEngine(demoScript)
        engine.onAsrIncrement("前前后后做了四次的")

        val manualPosition = demoScript.indexOf("迭代")
        engine.setPosition(manualPosition)

        assertEquals("manual position should be applied", manualPosition, engine.currentPosition)
        assertTrue("old ASR buffer should be cleared", engine.buffer.isEmpty())

        engine.onAsrIncrement("迭代升级今天")

        assertTrue(
            "ASR after manual positioning should match from the new position",
            engine.currentPosition > manualPosition
        )
    }

    @Test
    fun reset_clearsMatchingState() {
        val engine = VoiceSyncEngine(demoScript)
        engine.onAsrIncrement("前前后后做了四次")
        assertTrue(engine.currentPosition > 0)
        assertFalse(engine.buffer.isEmpty())

        engine.reset()

        assertEquals(0, engine.currentPosition)
        assertTrue(engine.buffer.isEmpty())
        assertFalse(engine.lastMatched)
    }
}
