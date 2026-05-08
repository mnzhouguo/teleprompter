package com.example.teleprompter

import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

class ScrollController(
    private val scrollView: ScrollView,
    private val textView: TextView
) {
    private var lastLine = 0

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

    fun scrollToChar(charIndex: Int) {
        val layout = textView.layout
        if (layout == null) {
            textView.post { scrollToChar(charIndex) }
            return
        }
        val safeIndex = charIndex.coerceIn(0, (textView.text.length - 1).coerceAtLeast(0))
        val line = layout.getLineForOffset(safeIndex)
        lastLine = line

        val lineTopInLayout = layout.getLineTop(line).toFloat()
        val paddingTop = textView.paddingTop.toFloat()
        val lineAbsY = paddingTop + lineTopInLayout

        val visibleH = scrollView.height.takeIf { it > 0 } ?: 500
        val targetScrollY = (lineAbsY - visibleH * 0.30f).coerceAtLeast(0f)

        springAnim.animateToFinalPosition(targetScrollY)
        android.util.Log.d("ScrollCtrl", "charIdx=$charIndex line=$line absY=$lineAbsY target=$targetScrollY svH=$visibleH")
    }

    fun scrollOneLine(): Int {
        val layout = textView.layout
        if (layout == null || layout.lineCount == 0) return 0
        val svH = scrollView.height.takeIf { it > 0 } ?: return 0

        val nextLine = (lastLine + 1).coerceAtMost(layout.lineCount - 1)
        if (nextLine == lastLine) return 0 // already at last line

        lastLine = nextLine
        val lineTop = layout.getLineTop(nextLine) + textView.paddingTop
        val targetScrollY = (lineTop - svH * 0.30f).coerceAtLeast(0f)

        springAnim.animateToFinalPosition(targetScrollY)
        val charIndex = layout.getLineStart(nextLine)
        android.util.Log.d("ScrollCtrl", "scrollOneLine: lastLine=$lastLine charIndex=$charIndex target=$targetScrollY")
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

        val line = layout.getLineForVertical(targetY.toInt().coerceAtLeast(0))
        lastLine = line
        return layout.getLineStart(line)
    }
}
