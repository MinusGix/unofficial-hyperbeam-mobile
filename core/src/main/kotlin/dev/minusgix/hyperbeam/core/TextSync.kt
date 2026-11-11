package dev.minusgix.hyperbeam.core

import java.text.BreakIterator

/**
 * Keeps a remote text field in step with a local IME buffer.
 *
 * Soft keyboards don't type one key at a time: they compose words, autocorrect
 * them, replace whole spans, and move the caret (e.g. sliding on the space bar).
 * The remote browser only understands key presses. After every IME change we
 * diff what we last sent against the new buffer and emit the key presses that
 * turn one into the other: arrow moves to reach the changed span, Backspace
 * for removed characters, then the inserted text, then arrows to put the caret
 * where the keyboard thinks it is.
 *
 * Counts are in user-perceived characters (grapheme clusters), which is what a
 * single Backspace or arrow press moves over in a browser text field.
 */
class TextSync {
    sealed interface Edit {
        /** Press ArrowRight (positive) or ArrowLeft (negative) |delta| times. */
        data class Move(val delta: Int) : Edit

        /** Press Backspace [count] times. */
        data class Delete(val count: Int) : Edit

        /** Type [text]. */
        data class Insert(val text: String) : Edit
    }

    /** What the remote side currently holds, as far as we know. */
    var sent: String = ""
        private set

    /** Where the remote caret is, as a char index into [sent]. */
    var remoteCaret: Int = 0
        private set

    /**
     * Diff [current] (with the local caret at [caret], a char index) against
     * what was sent, update our model of the remote field, and return the
     * key presses to apply, in order.
     */
    fun update(current: String, caret: Int = current.length): List<Edit> {
        val edits = ArrayList<Edit>(4)
        if (current != sent) {
            val old = sent
            var p = commonPrefixLength(old, current)
            p = alignDown(old, current, p)
            var s = commonSuffixLength(old, current, p)
            s = alignSuffix(old, current, s)
            val oldEnd = old.length - s
            val newEnd = current.length - s

            addMove(edits, old, remoteCaret, oldEnd)
            val removed = graphemeCount(old.substring(p, oldEnd))
            if (removed > 0) edits += Edit.Delete(removed)
            val inserted = current.substring(p, newEnd)
            if (inserted.isNotEmpty()) edits += Edit.Insert(inserted)

            sent = current
            remoteCaret = newEnd
        }
        val target = caret.coerceIn(0, current.length)
        if (target != remoteCaret) {
            addMove(edits, current, remoteCaret, target)
            remoteCaret = target
        }
        return edits
    }

    /** Forget the buffer, e.g. after Enter, when the remote field is likely cleared. */
    fun reset() {
        sent = ""
        remoteCaret = 0
    }

    /** Record a remote state that was reached outside of [update]. */
    fun assumeRemote(text: String, caret: Int = text.length) {
        sent = text
        remoteCaret = caret.coerceIn(0, text.length)
    }

    private fun addMove(edits: MutableList<Edit>, text: String, from: Int, to: Int) {
        if (from == to) return
        val delta = if (to > from) {
            graphemeCount(text.substring(from, to))
        } else {
            -graphemeCount(text.substring(to, from))
        }
        if (delta != 0) edits += Edit.Move(delta)
    }

    companion object {
        fun commonPrefixLength(a: String, b: String): Int {
            val max = minOf(a.length, b.length)
            var i = 0
            while (i < max && a[i] == b[i]) i++
            return i
        }

        /** Common suffix that doesn't overlap the first [prefix] chars of either string. */
        fun commonSuffixLength(a: String, b: String, prefix: Int): Int {
            val max = minOf(a.length, b.length) - prefix
            var i = 0
            while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
            return i
        }

        /** Move a prefix length back until it's a grapheme boundary in both strings. */
        fun alignDown(a: String, b: String, prefix: Int): Int {
            var p = prefix
            while (p > 0 && (!isBoundary(a, p) || !isBoundary(b, p))) p--
            return p
        }

        /** Shrink a suffix length until its start is a grapheme boundary in both strings. */
        fun alignSuffix(a: String, b: String, suffix: Int): Int {
            var s = suffix
            while (s > 0 && (!isBoundary(a, a.length - s) || !isBoundary(b, b.length - s))) s--
            return s
        }

        private fun isBoundary(s: String, index: Int): Boolean {
            if (index <= 0 || index >= s.length) return true
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            return it.isBoundary(index)
        }

        fun graphemeCount(s: String): Int {
            if (s.isEmpty()) return 0
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            var count = 0
            while (it.next() != BreakIterator.DONE) count++
            return count
        }
    }
}
