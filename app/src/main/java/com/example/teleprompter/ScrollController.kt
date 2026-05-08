package com.example.teleprompter

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

class ScrollController(
    private val scrollView: ScrollView,
    private val textView: TextView,
    private val originalText: String
) {
    // 原始文稿的换行位置（每个元素是该行起始字符索引），不包含空行
    val originalLineStarts: List<Int> = parseOriginalLines(originalText)
    // 当前滚动到的原始行索引
    private var currentOriginalLineIndex = 0

    private val scrollYProperty = object : FloatPropertyCompat<ScrollView>("scrollY") {
        override fun getValue(obj: ScrollView) = obj.scrollY.toFloat()
        override fun setValue(obj: ScrollView, value: Float) {
            obj.scrollTo(0, value.toInt().coerceAtLeast(0))
        }
    }

    private val springAnim = SpringAnimation(scrollView, scrollYProperty).apply {
        spring = SpringForce().apply {
            stiffness = SpringForce.STIFFNESS_LOW
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }
    }

    /**
     * 解析原始文稿，返回非空行的起始字符索引列表
     */
    private fun parseOriginalLines(text: String): List<Int> {
        val lines = mutableListOf<Int>()
        lines.add(0) // 第一行从0开始
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                val nextLineStart = i + 1
                // 检查下一行是否为空行
                if (nextLineStart < text.length) {
                    var j = nextLineStart
                    var isBlank = true
                    while (j < text.length && text[j] != '\n') {
                        if (!text[j].isWhitespace()) {
                            isBlank = false
                            break
                        }
                        j++
                    }
                    if (!isBlank) {
                        lines.add(nextLineStart)
                    }
                }
            }
            i++
        }
        return lines
    }

    fun scrollToChar(charIndex: Int) {
        val layout = textView.layout
        if (layout == null) {
            textView.post { scrollToChar(charIndex) }
            return
        }
        val safeIndex = charIndex.coerceIn(0, (textView.text.length - 1).coerceAtLeast(0))
        val line = layout.getLineForOffset(safeIndex)

        // 更新当前原始行索引到最接近的位置
        currentOriginalLineIndex = originalLineStarts.indexOfLast { it <= safeIndex }
            .coerceAtLeast(0)

        val lineTopInLayout = layout.getLineTop(line).toFloat()
        val paddingTop = textView.paddingTop.toFloat()
        val lineAbsY = paddingTop + lineTopInLayout

        val visibleH = scrollView.height.takeIf { it > 0 } ?: 500
        val targetScrollY = (lineAbsY - visibleH * 0.30f).coerceAtLeast(0f)

        springAnim.animateToFinalPosition(targetScrollY)
        android.util.Log.d("ScrollCtrl", "charIdx=$charIndex line=$line absY=$lineAbsY target=$targetScrollY svH=$visibleH")
    }

    /**
     * 按原始文稿滚动一行，跳过空行
     */
    fun scrollOneLine(): Int {
        val layout = textView.layout
        if (layout == null || originalLineStarts.isEmpty()) return 0
        val svH = scrollView.height.takeIf { it > 0 } ?: return 0

        // 找到下一个原始行
        val nextLineIndex = (currentOriginalLineIndex + 1).coerceAtMost(originalLineStarts.size - 1)
        if (nextLineIndex == currentOriginalLineIndex) {
            // 已经到最后一行
            return originalLineStarts[nextLineIndex]
        }

        currentOriginalLineIndex = nextLineIndex
        val charIndex = originalLineStarts[nextLineIndex]

        // 滚动到该位置
        val displayLine = layout.getLineForOffset(charIndex)
        val lineTop = layout.getLineTop(displayLine) + textView.paddingTop
        val targetScrollY = (lineTop - svH * 0.30f).coerceAtLeast(0f)

        springAnim.animateToFinalPosition(targetScrollY)
        android.util.Log.d("ScrollCtrl", "scrollOneLine: origLineIdx=$currentOriginalLineIndex charIdx=$charIndex target=$targetScrollY")
        return charIndex
    }

    fun stop() {
        springAnim.cancel()
    }

    /**
     * 获取当前可见区域目标位置（30%处）对应的字符索引
     */
    fun getCurrentPositionCharIndex(): Int {
        val layout = textView.layout ?: return 0
        val scrollY = scrollView.scrollY
        val visibleH = scrollView.height.takeIf { it > 0 } ?: 500
        val targetY = scrollY + visibleH * 0.30f - textView.paddingTop

        val displayLine = layout.getLineForVertical(targetY.toInt().coerceAtLeast(0))
        val charIndex = layout.getLineStart(displayLine)

        // 更新当前原始行索引
        currentOriginalLineIndex = originalLineStarts.indexOfLast { it <= charIndex }
            .coerceAtLeast(0)

        return charIndex
    }
}
