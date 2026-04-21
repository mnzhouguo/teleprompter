package com.example.teleprompter

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType

class VoiceSyncEngine(val script: String) {

    companion object {
        private const val WINDOW_SIZE = 6
        private const val SEARCH_BACK = 3
        private const val SEARCH_FORWARD = 50
        private const val CONFIDENCE_THRESHOLD = 0.45
    }

    private val scriptPinyin: List<String> = script.map { toPinyin(it) }
    val scriptChars: CharArray = script.toCharArray()

    @Volatile
    var currentPosition: Int = 0
        private set

    private val recentBuffer = StringBuilder()

    // 豆包返回的是累积全文，需要记住上次的文本，只处理新增部分
    private var lastFullText = ""

    @Synchronized
    fun onAsrIncrement(fullText: String): Int {
        val cleanNew = fullText.filter { !it.isWhitespace() }

        // 计算真正新增的字符（delta）
        val delta = if (cleanNew.startsWith(lastFullText)) {
            cleanNew.substring(lastFullText.length)
        } else {
            // 识别结果发生了非增量变化（如整句重置），用全文末尾作为 delta
            cleanNew.takeLast(WINDOW_SIZE)
        }
        lastFullText = cleanNew

        if (delta.isEmpty()) return currentPosition

        for (ch in delta) {
            recentBuffer.append(ch)
        }
        while (recentBuffer.length > WINDOW_SIZE) {
            recentBuffer.deleteCharAt(0)
        }

        if (recentBuffer.length < 2) return currentPosition

        val patternPinyin = recentBuffer.map { toPinyin(it) }

        val searchStart = (currentPosition - SEARCH_BACK).coerceAtLeast(0)
        val searchEnd = (currentPosition + SEARCH_FORWARD).coerceAtMost(scriptChars.size)

        var bestScore = 0.0
        var bestEndIdx = currentPosition

        for (start in searchStart until searchEnd) {
            val end = (start + patternPinyin.size).coerceAtMost(scriptChars.size)
            if (end - start < 2) break
            val score = similarity(patternPinyin, scriptPinyin.subList(start, end))
            if (score > bestScore) {
                bestScore = score
                bestEndIdx = end
            }
        }

        if (bestScore >= CONFIDENCE_THRESHOLD && bestEndIdx > currentPosition) {
            currentPosition = bestEndIdx
            android.util.Log.d("VoiceSyncEngine", "匹配：pos=$currentPosition  buffer=\"$recentBuffer\"  score=${"%.2f".format(bestScore)}")
        }
        return currentPosition
    }

    fun reset() {
        currentPosition = 0
        lastFullText = ""
        recentBuffer.clear()
    }

    private fun similarity(a: List<String>, b: List<String>): Double {
        val len = minOf(a.size, b.size)
        if (len == 0) return 0.0
        var match = 0.0
        for (i in 0 until len) {
            when {
                a[i] == b[i] -> match += 1.0
                a[i].isNotEmpty() && b[i].isNotEmpty() && a[i][0] == b[i][0] -> match += 0.3
            }
        }
        return match / maxOf(a.size, b.size)
    }

    private fun toPinyin(ch: Char): String {
        if (ch.code < 128) return ch.toString()
        val format = HanyuPinyinOutputFormat().apply {
            toneType = HanyuPinyinToneType.WITHOUT_TONE
        }
        return try {
            PinyinHelper.toHanyuPinyinStringArray(ch, format)?.firstOrNull() ?: ch.toString()
        } catch (_: Exception) {
            ch.toString()
        }
    }
}
