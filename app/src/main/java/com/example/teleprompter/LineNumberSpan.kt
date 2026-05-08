package com.example.teleprompter

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.LineBackgroundSpan
import android.text.style.LeadingMarginSpan

/**
 * 在每行前面显示行号的 Span
 */
class LineNumberSpan(
    private val lineNumber: Int,
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
