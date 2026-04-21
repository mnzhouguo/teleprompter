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

        // layout.getLineTop 返回的是文字区域内的 Y（不含 paddingTop）
        // ScrollView 滚动的是整个 TextView（含 paddingTop），所以要加上 paddingTop
        val lineTopInLayout = layout.getLineTop(line).toFloat()
        val paddingTop = textView.paddingTop.toFloat()
        val lineAbsY = paddingTop + lineTopInLayout   // 在 ScrollView 内容中的绝对 Y

        // 让当前行落在可见区上部 30% 处
        val visibleH = scrollView.height.takeIf { it > 0 } ?: 500
        val targetScrollY = (lineAbsY - visibleH * 0.30f).coerceAtLeast(0f)

        springAnim.animateToFinalPosition(targetScrollY)
        android.util.Log.d("ScrollCtrl", "charIdx=$charIndex line=$line absY=$lineAbsY target=$targetScrollY svH=$visibleH")
    }

    fun stop() {
        springAnim.cancel()
    }
}
