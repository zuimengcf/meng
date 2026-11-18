/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

open class CustomGestureView(ctx: Context) : FrameLayout(ctx) {

    enum class SwipeAxis { X, Y }

    enum class GestureType { Down, Move, Up }

    data class Event(
        val type: GestureType,
        val consumed: Boolean,
        val x: Float,
        val y: Float,
        val countX: Int,
        val countY: Int,
        val totalX: Int,
        val totalY: Int
    )

    fun interface OnGestureListener {
        fun onGesture(view: View, event: Event): Boolean

        companion object {
            val Empty = OnGestureListener { _, _ -> false }
        }
    }

    /**
     * 长按/连发的延时用 Handler 实现（视图自身机制），
     * 不依赖 findViewTreeLifecycleOwner() —— 在 RecyclerView 条目首次 attach 的竞态窗口下，
     * 外部 lifecycleOwner 可能尚未就绪（旧实现 `!!` 会抛 NPE/协程不执行），
     * 导致「打开剪切板直接长按点不动、滑动一下才恢复」「候选展开区点不动」。
     */
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var touchMovedOutside = false

    @Volatile
    private var longPressTriggered = false
    var longPressEnabled = false
    private var longPressRunnable: Runnable? = null
    private var longPressRunAt = 0L

    @Volatile
    var longPressFeedbackEnabled = true

    @Volatile
    private var repeatStarted = false
    var repeatEnabled = false
    private var repeatRunnable: Runnable? = null

    var swipeEnabled = false
    var swipeRepeatEnabled = false
    var swipeThresholdX = 24f
    var swipeThresholdY = 24f

    private var swipeRepeatTriggered = false
    private var swipeLastX = -1f
    private var swipeLastY = -1f
    private var swipeXUnconsumed = 0f
    private var swipeYUnconsumed = 0f
    private var swipeTotalX = 0
    private var swipeTotalY = 0
    private var gestureConsumed = false

    var doubleTapEnabled = false
    private var lastClickTime = 0L
    private var maybeDoubleTap = false

    var onDoubleTapListener: ((View) -> Unit)? = null
    var onRepeatListener: ((View) -> Unit)? = null
    var onGestureListener: OnGestureListener? = null

    var soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard

