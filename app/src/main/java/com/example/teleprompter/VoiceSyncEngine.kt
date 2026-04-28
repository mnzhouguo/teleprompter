package com.example.teleprompter

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType

class VoiceSyncEngine(val script: String) {

    companion object {
        private const val WINDOW_SIZE = 6
        private const val SEARCH_BACK = 3
        private const val SEARCH_FORWARD = 70
        private const val CONFIDENCE_THRESHOLD = 0.45
    }

    // 原始文稿（含标点）
    val scriptChars: CharArray = script.toCharArray()

    // 无标点版本：用于匹配计算
    private val cleanChars: List<Char> = scriptChars.filter { !isPunctuation(it) }
    private val cleanPinyin: List<String> = cleanChars.map { toPinyin(it) }

    // 映射：无标点位置 → 原始位置
    // 例如：文稿 "你好，世界" → cleanIndex=2 对应 originalIndex=3（跳过逗号）
    private val indexMapping: List<Int> = buildIndexMapping()

    @Volatile
    var currentPosition: Int = 0  // 原始文稿位置（含标点）
        private set

    // 无标点版本的位置，用于匹配
    private var cleanPosition: Int = 0

    private val recentBuffer = StringBuilder()
    private var lastFullText = ""

    private fun buildIndexMapping(): List<Int> {
        val mapping = mutableListOf<Int>()
        for (i in scriptChars.indices) {
            if (!isPunctuation(scriptChars[i])) {
                mapping.add(i)
            }
        }
        return mapping
    }

    private fun isPunctuation(ch: Char): Boolean {
        // 中文标点符号范围: U+3000-U+303F (CJK符号和标点)
        // 全角标点范围: U+FF00-U+FFEF
        val code = ch.code
        if (code in 0x3000..0x303F) return true
        if (code in 0xFF00..0xFFEF) return true
        // 英文常见标点
        return ch in setOf(',', '.', ';', ':', '?', '!', '"', '\'', '(', ')', '[', ']', '<', '>', '-', '_', '/', '\\')
    }

    @Synchronized
    fun onAsrIncrement(fullText: String): Int {
        val cleanNew = fullText.filter { !it.isWhitespace() }

        val delta = if (cleanNew.startsWith(lastFullText)) {
            cleanNew.substring(lastFullText.length)
        } else {
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

        // 在无标点版本中搜索
        val searchStart = (cleanPosition - SEARCH_BACK).coerceAtLeast(0)
        val searchEnd = (cleanPosition + SEARCH_FORWARD).coerceAtMost(cleanPinyin.size)

        var bestScore = 0.0
        var bestCleanEndIdx = cleanPosition

        for (start in searchStart until searchEnd) {
            val end = (start + patternPinyin.size).coerceAtMost(cleanPinyin.size)
            if (end - start < 2) break
            val rawScore = similarity(patternPinyin, cleanPinyin.subList(start, end))
            val forwardDist = (start - cleanPosition).coerceAtLeast(0)
            val score = rawScore - forwardDist.toFloat() / SEARCH_FORWARD * 0.3f
            if (score > bestScore) {
                bestScore = score
                bestCleanEndIdx = end
            }
        }

        if (bestScore >= CONFIDENCE_THRESHOLD && bestCleanEndIdx > cleanPosition) {
            cleanPosition = bestCleanEndIdx
            // 映射回原始位置（取匹配结束位置对应的原始字符）
            currentPosition = if (bestCleanEndIdx < indexMapping.size) {
                indexMapping[bestCleanEndIdx]
            } else {
                scriptChars.size  // 已到末尾
            }
            android.util.Log.d("VoiceSyncEngine", "匹配：cleanPos=$cleanPosition origPos=$currentPosition  buffer=\"$recentBuffer\"  score=${"%.2f".format(bestScore)}")
        }
        return currentPosition
    }

    fun reset() {
        currentPosition = 0
        cleanPosition = 0
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
        if (ch.code < 128) return ch.toString().lowercase()
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
