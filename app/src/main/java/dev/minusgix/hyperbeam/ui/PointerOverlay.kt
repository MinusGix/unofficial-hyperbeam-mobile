package dev.minusgix.hyperbeam.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.minusgix.hyperbeam.core.PointerGestures
import dev.minusgix.hyperbeam.room.RoomSession

/**
 * Sits over the page in mouse mode and turns fingers on the shared screen
 * into mouse input (see [PointerGestures]). Touches anywhere else, like the
 * chat or the page's buttons, fall through to the page underneath.
 */
class PointerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val gestures = PointerGestures(
        out = object : PointerGestures.Output {
            override fun mouse(action: String, x: Float, y: Float, button: Int) {
                RoomSession.bridge?.mouse(action, x, y, button)
            }

            override fun wheel(x: Float, y: Float, deltaX: Float, deltaY: Float) {
                RoomSession.bridge?.wheel(x, y, deltaX, deltaY)
            }
        },
        slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )

    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPress = Runnable {
        if (gestures.pending) {
            gestures.longPress()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val edge = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
        color = Color.argb(110, 124, 92, 255)
    }

    /** The shared screen's picture, in this view's pixels. */
    fun setArea(area: PointerGestures.Area) {
        if (area == gestures.area) return
        gestures.area = area
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val a = gestures.area
        if (!a.valid) return
        // A faint frame so it's clear which part responds as a mouse.
        val h = edge.strokeWidth / 2
        canvas.drawRect(a.left + h, a.top + h, a.left + a.width - h, a.top + a.height - h, edge)
    }

    @SuppressLint("ClickableViewAccessibility") // Mouse input for a remote screen; nothing to "click" locally.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!gestures.down(ev.x, ev.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                gestures.pointerDown(centroidX(ev), centroidY(ev))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-centre on the fingers still down so the scroll doesn't jump.
                gestures.pointerDown(centroidX(ev, ev.actionIndex), centroidY(ev, ev.actionIndex))
            }
            MotionEvent.ACTION_MOVE -> {
                gestures.move(ev.getX(0), ev.getY(0), centroidX(ev), centroidY(ev))
                if (!gestures.pending) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                gestures.up(ev.x, ev.y)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                gestures.cancel()
            }
        }
        return true
    }

    private fun centroidX(ev: MotionEvent, skip: Int = -1) = centroid(ev, skip) { ev.getX(it) }

    private fun centroidY(ev: MotionEvent, skip: Int = -1) = centroid(ev, skip) { ev.getY(it) }

    private inline fun centroid(ev: MotionEvent, skip: Int, at: (Int) -> Float): Float {
        var s = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            s += at(i)
            n++
        }
        return if (n == 0) 0f else s / n
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        gestures.cancel()
        super.onDetachedFromWindow()
    }
}
