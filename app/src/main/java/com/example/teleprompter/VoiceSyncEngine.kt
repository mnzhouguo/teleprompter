package com.example.teleprompter

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType

class VoiceSyncEngine(
    val script: String,
    var windowSize: Int = 5,
    var searchForward: Int = 60,
    var searchBack: Int = 3
) {

    companion object {
        private const val LOW_CONFIDENCE = 0.35
        private const val FLUSH_AFTER = 3
    }

    // 原始文稿（含标点）
    val scriptChars: CharArray = script.toCharArray()

    // 无标点版本：用于匹配计算
    private val cleanChars: List<Char> = scriptChars.filter { !isPunctuation(it) }
    private val cleanPinyin: List<String> = cleanChars.map { toPinyin(it) }

    // 映射：无标点位置 → 原始位置
    private val indexMapping: List<Int> = buildIndexMapping()

    @Volatile
    var currentPosition: Int = 0
        private set

    private var cleanPosition: Int = 0

    // ASR 增量累积
    private val fullAccumulated = StringBuilder()
    private var lastFullClean = ""

    // 滑动窗口 buffer（最近 windowSize 个识别字符）
    val buffer: String get() = recentBuffer.toString()
    private val recentBuffer = StringBuilder()

    private var consecutiveNoMatch = 0

    // 最近两次匹配结果（供 UI 读取）
    @Volatile var lastScore: Double = 0.0
    @Volatile var lastBuffer: String = ""
    @Volatile var lastMatched: Boolean = false
    @Volatile var prevScore: Double = 0.0
    @Volatile var prevBuffer: String = ""
    @Volatile var prevMatched: Boolean = false

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
        val code = ch.code
        if (code in 0x3000..0x303F) return true
        if (code in 0xFF00..0xFFEF) return true
        return ch in setOf(',', '.', ';', ':', '?', '!', '"', '\'', '(', ')', '[', ']', '<', '>', '-', '_', '/', '\\')
    }

    @Synchronized
    fun onAsrIncrement(newText: String): Int {
        fullAccumulated.append(newText)
        val cleanFull = fullAccumulated.filter { !it.isWhitespace() }.toString()

        val delta: String
        if (cleanFull.startsWith(lastFullClean)) {
            delta = cleanFull.substring(lastFullClean.length)
        } else {
            lastFullClean = cleanFull
            recentBuffer.clear()
            consecutiveNoMatch = 0
            return currentPosition
        }
        lastFullClean = cleanFull

        if (delta.isEmpty()) return currentPosition

        for (ch in delta) {
            recentBuffer.append(ch)
        }
        while (recentBuffer.length > windowSize) {
            recentBuffer.deleteCharAt(0)
        }

        if (recentBuffer.length < 2) return currentPosition

        val patternPinyin = recentBuffer.map { toPinyin(it) }
        val patternLen = patternPinyin.size

        val searchStart = (cleanPosition - searchBack).coerceAtLeast(0)
        val searchEnd = (cleanPosition + searchForward).coerceAtMost(cleanPinyin.size)

        var bestScore = -1.0
        var bestCleanEndIdx = cleanPosition
        var bestForwardDist = 0

        for (start in searchStart until searchEnd) {
            val maxEnd = (start + patternLen).coerceAtMost(cleanPinyin.size)
            if (maxEnd - start < 2) break

            val result = similarity(patternPinyin, cleanPinyin.subList(start, maxEnd))
            val rawScore = result.first
            val weightedMatch = result.second
            if (weightedMatch < 2) continue  // 至少加权匹配2个字符

            val forwardDist = (start - cleanPosition).coerceAtLeast(0)

            // 距离惩罚：远处陡峭
            val penalty = when {
                forwardDist <= 2 -> 0.0
                forwardDist <= 8 -> (forwardDist - 2) / 8.0 * 0.12
                else -> 0.12 + (forwardDist - 8) / 12.0 * 0.48
            }
            val score = rawScore - penalty

            if (score > bestScore) {
                bestScore = score
                bestCleanEndIdx = start + weightedMatch.coerceAtLeast(1)
                bestForwardDist = forwardDist
            }
        }

        // 统一阈值
        val threshold = 0.30

        // 上一条 → prev，新结果 → last
        prevScore = lastScore
        prevBuffer = lastBuffer
        prevMatched = lastMatched

        lastScore = bestScore
        lastBuffer = recentBuffer.toString()

        if (bestScore >= threshold && bestCleanEndIdx > cleanPosition) {
            cleanPosition = bestCleanEndIdx
            currentPosition = if (bestCleanEndIdx < indexMapping.size) {
                indexMapping[bestCleanEndIdx]
            } else {
                scriptChars.size
            }
            consecutiveNoMatch = 0
            lastMatched = true
        } else {
            lastMatched = false
            if (bestScore < LOW_CONFIDENCE) {
                consecutiveNoMatch++
                if (consecutiveNoMatch >= FLUSH_AFTER) {
                    recentBuffer.clear()
                    consecutiveNoMatch = 0
                }
            } else {
                consecutiveNoMatch = 0
            }
        }
        return currentPosition
    }

    fun reset() {
        currentPosition = 0
        cleanPosition = 0
        fullAccumulated.clear()
        lastFullClean = ""
        recentBuffer.clear()
        consecutiveNoMatch = 0
    }

    @Synchronized
    fun setPosition(originalCharIndex: Int) {
        currentPosition = originalCharIndex.coerceIn(0, scriptChars.size)
        // 找到对应的 cleanPosition
        cleanPosition = 0
        for (i in indexMapping.indices) {
            if (indexMapping[i] <= currentPosition) {
                cleanPosition = i
            } else {
                break
            }
        }
        android.util.Log.d("VoiceSync", "setPosition: orig=$currentPosition clean=$cleanPosition")
    }

    // 返回 Pair<相似度分数, 加权匹配字符数>
    private fun similarity(a: List<String>, b: List<String>): Pair<Double, Int> {
        val len = minOf(a.size, b.size)
        if (len == 0) return Pair(0.0, 0)
        var match = 0.0
        var weightedMatch = 0.0
        for (i in 0 until len) {
            when {
                a[i] == b[i] -> { match += 1.0; weightedMatch += 1.0 }
                a[i].isNotEmpty() && b[i].isNotEmpty() && a[i][0].lowercaseChar() == b[i][0].lowercaseChar() -> { match += 0.2; weightedMatch += 0.2 }
            }
        }
        // 分母用实际比较数 len，不是 maxOf——修复稿末剩余字数少于 buffer 时 score 被压低的 bug
        val score = match / len
        return Pair(score, (weightedMatch + 0.5).toInt())
    }

    private fun toPinyin(ch: Char): String {
        if (ch.code < 128) return ch.lowercaseChar().toString()
        val format = HanyuPinyinOutputFormat().apply {
            toneType = HanyuPinyinToneType.WITHOUT_TONE
        }
        val raw = try {
            PinyinHelper.toHanyuPinyinStringArray(ch, format)?.firstOrNull()
        } catch (_: Exception) { null }
        return (raw ?: ch.toString()).lowercase()
    }
}
