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
        private const val JUMP_AFTER_LOCAL_FAILURES = 2
        private const val JUMP_SHORT_MIN_CHARS = 5
        private const val JUMP_LONG_MIN_CHARS = 8
        private const val JUMP_MAX_CHARS = 12
        private const val JUMP_SHORT_THRESHOLD = 0.95
        private const val JUMP_LONG_THRESHOLD = 0.72
        private const val MAX_AUTO_ADVANCE_CHARS = 50
    }

    private data class JumpRecovery(
        val score: Double,
        val cleanEnd: Int,
        val patternLength: Int,
        val distance: Int,
        val threshold: Double,
        val accepted: Boolean
    )

    // 原始文稿（含标点）
    val scriptChars: CharArray = script.toCharArray()

    // 无标点版本：用于匹配计算
    private val cleanChars: List<Char> = scriptChars.filter { !isPunctuation(it) && !it.isWhitespace() }
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
    @Volatile var lastDiagnostics: VoiceSyncDiagnostics = VoiceSyncDiagnostics()
        private set

    private fun buildIndexMapping(): List<Int> {
        val mapping = mutableListOf<Int>()
        for (i in scriptChars.indices) {
            if (!isPunctuation(scriptChars[i]) && !scriptChars[i].isWhitespace()) {
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
        val previousPosition = currentPosition
        val previousScore = lastScore
        val previousBuffer = lastBuffer
        val previousMatched = lastMatched

        // 对齐链路只用去标点、去空白的 ASR
        val cleanFull = stripPunctuationForAlignment(newText)
            .filter { !it.isWhitespace() }

        // 提取增量
        val delta = if (cleanFull.startsWith(lastFullClean)) {
            cleanFull.substring(lastFullClean.length)
        } else {
            // ASR 文本发生大幅变化或进入新句时，丢弃旧 buffer，但继续尝试匹配当前段。
            recentBuffer.clear()
            consecutiveNoMatch = 0
            cleanFull
        }
        lastFullClean = cleanFull

        if (delta.isEmpty()) {
            lastDiagnostics = VoiceSyncDiagnostics(
                rawTextLength = newText.length,
                cleanTextLength = cleanFull.length,
                deltaLength = 0,
                bufferLength = recentBuffer.length,
                previousPosition = previousPosition,
                currentPosition = currentPosition,
                cleanPosition = cleanPosition,
                consecutiveNoMatch = consecutiveNoMatch,
                matched = lastMatched,
                decision = "empty_delta"
            )
            return currentPosition
        }

        // 将增量加入滑动窗口
        recentBuffer.append(delta)
        var localSearchStart = cleanPosition
        var localSearchEnd = cleanPosition
        var localBestScore = -1.0
        var localBestEnd = cleanPosition
        var localMatched = false
        var jumpAttempted = false
        var jumpAccepted = false
        var jumpScore = -1.0
        var jumpPatternLength = 0
        var jumpDistance = 0
        var jumpThreshold = 0.0
        var decision = "buffering"

        if (recentBuffer.length >= windowSize) {
            // 使用缓冲区尾部 windowSize 个字符作为匹配模式
            val patStr = recentBuffer.takeLast(windowSize).toString()
            val patternPinyin = patStr.map { toPinyin(it) }
            val patternLen = patternPinyin.size

            val searchStart = (cleanPosition - searchBack).coerceAtLeast(0)
            val maxAutoCleanEnd = (cleanPosition + MAX_AUTO_ADVANCE_CHARS).coerceAtMost(cleanPinyin.size)
            val searchEnd = (cleanPosition + minOf(searchForward, MAX_AUTO_ADVANCE_CHARS)).coerceAtMost(cleanPinyin.size)
            localSearchStart = searchStart
            localSearchEnd = searchEnd

            var bestScore = -1.0
            var bestCleanEndIdx = cleanPosition

            for (start in searchStart until searchEnd) {
                val maxEnd = minOf(start + patternLen, cleanPinyin.size, maxAutoCleanEnd)
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
                    bestCleanEndIdx = (start + weightedMatch.coerceAtLeast(1)).coerceAtMost(maxAutoCleanEnd)
                }
            }

            val threshold = 0.30
            localBestScore = bestScore
            localBestEnd = bestCleanEndIdx
            localMatched = bestScore >= threshold && bestCleanEndIdx > cleanPosition
            val shouldTryJump = !localMatched && consecutiveNoMatch + 1 >= JUMP_AFTER_LOCAL_FAILURES
            if (shouldTryJump) {
                jumpAttempted = true
                findJumpRecovery()?.let { recovery ->
                    jumpAccepted = recovery.accepted
                    jumpScore = recovery.score
                    jumpPatternLength = recovery.patternLength
                    jumpDistance = recovery.distance
                    jumpThreshold = recovery.threshold
                    if (recovery.accepted) {
                        bestScore = recovery.score
                        bestCleanEndIdx = recovery.cleanEnd
                    }
                }
            }

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
                decision = if (jumpAccepted) "jump_match" else "local_match"

                // 本轮已消费 pattern 对应的文本，清理 buffer 中已被越过的旧字符
                // 保留最后 windowSize 个作为下轮上下文
                val keepFrom = (recentBuffer.length - windowSize).coerceAtLeast(0)
                if (keepFrom > 0) {
                    recentBuffer.delete(0, keepFrom)
                }
            } else {
                // 本轮未匹配
                lastMatched = false
                lastScore = bestScore
                decision = if (jumpAttempted) "no_match_after_jump_search" else "no_match"
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
        }

        // 清理多余的旧字符，防止 buffer 无限制增长
        val maxBuf = (windowSize * 4).coerceAtLeast(20)
        while (recentBuffer.length > maxBuf) {
            recentBuffer.deleteCharAt(0)
        }

        // 上一条 → prev，新结果 → last（在循环外更新 UI 可见状态）
        prevScore = previousScore
        prevBuffer = previousBuffer
        prevMatched = previousMatched
        lastBuffer = recentBuffer.toString()
        lastDiagnostics = VoiceSyncDiagnostics(
            rawTextLength = newText.length,
            cleanTextLength = cleanFull.length,
            deltaLength = delta.length,
            bufferLength = recentBuffer.length,
            previousPosition = previousPosition,
            currentPosition = currentPosition,
            cleanPosition = cleanPosition,
            localSearchStart = localSearchStart,
            localSearchEnd = localSearchEnd,
            localBestScore = localBestScore,
            localBestEnd = localBestEnd,
            localMatched = localMatched,
            jumpAttempted = jumpAttempted,
            jumpAccepted = jumpAccepted,
            jumpScore = jumpScore,
            jumpPatternLength = jumpPatternLength,
            jumpDistance = jumpDistance,
            jumpThreshold = jumpThreshold,
            consecutiveNoMatch = consecutiveNoMatch,
            matched = lastMatched,
            decision = decision
        )

        return currentPosition
    }

    private fun findJumpRecovery(): JumpRecovery? {
        if (recentBuffer.length < JUMP_SHORT_MIN_CHARS) return null

        val patStr = recentBuffer.takeLast(minOf(JUMP_MAX_CHARS, recentBuffer.length)).toString()
        val patternPinyin = patStr.map { toPinyin(it) }
        val patternLen = patternPinyin.size
        val isShortPattern = patternLen < JUMP_LONG_MIN_CHARS
        val jumpThreshold = if (isShortPattern) JUMP_SHORT_THRESHOLD else JUMP_LONG_THRESHOLD
        val minWeightedMatch = if (isShortPattern) {
            patternLen
        } else {
            maxOf(JUMP_LONG_MIN_CHARS - 1, (patternLen * 0.70).toInt())
        }

        var bestScore = -1.0
        var bestCleanEndIdx = cleanPosition
        var bestDistance = 0
        var bestThreshold = jumpThreshold
        val searchStart = (cleanPosition + 1).coerceAtMost(cleanPinyin.size)
        val searchEnd = (cleanPosition + MAX_AUTO_ADVANCE_CHARS).coerceAtMost(cleanPinyin.size)

        for (start in searchStart until searchEnd) {
            val maxEnd = minOf(start + patternLen, cleanPinyin.size, searchEnd)
            if (maxEnd - start < JUMP_SHORT_MIN_CHARS) break

            val result = similarity(patternPinyin, cleanPinyin.subList(start, maxEnd))
            val rawScore = result.first
            val weightedMatch = result.second
            val distance = start - cleanPosition
            if (weightedMatch < minWeightedMatch) continue

            if (rawScore > bestScore) {
                bestScore = rawScore
                bestCleanEndIdx = (start + patternLen).coerceAtMost(searchEnd)
                bestDistance = distance
                bestThreshold = jumpThreshold
            }
        }

        if (bestCleanEndIdx <= cleanPosition) return null
        return JumpRecovery(
            score = bestScore,
            cleanEnd = bestCleanEndIdx,
            patternLength = patternLen,
            distance = bestDistance,
            threshold = bestThreshold,
            accepted = bestScore >= bestThreshold
        )
    }

    fun reset() {
        currentPosition = 0
        cleanPosition = 0
        clearAsrState()
    }

    private fun clearAsrState() {
        lastFullClean = ""
        recentBuffer.clear()
        consecutiveNoMatch = 0
        lastScore = 0.0
        lastBuffer = ""
        lastMatched = false
        prevScore = 0.0
        prevBuffer = ""
        prevMatched = false
        lastDiagnostics = VoiceSyncDiagnostics()
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
        clearAsrState()
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