    private val touchSlop: Float = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            isPressed = false
        }
    }

    private fun pointInView(x: Float, y: Float): Boolean {
        return -touchSlop <= x &&
                -touchSlop <= y &&
                x < (width + touchSlop) &&
                y < (height + touchSlop)
    }

    private fun resetState() {
        touchMovedOutside = false
        if (longPressEnabled) {
            longPressTriggered = false
            handler.removeCallbacksAndMessages(null)
            longPressRunnable = null
            longPressRunAt = 0L
        }
        if (repeatEnabled) {
            repeatStarted = false
            handler.removeCallbacksAndMessages(null)
            repeatRunnable = null
        }
        if (swipeEnabled) {
            if (swipeRepeatEnabled) {
                swipeRepeatTriggered = false
            }
            swipeXUnconsumed = 0f
            swipeYUnconsumed = 0f
            swipeTotalX = 0
            swipeTotalY = 0
            gestureConsumed = false
        }
        // double tap state should be preserved on touch up
    }

    fun cancelGestures() {
        isPressed = false
        handler.removeCallbacksAndMessages(null)
        longPressRunnable = null
        longPressRunAt = 0L
        repeatRunnable = null
        longPressTriggered = false
        repeatStarted = false
        resetState()
        // reset double tap state on cancel
        if (doubleTapEnabled) {
            maybeDoubleTap = false
            lastClickTime = 0
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 视图从窗口移除时清理所有挂起的延时，避免条目回收后仍触发长按/连发
        handler.removeCallbacksAndMessages(null)
        longPressRunnable = null
        longPressRunAt = 0L
        repeatRunnable = null
        longPressTriggered = false
        repeatStarted = false
        touchMovedOutside = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)
                isPressed = true
                InputFeedbacks.hapticFeedback(this)
                InputFeedbacks.soundEffect(soundEffect)
                dispatchGestureEvent(GestureType.Down, x, y)
                if (longPressEnabled) {
                    handler.removeCallbacksAndMessages(null)
                    longPressTriggered = false
                    longPressRunAt = SystemClock.uptimeMillis()
                    val lpRunnable = Runnable {
                        longPressRunnable = null
                        if (longPressFeedbackEnabled) {
                            InputFeedbacks.hapticFeedback(this@CustomGestureView, true)
                        }
                        longPressTriggered = performLongClick()
                    }
                    longPressRunnable = lpRunnable
                    handler.postDelayed(lpRunnable, longPressDelay.toLong())
                }
                if (repeatEnabled) {
                    handler.removeCallbacksAndMessages(null)
                    repeatStarted = false
                    val rpRunnable = object : Runnable {
                        override fun run() {
                            repeatStarted = true
                            if (!isEnabled) return
                            onRepeatListener?.invoke(this@CustomGestureView)
                            handler.postDelayed(this, RepeatInterval)
                        }
                    }
                    repeatRunnable = rpRunnable
                    handler.postDelayed(rpRunnable, longPressDelay.toLong())
                }
                if (swipeEnabled) {
                    swipeLastX = x
                    swipeLastY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                InputFeedbacks.hapticFeedback(this, longPress = true, keyUp = true)
                dispatchGestureEvent(GestureType.Up, event.x, event.y)
                val shouldPerformClick = !(touchMovedOutside ||
                        longPressTriggered ||
                        repeatStarted ||
                        swipeRepeatTriggered ||
                        gestureConsumed)
                resetState()
                if (shouldPerformClick) {
                    if (doubleTapEnabled) {
                        val now = System.currentTimeMillis()
                        if (maybeDoubleTap && now - lastClickTime <= longPressDelay) {
                            maybeDoubleTap = false
                            onDoubleTapListener?.invoke(this)
                        } else {
                            maybeDoubleTap = true
                            performClick()
                        }
                        lastClickTime = now
                    } else {
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)
                if (!touchMovedOutside && !pointInView(x, y)) {
                    touchMovedOutside = true
                    if (longPressEnabled || repeatEnabled) {
                        handler.removeCallbacksAndMessages(null)
                        longPressRunnable = null
                        longPressRunAt = 0L
                        repeatRunnable = null
                    }
                    if (repeatStarted || !swipeEnabled) {
                        isPressed = false
                    }
                }
                if (!swipeEnabled || longPressTriggered || repeatStarted) return true
                val countX = consumeSwipe(x, SwipeAxis.X)
                val countY = consumeSwipe(y, SwipeAxis.Y)
                dispatchGestureEvent(GestureType.Move, x, y, countX, countY)
                swipeLastX = x
                swipeLastY = y
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dispatchGestureEvent(GestureType.Up, event.x, event.y)
                cancelGestures()
                return true
            }
        }
        return true
    }

    private fun dispatchGestureEvent(
        type: GestureType,
        x: Float,
        y: Float,
        countX: Int = 0,
        countY: Int = 0
    ) {
        val event = Event(type, gestureConsumed, x, y, countX, countY, swipeTotalX, swipeTotalY)
        val consumed = onGestureListener?.onGesture(this, event) ?: return
        if (consumed && !gestureConsumed) {
            gestureConsumed = true
        }
    }

    private fun consumeSwipe(current: Float, axis: SwipeAxis): Int {
        val unconsumed: Float
        val threshold: Float
        when (axis) {
            SwipeAxis.X -> {
                unconsumed = current - swipeLastX + swipeXUnconsumed
                threshold = swipeThresholdX
            }
            SwipeAxis.Y -> {
                unconsumed = current - swipeLastY + swipeYUnconsumed
                threshold = swipeThresholdY
            }
        }
        val remains: Float = unconsumed % threshold
        val count: Int = (unconsumed / threshold).toInt()
        if (count != 0) {
            if (swipeRepeatEnabled && !swipeRepeatTriggered) {
                swipeRepeatTriggered = true
            }
            if ((longPressEnabled && !longPressTriggered) || (repeatEnabled && !repeatStarted)) {
                handler.removeCallbacksAndMessages(null)
                longPressRunnable = null
                longPressRunAt = 0L
                repeatRunnable = null
            }
        }
        when (axis) {
            SwipeAxis.X -> {
                swipeXUnconsumed = remains
                swipeTotalX += count
            }
            SwipeAxis.Y -> {
                swipeYUnconsumed = remains
                swipeTotalY += count
            }
        }
        return count
    }

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        longPressEnabled = l != null
        super.setOnLongClickListener(l)
    }

    companion object {
        val longPressDelay by AppPrefs.getInstance().keyboard.longPressDelay
        const val RepeatInterval = 50L
    }
}
