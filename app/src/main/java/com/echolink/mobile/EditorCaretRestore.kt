package com.echolink.mobile

/**
 * Where the cursor belongs after a correction rewrites the document.
 *
 * The GUI build manual requires cursor and scroll position to survive a
 * correction. Applying one replaced the whole editor text, which sends the
 * caret to offset 0 and the view to the top: accept a fix on page four of a
 * document and you are thrown back to page one, with nothing to say why.
 *
 * The rule is deliberately dull, because a clever guess that is wrong is worse
 * than a dull one that is predictable:
 *
 * - Text before the caret that did not change does not move the caret.
 * - A correction that lands before the caret shifts it by the length it
 *   changed, so the caret stays with the words it was sitting in.
 * - The result is always inside the new text.
 */
object EditorCaretRestore {
    fun offsetAfter(oldText: String, newText: String, caret: Int): Int {
        if (caret <= 0) return 0
        val safeCaret = caret.coerceAtMost(oldText.length)
        val prefix = commonPrefixLength(oldText, newText)
        // The caret sits in text the correction never reached, so it does not
        // move at all.
        if (safeCaret <= prefix) return safeCaret.coerceAtMost(newText.length)
        // The change was behind the caret. Carry the caret by the amount the
        // document grew or shrank, and never past either end.
        val shifted = safeCaret + (newText.length - oldText.length)
        return shifted.coerceIn(prefix.coerceAtMost(newText.length), newText.length)
    }

    private fun commonPrefixLength(first: String, second: String): Int {
        val limit = minOf(first.length, second.length)
        var index = 0
        while (index < limit && first[index] == second[index]) index++
        return index
    }
}
