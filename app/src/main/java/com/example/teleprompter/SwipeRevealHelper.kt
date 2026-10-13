package com.example.teleprompter

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 左滑露出右侧操作区。同一时间只允许一张卡片处于打开状态。
 *
 * @param touchView 接收手势的视图（可为内部可点击区域）
 * @param moveView 实际平移的前景（默认同 [touchView]）
 */
object SwipeRevealHelper {

    private var openMoveView: View? = null

    fun closeOpen(animate: Boolean = true) {
        val view = openMoveView ?: return
        if (animate) {
            view.animate().translationX(0f).setDuration(160).start()
        } else {
            view.translationX = 0f
        }
        openMoveView = null
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind(
        touchView: View,
        moveView: View = touchView,
        revealWidthPx: () -> Int
    ) {
        val touchSlop = ViewConfiguration.get(touchView.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startTranslation = 0f
        var trackingHorizontal = false
        var decided = false

        touchView.setOnTouchListener { _, event ->
            val maxReveal = revealWidthPx().toFloat().coerceAtLeast(0f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTranslation = moveView.translationX
                    trackingHorizontal = false
                    decided = false
                    if (openMoveView != null && openMoveView !== moveView) {
                        closeOpen()
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!decided) {
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            decided = true
                            trackingHorizontal = abs(dx) > abs(dy)
                            if (trackingHorizontal) {
                                (touchView.parent as? ViewGroup)
                                    ?.requestDisallowInterceptTouchEvent(true)
                                (moveView.parent as? ViewGroup)
                                    ?.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                    }
                    if (trackingHorizontal) {
                        val next = min(0f, max(-maxReveal, startTranslation + dx))
                        moveView.translationX = next
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (trackingHorizontal) {
                        (touchView.parent as? ViewGroup)
                            ?.requestDisallowInterceptTouchEvent(false)
                        (moveView.parent as? ViewGroup)
                            ?.requestDisallowInterceptTouchEvent(false)
                        val open = moveView.translationX < -maxReveal / 2f
                        val target = if (open) -maxReveal else 0f
                        moveView.animate().translationX(target).setDuration(160).start()
                        openMoveView = if (open) moveView else null
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    fun closeIfOpen(moveView: View) {
        if (openMoveView === moveView) {
            closeOpen()
        } else {
            moveView.animate().translationX(0f).setDuration(120).start()
        }
    }
}
