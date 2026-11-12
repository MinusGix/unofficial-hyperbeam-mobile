package dev.minusgix.hyperbeam.core

import kotlin.math.hypot

/**
 * Turns finger gestures into mouse input for the shared browser.
 *
 * Touches on the page reach the remote browser as touch events, so a drag
 * scrolls instead of selecting text. In mouse mode the app maps fingers to a
 * mouse instead:
 *
 * - tap: left click (two quick taps make a double click, which selects a word)
 * - drag: press, move, release (selects text, drags sliders and windows)
 * - long press: right click
 * - two fingers: scroll wheel
 *
 * Positions come in as screen pixels and leave normalised 0..1 over the
 * shared screen, via [area].
 */
class PointerGestures(
    private val out: Output,
    /** How far a finger must travel (px) before a press becomes a drag. */
    private val slop: Float,
) {
    interface Output {
        fun mouse(action: String, x: Float, y: Float, button: Int)
        fun wheel(x: Float, y: Float, deltaX: Float, deltaY: Float)
    }

    /** The shared screen's picture on the device screen, plus its size in remote pixels. */
    data class Area(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val remoteWidth: Int? = null,
        val remoteHeight: Int? = null,
    ) {
        val valid: Boolean get() = width > 1f && height > 1f

        fun contains(x: Float, y: Float) = valid && x >= left && x <= left + width && y >= top && y <= top + height

        fun nx(x: Float) = ((x - left) / width).coerceIn(0f, 1f)
        fun ny(y: Float) = ((y - top) / height).coerceIn(0f, 1f)

        /** Remote pixels per screen pixel, so scrolling feels like moving the page under the fingers. */
        val scale: Float
            get() = remoteWidth?.takeIf { it > 0 && width > 0f }?.let { it / width } ?: 1f
    }

    private enum class State { IDLE, PENDING, DRAGGING, SCROLLING, DONE }

    var area: Area = Area(0f, 0f, 0f, 0f)

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** Whether a gesture is in progress (so a pending long press still matters). */
    val pending: Boolean get() = state == State.PENDING

    /** First finger down. False when it's outside the shared screen: let the page have it. */
    fun down(x: Float, y: Float): Boolean {
        if (!area.contains(x, y)) {
            state = State.IDLE
            return false
        }
        state = State.PENDING
        downX = x
        downY = y
        lastX = x
        lastY = y
        // Hover first, like a real mouse arriving at the spot.
        out.mouse("move", area.nx(x), area.ny(y), 0)
        return true
    }

    /** Another finger joined; [cx], [cy] is the centre of all of them. */
    fun pointerDown(cx: Float, cy: Float) {
        when (state) {
            State.IDLE, State.DONE -> return
            State.DRAGGING -> out.mouse("up", area.nx(lastX), area.ny(lastY), 0)
            else -> {}
        }
        state = State.SCROLLING
        lastCx = cx
        lastCy = cy
    }

    /** Fingers moved: ([x], [y]) is the first finger, ([cx], [cy]) the centre of all of them. */
    fun move(x: Float, y: Float, cx: Float, cy: Float) {
        when (state) {
            State.PENDING -> {
                if (hypot(x - downX, y - downY) < slop) return
                out.mouse("down", area.nx(downX), area.ny(downY), 0)
                state = State.DRAGGING
                out.mouse("move", area.nx(x), area.ny(y), 0)
            }
            State.DRAGGING -> out.mouse("move", area.nx(x), area.ny(y), 0)
            State.SCROLLING -> {
                val dx = cx - lastCx
                val dy = cy - lastCy
                if (dx == 0f && dy == 0f) return
                lastCx = cx
                lastCy = cy
                // Content follows the fingers: moving them up scrolls down.
                val s = area.scale
                out.wheel(area.nx(cx), area.ny(cy), -dx * s, -dy * s)
            }
            else -> {}
        }
        lastX = x
        lastY = y
    }

    /** The finger stayed put long enough: right click. */
    fun longPress() {
        if (state != State.PENDING) return
        out.mouse("down", area.nx(downX), area.ny(downY), 2)
        out.mouse("up", area.nx(downX), area.ny(downY), 2)
        state = State.DONE
    }

    /** Last finger lifted at ([x], [y]). */
    fun up(x: Float, y: Float) {
        when (state) {
            State.PENDING -> {
                out.mouse("down", area.nx(downX), area.ny(downY), 0)
                out.mouse("up", area.nx(downX), area.ny(downY), 0)
            }
            State.DRAGGING -> out.mouse("up", area.nx(x), area.ny(y), 0)
            else -> {}
        }
        state = State.IDLE
    }

    /** The system took the gesture away; release anything held. */
    fun cancel() {
        if (state == State.DRAGGING) out.mouse("up", area.nx(lastX), area.ny(lastY), 0)
        state = State.IDLE
    }
}
