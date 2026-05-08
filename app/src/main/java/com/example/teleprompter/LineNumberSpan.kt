package com.example.teleprompter

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spanned
import android.text.style.LeadingMarginSpan

/**
 * 在每个原始段落第一行前面显示行号的 Span
 */
class LineNumberSpan(
    val lineNumber: Int,
    val spanStart: Int,
    private val marginWidth: Int = 80,
    private val textColor: Int = 0x80FFFFFF.toInt()
) : LeadingMarginSpan {

    private val numberText = "$lineNumber."

    override fun getLeadingMargin(first: Boolean): Int {
        return marginWidth
    }

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence?,
        start: Int,
        end: Int,
        first: Boolean,
        layout: android.text.Layout?
    ) {
        // 只有当这一行的起始位置等于 span 的起始位置时，才绘制编号
        // 这样可以确保只在段落第一行显示编号
        if (start != spanStart) return

        val style = p.style
        val color = p.color
        val textSize = p.textSize

        p.style = Paint.Style.FILL
        p.color = textColor
        p.textSize = textSize * 0.7f

        val textWidth = p.measureText(numberText)
        val textX = (x + marginWidth - textWidth - 8).toFloat()
        c.drawText(numberText, textX, baseline.toFloat(), p)

        p.style = style
        p.color = color
        p.textSize = textSize
    }
}
