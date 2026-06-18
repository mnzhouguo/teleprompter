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
        private const val MAX_MATCH_ROUNDS = 8
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

    // 上一帧 ASR 的 cleanFull，用于计算增量
    private var lastFullClean = ""

    // 滑动窗口 buffer（未消费的识别字符）
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

    /** 与文稿 [isPunctuation] 一致：对齐/滚动仅看「字」，不看识别里的标点。 */
    private fun stripPunctuationForAlignment(s: String): String = s.filter { !isPunctuation(it) }

    @Synchronized
    fun onAsrIncrement(newText: String): Int {
        // 对齐链路只用去标点、去空白的 ASR
        val cleanFull = stripPunctuationForAlignment(newText)
            .filter { !it.isWhitespace() }

        // 提取增量
        val delta: String
        if (cleanFull.startsWith(lastFullClean)) {
            delta = cleanFull.substring(lastFullClean.length)
        } else {
            // ASR 文本发生大幅变化（修正或重新识别），重置匹配状态
            lastFullClean = cleanFull
            recentBuffer.clear()
            consecutiveNoMatch = 0
            return currentPosition
        }
        lastFullClean = cleanFull

        if (delta.isEmpty()) return currentPosition

        // 将增量加入滑动窗口
        recentBuffer.append(delta)

        // 多轮匹配：当 ASR 突发大量文本时，一轮可能只能推进 windowSize 个位置，
        // 循环多次让匹配追上新到的文本，避免累积滞后
        var round = 0
        while (round < MAX_MATCH_ROUNDS && recentBuffer.length >= windowSize) {
            round++

            // 使用缓冲区尾部 windowSize 个字符作为匹配模式
            val patStr = recentBuffer.takeLast(windowSize).toString()
            val patternPinyin = patStr.map { toPinyin(it) }
            val patternLen = patternPinyin.size

            val searchStart = (cleanPosition - searchBack).coerceAtLeast(0)
            val searchEnd = (cleanPosition + searchForward).coerceAtMost(cleanPinyin.size)

            var bestScore = -1.0
            var bestCleanEndIdx = cleanPosition

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
                }
            }

            val threshold = 0.30

            if (bestScore >= threshold && bestCleanEndIdx > cleanPosition) {
                cleanPosition = bestCleanEndIdx
                currentPosition = if (bestCleanEndIdx < indexMapping.size) {
                    indexMapping[bestCleanEndIdx]
                } else {
                    scriptChars.size
                }
                consecutiveNoMatch = 0
                lastMatched = true
                lastScore = bestScore

                // 本轮已消费 pattern 对应的文本，清理 buffer 中已被越过的旧字符
                // 保留最后 windowSize 个作为下轮上下文
                val keepFrom = (recentBuffer.length - windowSize).coerceAtLeast(0)
                if (keepFrom > 0) {
                    recentBuffer.delete(0, keepFrom)
                }
            } else {
                // 本轮未匹配，不再继续
                lastMatched = false
                lastScore = bestScore
                if (bestScore < LOW_CONFIDENCE) {
                    consecutiveNoMatch++
                    if (consecutiveNoMatch >= FLUSH_AFTER) {
                        recentBuffer.clear()
                        consecutiveNoMatch = 0
                    }
                } else {
                    consecutiveNoMatch = 0
                }
                break
            }
        }

        // 清理多余的旧字符，防止 buffer 无限制增长
        val maxBuf = (windowSize * 4).coerceAtLeast(20)
        while (recentBuffer.length > maxBuf) {
            recentBuffer.deleteCharAt(0)
        }

        // 上一条 → prev，新结果 → last（在循环外更新 UI 可见状态）
        prevScore = lastScore
        prevBuffer = lastBuffer
        prevMatched = lastMatched
        lastBuffer = recentBuffer.toString()

        return currentPosition
    }

    fun reset() {
        currentPosition = 0
        cleanPosition = 0
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
