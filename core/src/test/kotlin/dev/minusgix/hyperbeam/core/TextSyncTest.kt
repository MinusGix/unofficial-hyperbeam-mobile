package dev.minusgix.hyperbeam.core

import dev.minusgix.hyperbeam.core.TextSync.Edit
import kotlin.test.Test
import kotlin.test.assertEquals

class TextSyncTest {
    /** Applies edits to a simulated remote text field, the way a browser would. */
    private class FakeField {
        var text = ""
        var caret = 0 // char index

        fun apply(edits: List<Edit>) {
            for (e in edits) when (e) {
                is Edit.Move -> repeat(kotlin.math.abs(e.delta)) {
                    caret = if (e.delta > 0) nextBoundary(caret) else prevBoundary(caret)
                }
                is Edit.Delete -> repeat(e.count) {
                    val start = prevBoundary(caret)
                    text = text.removeRange(start, caret)
                    caret = start
                }
                is Edit.Insert -> {
                    text = text.substring(0, caret) + e.text + text.substring(caret)
                    caret += e.text.length
                }
            }
        }

        private fun iter() = java.text.BreakIterator.getCharacterInstance().also { it.setText(text) }
        private fun nextBoundary(i: Int) = if (i >= text.length) i else iter().following(i)
        private fun prevBoundary(i: Int) = if (i <= 0) 0 else iter().preceding(i)
    }

    /** Runs a sequence of IME states and checks the remote field ends up identical each step. */
    private fun replay(vararg states: Pair<String, Int>) {
        val sync = TextSync()
        val field = FakeField()
        for ((text, caret) in states) {
            field.apply(sync.update(text, caret))
            assertEquals(text, field.text, "text after reaching \"$text\"")
            assertEquals(caret, field.caret, "caret after reaching \"$text\"")
        }
    }

    private fun at(text: String) = text to text.length

    @Test
    fun typingAppendsOnlyNewCharacters() {
        val s = TextSync()
        assertEquals(listOf(Edit.Insert("h")), s.update("h"))
        assertEquals(listOf(Edit.Insert("i")), s.update("hi"))
        assertEquals(emptyList(), s.update("hi"))
    }

    @Test
    fun autocorrectReplacesOnlyTheChangedSpan() {
        val s = TextSync()
        s.update("teh ")
        // "teh " -> "the ": only "eh" changes; the remote caret walks back over the space.
        assertEquals(
            listOf(Edit.Move(-1), Edit.Delete(2), Edit.Insert("he"), Edit.Move(1)),
            s.update("the "),
        )
    }

    @Test
    fun swipeTypingComposesAndRecomposesAWord() {
        val s = TextSync()
        s.update("go ")
        assertEquals(listOf(Edit.Insert("hello")), s.update("go hello"))
        assertEquals(listOf(Edit.Delete(2), Edit.Insert("p")), s.update("go help"))
    }

    @Test
    fun deletingCountsUserPerceivedCharacters() {
        val s = TextSync()
        s.update("ok 👍🏽")
        assertEquals(listOf(Edit.Delete(1)), s.update("ok "))
    }

    @Test
    fun changingASkinToneRetypesTheWholeEmoji() {
        val s = TextSync()
        s.update("👍🏽")
        assertEquals(listOf(Edit.Delete(1), Edit.Insert("👍🏿")), s.update("👍🏿"))
    }

    @Test
    fun surrogatePairsAreNeverSplit() {
        val s = TextSync()
        s.update("a😀")
        // 😀 (U+1F600) and 😁 (U+1F601) share a high surrogate.
        assertEquals(listOf(Edit.Delete(1), Edit.Insert("😁")), s.update("a😁"))
    }

    @Test
    fun combiningMarksStayWithTheirBase() {
        val s = TextSync()
        s.update("e")
        assertEquals(listOf(Edit.Delete(1), Edit.Insert("é")), s.update("é"))
    }

    @Test
    fun caretMovesBecomeArrowKeys() {
        val s = TextSync()
        s.update("hello")
        assertEquals(listOf(Edit.Move(-3)), s.update("hello", 2))
        assertEquals(listOf(Edit.Insert("y")), s.update("heyllo", 3))
        assertEquals(listOf(Edit.Move(3)), s.update("heyllo", 6))
    }

    @Test
    fun clearingTheBufferDeletesEverything() {
        val s = TextSync()
        s.update("abc")
        assertEquals(listOf(Edit.Delete(3)), s.update(""))
    }

    @Test
    fun resetStartsFresh() {
        val s = TextSync()
        s.update("line one")
        s.reset()
        assertEquals(listOf(Edit.Insert("x")), s.update("x"))
    }

    @Test
    fun graphemeCountHandlesFlagsAndFamilies() {
        assertEquals(1, TextSync.graphemeCount("🇯🇵"))
        assertEquals(1, TextSync.graphemeCount("👨‍👩‍👧"))
        assertEquals(3, TextSync.graphemeCount("abc"))
    }

    @Test
    fun remoteFieldMatchesAcrossARealisticSession() {
        replay(
            at("i"),
            at("i "),
            at("i wnat"),
            at("i want "), // autocorrect
            at("I want "), // auto-capitalise after the fact
            "I want " to 2, // caret slides back on the space bar
            "I really want " to 9,
            at("I really want "),
            at("I really want 🇯🇵"),
            at("I really want 🇯🇵 sushi"),
            at("I really want sushi"), // delete the flag via selection replace
            at(""),
        )
    }

    @Test
    fun remoteFieldMatchesWhenEditingInTheMiddleWithRepeatedLetters() {
        replay(
            at("aaaa"),
            "aaaaa" to 2,
            "aaa" to 1,
            "baaa" to 1,
            at("baaab"),
        )
    }
}
