package dev.minusgix.hyperbeam.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerGesturesTest {
    private val log = mutableListOf<String>()
    private val out = object : PointerGestures.Output {
        override fun mouse(action: String, x: Float, y: Float, button: Int) {
            log += "$action ${fmt(x)},${fmt(y)}" + if (button != 0) " b$button" else ""
        }

        override fun wheel(x: Float, y: Float, deltaX: Float, deltaY: Float) {
            log += "wheel ${fmt(x)},${fmt(y)} ${fmt(deltaX)},${fmt(deltaY)}"
        }
    }

    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v)

    // A 200x100 picture at (100, 50), showing a 1000x500 remote screen.
    private val g = PointerGestures(out, slop = 10f).apply {
        area = PointerGestures.Area(100f, 50f, 200f, 100f, 1000, 500)
    }

    @Test
    fun tapClicksWhereTheFingerLanded() {
        assertTrue(g.down(150f, 100f))
        g.move(153f, 102f, 153f, 102f) // jitter under the slop
        g.up(153f, 102f)
        assertEquals(listOf("move 0.25,0.50", "down 0.25,0.50", "up 0.25,0.50"), log)
    }

    @Test
    fun touchesOutsideThePictureAreLeftToThePage() {
        assertFalse(g.down(50f, 10f))
        g.move(80f, 10f, 80f, 10f)
        g.up(80f, 10f)
        assertEquals(emptyList(), log)
    }

    @Test
    fun dragPressesAtTheStartAndReleasesAtTheEnd() {
        g.down(100f, 50f)
        g.move(120f, 50f, 120f, 50f)
        g.move(300f, 150f, 300f, 150f)
        g.up(300f, 150f)
        assertEquals(
            listOf("move 0,0", "down 0,0", "move 0.10,0", "move 1,1", "up 1,1"),
            log,
        )
    }

    @Test
    fun dragsPastTheEdgeStayOnTheScreen() {
        g.down(200f, 100f)
        g.move(900f, -40f, 900f, -40f)
        g.up(900f, -40f)
        assertEquals("up 1,0", log.last())
    }

    @Test
    fun longPressRightClicksOnce() {
        g.down(200f, 100f)
        g.longPress()
        g.move(260f, 100f, 260f, 100f)
        g.up(260f, 100f)
        assertEquals(listOf("move 0.50,0.50", "down 0.50,0.50 b2", "up 0.50,0.50 b2"), log)
    }

    @Test
    fun twoFingersScrollInRemotePixels() {
        g.down(200f, 100f)
        g.pointerDown(210f, 100f)
        g.move(200f, 90f, 210f, 90f) // fingers up 10px: scroll down 50 remote px
        g.up(200f, 90f)
        assertEquals(listOf("move 0.50,0.50", "wheel 0.55,0.40 0,50"), log)
    }

    @Test
    fun secondFingerMidDragReleasesTheButton() {
        g.down(200f, 100f)
        g.move(230f, 100f, 230f, 100f)
        g.pointerDown(230f, 100f)
        g.up(230f, 100f)
        assertEquals(listOf("move 0.50,0.50", "down 0.50,0.50", "move 0.65,0.50", "up 0.65,0.50"), log)
    }

    @Test
    fun cancelReleasesAHeldButton() {
        g.down(200f, 100f)
        g.move(230f, 100f, 230f, 100f)
        g.cancel()
        assertEquals("up 0.65,0.50", log.last())
    }

    @Test
    fun withoutAPictureNothingIsCaptured() {
        g.area = PointerGestures.Area(0f, 0f, 0f, 0f)
        assertFalse(g.down(10f, 10f))
    }
}
