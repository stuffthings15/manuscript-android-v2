package com.echolink.mobile

/**
 * Pure text-splice logic for inserting a dictated transcript into the editor.
 * Kept free of Android types so the JVM suite can verify every boundary case.
 */
object DictationInsertion {
    data class Result(val text: String, val cursor: Int)

    /**
     * Cleans the raw transcript with the shared deterministic dictation
     * cleanup, then splices it over the current selection with sane spacing.
     */
    fun insert(
        existing: String,
        selectionStart: Int,
        selectionEnd: Int,
        rawTranscript: String,
        vocabulary: Map<String, String> = emptyMap(),
    ): Result {
        val cleaned = DeterministicCleanup.clean(rawTranscript, vocabulary).text.trim()
        if (cleaned.isEmpty()) {
            return Result(existing, selectionEnd.coerceIn(0, existing.length))
        }
        val start = selectionStart.coerceIn(0, existing.length)
        val end = selectionEnd.coerceIn(start, existing.length)
        val before = existing.substring(0, start)
        val after = existing.substring(end)
        val prefix = if (
            before.isNotEmpty() && !before.last().isWhitespace()
        ) {
            " "
        } else {
            ""
        }
        val suffix = if (
            after.isNotEmpty() && !after.first().isWhitespace()
        ) {
            " "
        } else {
            ""
        }
        val inserted = prefix + cleaned + suffix
        val text = before + inserted + after
        return Result(text, (before + prefix + cleaned).length)
    }
}
