package com.example.teleprompter

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType

class VoiceSyncEngine(
    val script: String,
    voiceSegmentMaxSize: Int = 5,
    forwardSearchLines: Int = 2,
    backwardSearchChars: Int = 3
) {
    var windowSize: Int = voiceSegmentMaxSize
    var searchBack: Int = backwardSearchChars
    var forwardSearchLines: Int = forwardSearchLines
        set(value) {
            field = value
            searchForward = charsForLines(value)
        }
    var searchForward: Int = 10

    /** 与 [windowSize] 同名，供配置 UI 使用 */
    var voiceSegmentMaxSize: Int
        get() = windowSize
        set(value) { windowSize = value }

    /** 与 [searchBack] 同名，供配置 UI 使用 */
    var backwardSearchChars: Int
        get() = searchBack
        set(value) { searchBack = value }

    private fun charsForLines(lines: Int): Int {
        val lineCount = script.count { it == '\n' } + 1
        val avgCharsPerLine = matchSurfaceChars.size / lineCount.coerceAtLeast(1)
        val effectiveLines = if (avgCharsPerLine <= 6) lines * 2 else lines
        return (avgCharsPerLine * effectiveLines).coerceAtLeast(10)
    }

    companion object {
        private const val LOW_CONFIDENCE_THRESHOLD = 0.35
        private const val FOLLOW_LOSS_LIMIT = 3
    }

    // 原始文稿（含标点）
    val scriptChars: CharArray = script.toCharArray()

    // 无标点版本：用于匹配计算
    private val matchSurfaceChars: List<Char> = scriptChars.filter { !isPunctuation(it) }
    private val matchSurfacePinyin: List<String> = matchSurfaceChars.map { toPinyin(it) }

    init {
        searchForward = charsForLines(forwardSearchLines)
    }

    // 映射：匹配位置 → 文稿位置
    private val positionMapping: List<Int> = buildPositionMapping()

    @Volatile
    var scriptPosition: Int = 0
        private set

    private var matchPosition: Int = 0

    // ASR 累积（对齐/滚动用；去掉与文稿一致的标点，与转写原文分离）
    private val asrAccumulated = StringBuilder()
    private var lastCleanAsr = ""

    /** 用于导出/上传：豆包流式 text 多为「当前句整段假设」反复覆盖，需去重、去误分段 */
    private val transcriptFinalized = StringBuilder()
    private var transcriptInterim: String = ""
    private var lastExportPacket: String = ""

    // 语音片段（最近 voiceSegmentMaxSize 个识别字符）
    val voiceSegment: String get() = voiceSegmentBuffer.toString()
    private val voiceSegmentBuffer = StringBuilder()

    private var followLossCount = 0

    // 最近两次匹配结果（供 UI 读取）
    @Volatile var lastScore: Double = 0.0
    @Volatile var lastBuffer: String = ""
    @Volatile var lastMatched: Boolean = false
    @Volatile var prevScore: Double = 0.0
    @Volatile var prevBuffer: String = ""
    @Volatile var prevMatched: Boolean = false

    private fun buildPositionMapping(): List<Int> {
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

    /** 与文稿 [isPunctuation] 一致：对齐/滚动仅看「字」,不看识别里的标点(转写仍保留原文标点)。 */
    private fun stripPunctuation(s: String): String = s.filter { !isPunctuation(it) }

    @Synchronized
    fun processAsrDelta(newText: String, isFinal: Boolean = false): Int {
        recordTranscript(newText, isFinal)
        // 对齐链路只用去标点后的 ASR；转写已在 recordTranscript 使用原始 newText
        val alignedText = stripPunctuation(newText)
        // full 模式:每包为当前累计完整文本,整段替换; single 模式:多为片段追加(与豆包文档一致)
        val matchInput = alignedText.filter { !it.isWhitespace() }
        val matchPrev = asrAccumulated.toString().filter { !it.isWhitespace() }
        when {
            matchInput.startsWith(matchPrev) || matchPrev.isEmpty() -> {
                asrAccumulated.setLength(0)
                asrAccumulated.append(alignedText)
            }
            matchPrev.startsWith(matchInput) -> {
                asrAccumulated.setLength(0)
                asrAccumulated.append(alignedText)
            }
            else -> {
                // 新分句或 ASR 整段改写：整段替换，避免把上一句拼进 asrAccumulated
                asrAccumulated.setLength(0)
                asrAccumulated.append(alignedText)
            }
        }
        val matchFull = asrAccumulated.filter { !it.isWhitespace() }.toString()

        val delta: String
        if (matchFull.startsWith(lastCleanAsr)) {
            delta = matchFull.substring(lastCleanAsr.length)
            lastCleanAsr = matchFull
        } else {
            // 整段替换：取公共前缀，尾部作为新增量继续匹配（CONTEXT.md）
            var prefixLen = 0
            val limit = minOf(matchFull.length, lastCleanAsr.length)
            while (prefixLen < limit && matchFull[prefixLen] == lastCleanAsr[prefixLen]) {
                prefixLen++
            }
            lastCleanAsr = matchFull
            if (prefixLen == 0) {
                voiceSegmentBuffer.clear()
                followLossCount = 0
            }
            delta = matchFull.substring(prefixLen)
        }

        if (delta.isEmpty()) {
            if (isFinal) resetAlignmentForNextUtterance()
            return scriptPosition
        }

        for (ch in delta) {
            voiceSegmentBuffer.append(ch)
        }
        while (voiceSegmentBuffer.length > windowSize) {
            voiceSegmentBuffer.deleteCharAt(0)
        }

        if (voiceSegmentBuffer.length < 2) {
            if (isFinal) resetAlignmentForNextUtterance()
            return scriptPosition
        }

        val patternPinyin = voiceSegmentBuffer.map { toPinyin(it) }
        val patternLen = patternPinyin.size

        val searchStart = (matchPosition - searchBack).coerceAtLeast(0)
        val primarySearchEnd = (matchPosition + searchForward).coerceAtMost(matchSurfacePinyin.size)

        // ── 第一轮搜索（常规）：带距离惩罚 ──
        var bestScore = -1.0
        var bestMatchEndIdx = matchPosition
        var bestForwardDist = 0

        for (start in searchStart until primarySearchEnd) {
            val maxEnd = (start + patternLen).coerceAtMost(matchSurfacePinyin.size)
            if (maxEnd - start < 2) break

            val result = pinyinSimilarity(patternPinyin, matchSurfacePinyin.subList(start, maxEnd))
            val rawScore = result.first
            val weightedMatch = result.second
            if (weightedMatch < 2) continue  // 至少加权匹配2个字符

            val forwardDist = (start - matchPosition).coerceAtLeast(0)

            // 距离惩罚：远处陡峭，鼓励近处精确匹配
            val penalty = when {
                forwardDist <= 2 -> 0.0
                forwardDist <= 8 -> (forwardDist - 2) / 8.0 * 0.12
                else -> 0.12 + (forwardDist - 8) / 12.0 * 0.48
            }
            val score = rawScore - penalty

            if (score > bestScore) {
                bestScore = score
                bestMatchEndIdx = start + weightedMatch.coerceAtLeast(1)
                bestForwardDist = forwardDist
            }
        }

        // ── 第二轮搜索（fallback）：常规搜索无匹配时，不带距离惩罚搜满范围 ──
        if (bestScore < 0.30 && matchPosition + 1 < primarySearchEnd) {
            var fallbackScore = -1.0
            var fallbackEndIdx = matchPosition

            for (start in searchStart until primarySearchEnd) {
                val maxEnd = (start + patternLen).coerceAtMost(matchSurfacePinyin.size)
                if (maxEnd - start < 2) break

                val result = pinyinSimilarity(patternPinyin, matchSurfacePinyin.subList(start, maxEnd))
                val rawScore = result.first
                val weightedMatch = result.second
                if (weightedMatch < 2) continue

                // fallback：无距离惩罚，仅看拼音匹配质量
                if (rawScore > fallbackScore) {
                    fallbackScore = rawScore
                    fallbackEndIdx = start + weightedMatch.coerceAtLeast(1)
                }
            }

            // fallback 需要更高阈值（0.50 vs 常规 0.30），确保不误跳
            if (fallbackScore >= 0.50 && fallbackEndIdx > matchPosition) {
                bestScore = fallbackScore
                bestMatchEndIdx = fallbackEndIdx
                bestForwardDist = (fallbackEndIdx - matchPosition).coerceAtLeast(0)
            }
        }

        // 统一阈值
        val threshold = 0.30

        // 上一条 → prev，新结果 → last
        prevScore = lastScore
        prevBuffer = lastBuffer
        prevMatched = lastMatched

        lastScore = bestScore
        lastBuffer = voiceSegmentBuffer.toString()

        if (bestScore >= threshold && bestMatchEndIdx > matchPosition) {
            matchPosition = bestMatchEndIdx
            scriptPosition = if (bestMatchEndIdx < positionMapping.size) {
                positionMapping[bestMatchEndIdx]
            } else {
                scriptChars.size
            }
            followLossCount = 0
            lastMatched = true
        } else {
            lastMatched = false
            if (bestScore < LOW_CONFIDENCE_THRESHOLD) {
                followLossCount++
                if (followLossCount >= FOLLOW_LOSS_LIMIT) {
                    voiceSegmentBuffer.clear()
                    followLossCount = 0
                }
            } else {
                followLossCount = 0
            }
        }
        if (isFinal) resetAlignmentForNextUtterance()
        return scriptPosition
    }

    /** 一句定稿后清空对齐累积，下一句从全新 ASR 假设开始增量提取 */
    private fun resetAlignmentForNextUtterance() {
        lastCleanAsr = ""
        asrAccumulated.clear()
        voiceSegmentBuffer.clear()
        followLossCount = 0
    }

    /** @deprecated 使用 [processAsrDelta] */
    fun onAsrIncrement(newText: String, isFinal: Boolean = false): Int =
        processAsrDelta(newText, isFinal)

    /** @deprecated 使用 [getTranscript] */
    fun accumulatedAsrTranscript(): String = getTranscript()

    fun reset() {
        scriptPosition = 0
        matchPosition = 0
        asrAccumulated.clear()
        lastCleanAsr = ""
        voiceSegmentBuffer.clear()
        followLossCount = 0
        transcriptFinalized.setLength(0)
        transcriptInterim = ""
        lastExportPacket = ""
    }

    /**
     * 去重后的语音转写：流式结果常为「整句当前假设」递增替换，非字符增量拼接。
     */
    @Synchronized
    fun getTranscript(): String {
        val fin = transcriptFinalized.toString().trim()
        val inter = transcriptInterim.trim()
        val raw = when {
            fin.isEmpty() -> inter
            inter.isEmpty() -> fin
            else -> "$fin\n$inter"
        }
        return collapseTranscriptStaircaseLines(raw)
    }

    /**
     * 维护 transcriptFinalized / transcriptInterim，避免把每次整句假设都 append 成重复串。
     *
     * 豆包常见行为：同一识别分片会多次下发相同或「前缀延长」的 text；definite=true 时定稿一句。
     * 不能用「非前缀就定稿上一包」的策略，否则会把仍在生长的半句反复写入 finalized。
     */
    private fun recordTranscript(text: String, isFinal: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (t == lastExportPacket) return
        lastExportPacket = t

        val compactT = compactForDelta(t)
        val compactFinalized = compactForDelta(transcriptFinalized.toString())

        if (isFinal) {
            val newContent = if (compactFinalized.isNotEmpty() && compactT.startsWith(compactFinalized)) {
                t.substring(transcriptFinalized.length).trim()
            } else {
                t
            }

            if (newContent.isNotEmpty()) {
                for (part in newContent.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
                    appendOneFinalizedParagraph(part)
                }
            }
            transcriptInterim = ""
            lastExportPacket = ""
            return
        }

        val baseContent = if (compactFinalized.isNotEmpty() && compactT.startsWith(compactFinalized)) {
            t.substring(transcriptFinalized.length).trim()
        } else {
            t
        }

        val cur = transcriptInterim
        val ct = compactForDelta(baseContent)
        val cc = compactForDelta(cur)

        when {
            cur.isEmpty() -> transcriptInterim = baseContent
            ct.startsWith(cc) -> transcriptInterim = baseContent
            cc.startsWith(ct) -> transcriptInterim = baseContent
            baseContent.length == 1 -> transcriptInterim = cur.trim() + baseContent
            else -> {
                transcriptInterim = baseContent
            }
        }
    }

    private fun compactForDelta(s: String): String = s.filter { !it.isWhitespace() }

    /** 定稿段落：与末行去重；新行若为末行的真超集则替换末行 */
    private fun appendOneFinalizedParagraph(L: String) {
        if (L.isEmpty()) return
        if (transcriptFinalized.isEmpty()) {
            transcriptFinalized.append(L)
            return
        }
        val fin = transcriptFinalized.toString()
        val lastPara = fin.substringAfterLast('\n').trim()
        if (lastPara == L) return
        if (lastPara.isNotEmpty()) {
            when {
                L.startsWith(lastPara) && L.length > lastPara.length -> {
                    stripLastFinalizedParagraph()
                    if (transcriptFinalized.isNotEmpty()) transcriptFinalized.append('\n')
                    transcriptFinalized.append(L)
                    return
                }
                lastPara.startsWith(L) && lastPara.length >= L.length -> return
            }
        }
        transcriptFinalized.append('\n').append(L)
    }

    private fun stripLastFinalizedParagraph() {
        val s = transcriptFinalized.toString()
        val idx = s.lastIndexOf('\n')
        transcriptFinalized.setLength(0)
        if (idx >= 0) transcriptFinalized.append(s.substring(0, idx))
    }

    @Synchronized
    fun setPosition(originalCharIndex: Int) {
        scriptPosition = originalCharIndex.coerceIn(0, scriptChars.size)
        // 找到对应的 matchPosition
        matchPosition = 0
        for (i in positionMapping.indices) {
            if (positionMapping[i] <= scriptPosition) {
                matchPosition = i
            } else {
                break
            }
        }
        asrAccumulated.clear()
        lastCleanAsr = ""
        voiceSegmentBuffer.clear()
        followLossCount = 0
        android.util.Log.d("VoiceSync", "setPosition: orig=$scriptPosition match=$matchPosition")
    }

    // 返回 Pair<拼音相似度分数, 加权匹配字符数>
    private fun pinyinSimilarity(a: List<String>, b: List<String>): Pair<Double, Int> {
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

/**
 * 合并「后一行是前一行去掉空白后的真超集」的阶梯重复行。
 * internal：供单元测试与 [VoiceSyncEngine.getTranscript] 共用。
 */
internal fun collapseTranscriptStaircaseLines(s: String): String {
    val rawLines = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (rawLines.size <= 1) return rawLines.joinToString("\n")
    val compact = { x: String -> x.filter { !it.isWhitespace() } }
    val merged = mutableListOf<String>()
    outer@ for (line in rawLines) {
        val c = compact(line)
        if (c.isEmpty()) continue
        while (merged.isNotEmpty()) {
            val p = compact(merged.last())
            when {
                c.startsWith(p) -> merged.removeAt(merged.size - 1)
                p.startsWith(c) && p.length >= c.length -> continue@outer
                else -> break
            }
        }
        merged.add(line)
    }
    return merged.joinToString("\n")
}
