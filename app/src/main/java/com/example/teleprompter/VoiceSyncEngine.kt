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

    // ASR 累积（仅对齐/滚动；已去掉与文稿 isPunctuation 一致的符号,与 enable_punc 转写原文分离）
    private val fullAccumulated = StringBuilder()
    private var lastFullClean = ""

    /** 用于导出/上传：豆包流式 text 多为「当前句整段假设」反复覆盖，需去重、去误分段 */
    private val transcriptFinalized = StringBuilder()
    private var transcriptInterim: String = ""
    private var lastExportPacket: String = ""

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

    /** 与文稿 [isPunctuation] 一致：对齐/滚动仅看「字」,不看识别里的标点(转写仍保留原文标点)。 */
    private fun stripPunctuationForAlignment(s: String): String = s.filter { !isPunctuation(it) }

    @Synchronized
    fun onAsrIncrement(newText: String, isFinal: Boolean = false): Int {
        recordTranscriptForExport(newText, isFinal)
        // 对齐链路只用去标点后的 ASR；转写已在 recordTranscriptForExport 使用原始 newText
        val alignedText = stripPunctuationForAlignment(newText)
        // full 模式:每包为当前累计完整文本,整段替换; single 模式:多为片段追加(与豆包文档一致)
        val cleanIn = alignedText.filter { !it.isWhitespace() }
        val cleanPrev = fullAccumulated.toString().filter { !it.isWhitespace() }
        when {
            cleanIn.startsWith(cleanPrev) || cleanPrev.isEmpty() -> {
                fullAccumulated.setLength(0)
                fullAccumulated.append(alignedText)
            }
            cleanPrev.startsWith(cleanIn) -> {
                fullAccumulated.setLength(0)
                fullAccumulated.append(alignedText)
            }
            else -> fullAccumulated.append(alignedText)
        }
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
        transcriptFinalized.setLength(0)
        transcriptInterim = ""
        lastExportPacket = ""
    }

    /**
     * 去重后的语音转写：流式结果常为「整句当前假设」递增替换，非字符增量拼接。
     */
    @Synchronized
    fun accumulatedAsrTranscript(): String {
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
    private fun recordTranscriptForExport(text: String, isFinal: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (t == lastExportPacket) return
        lastExportPacket = t

        val compactT = compactForAsr(t)
        val compactFinalized = compactForAsr(transcriptFinalized.toString())

        if (isFinal) {
            // 最终结果：检查是否包含了已有的 finalized 内容
            val newContent = if (compactFinalized.isNotEmpty() && compactT.startsWith(compactFinalized)) {
                // 新文本包含了已有的 finalized 内容，提取新增部分
                t.substring(transcriptFinalized.length).trim()
            } else {
                t
            }
            
            // 处理新增部分
            if (newContent.isNotEmpty()) {
                for (part in newContent.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
                    appendOneFinalizedParagraph(part)
                }
            }
            transcriptInterim = ""
            lastExportPacket = ""
            return
        }

        // 非最终结果：处理中间状态
        // 检查新文本是否包含了已有的 finalized 内容
        val baseContent = if (compactFinalized.isNotEmpty() && compactT.startsWith(compactFinalized)) {
            // 新文本包含了已有的 finalized 内容，只保留新增部分作为 interim
            t.substring(transcriptFinalized.length).trim()
        } else {
            t
        }

        val cur = transcriptInterim
        val ct = compactForAsr(baseContent)
        val cc = compactForAsr(cur)

        when {
            cur.isEmpty() -> transcriptInterim = baseContent
            ct.startsWith(cc) -> transcriptInterim = baseContent
            cc.startsWith(ct) -> transcriptInterim = baseContent // 用新的，更准确的识别
            baseContent.length == 1 -> transcriptInterim = cur.trim() + baseContent
            else -> {
                // full模式，新内容可能是重新开始，谨慎处理，只更新interim
                transcriptInterim = baseContent
            }
        }
    }

    private fun compactForAsr(s: String): String = s.filter { !it.isWhitespace() }

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

/**
 * 合并「后一行是前一行去掉空白后的真超集」的阶梯重复行。
 * internal：供单元测试与 [VoiceSyncEngine.accumulatedAsrTranscript] 共用。
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
