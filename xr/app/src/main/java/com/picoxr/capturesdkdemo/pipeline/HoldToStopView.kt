package com.picoxr.capturesdkdemo.pipeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

/** A continuous 2.5-second press, never a short click or a stale delayed action. */
class HoldToStopView(context: Context, private val stopRecording: () -> Unit, private val progressChanged: () -> Unit = {}) : View(context) {
    companion object { const val HOLD_MS = 2_500L }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private var pressedAt = -1L
    private var pointerId = -1
    private var progress = 0f
    private var completed = false
    private val tick = object : Runnable {
        override fun run() {
            if (pressedAt < 0 || !isEnabled || !isShown) { cancelHold(); return }
            progress = ((SystemClock.elapsedRealtime() - pressedAt).toFloat() / HOLD_MS).coerceIn(0f, 1f)
            invalidate(); progressChanged()
            if (progress >= 1f) {
                pressedAt = -1; completed = true; pointerId = -1
                contentDescription = "正在停止并保存"
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
                stopRecording()
            } else postOnAnimation(this)
        }
    }
    init {
        isFocusable = true
        isClickable = true
        contentDescription = "按住圆圈2.5 秒停止并保存，提前松开取消"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
    private fun inside(x: Float, y: Float): Boolean =
        hypot(x - width / 2f, y - height / 2f) <= minOf(width, height) * .47f
    private fun beginHold() {
        if (!isEnabled || completed || pressedAt >= 0) return
        pressedAt = SystemClock.elapsedRealtime(); progress = 0f
        postOnAnimation(tick); invalidate(); progressChanged()
    }
    fun cancelHold() {
        removeCallbacks(tick); pressedAt = -1; pointerId = -1; progress = 0f; invalidate(); progressChanged()
    }
    fun reset() {
        cancelHold(); completed = false
        contentDescription = "按住圆圈2.5 秒停止并保存，提前松开取消"
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled || !inside(event.x, event.y)) return false
                pointerId = event.getPointerId(0); parent?.requestDisallowInterceptTouchEvent(true)
                beginHold(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(pointerId)
                if (i < 0 || !inside(event.getX(i), event.getY(i))) cancelHold()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { cancelHold(); return true }
            MotionEvent.ACTION_UP -> { cancelHold(); parent?.requestDisallowInterceptTouchEvent(false); return true }
            MotionEvent.ACTION_CANCEL -> { cancelHold(); parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE)) {
            if (event.repeatCount == 0) beginHold()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE)) { cancelHold(); return true }
        return super.onKeyUp(keyCode, event)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) cancelHold() }
    override fun onDetachedFromWindow() { cancelHold(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val radius = minOf(width, height) * .42f
        paint.style = Paint.Style.FILL; paint.color = Color.argb(110, 12, 16, 22)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density * 3
        paint.color = Color.rgb(245, 106, 102); canvas.drawCircle(cx, cy, radius, paint)
        if (progress > 0) {
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
            paint.strokeWidth = resources.displayMetrics.density * 5; paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE; canvas.drawArc(arc, -90f, progress * 360f, false, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        val side = radius * .35f
        canvas.drawRoundRect(cx - side, cy - side, cx + side, cy + side, side * .2f, side * .2f, paint)
    }
}
