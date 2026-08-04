package com.echolink.mobile

data class LocalRewriteChunk(
    val text: String,
    val separatorAfter: String,
)

/**
 * Keeps paragraph, line, sentence, and word boundaries byte-for-byte while
 * presenting bounded text blocks to the local model.
 */
object LocalRewriteChunker {
    private val paragraphBreak = Regex("(?:\\r?\\n[\\t ]*){2,}")
    private val sentenceBreak = Regex("[.!?][\"')\\]]?([\\t ]+)")
    private val lineBreak = Regex("\\r?\\n")
    private val wordBreak = Regex("[\\t ]+")

    fun split(source: String, maximumCharacters: Int): List<LocalRewriteChunk> {
        require(maximumCharacters >= 128)
        val paragraphSeparators = paragraphBreak.findAll(source).toList()
        if (paragraphSeparators.isEmpty()) return splitBlock(source, maximumCharacters)

        val result = mutableListOf<LocalRewriteChunk>()
        var start = 0
        paragraphSeparators.forEach { separator ->
            appendBlock(
                result = result,
                text = source.substring(start, separator.range.first),
                separatorAfter = separator.value,
                maximumCharacters = maximumCharacters,
            )
            start = separator.range.last + 1
        }
        appendBlock(
            result = result,
            text = source.substring(start),
            separatorAfter = "",
            maximumCharacters = maximumCharacters,
        )
        return result
    }

    private fun appendBlock(
        result: MutableList<LocalRewriteChunk>,
        text: String,
        separatorAfter: String,
        maximumCharacters: Int,
    ) {
        val blocks = splitBlock(text, maximumCharacters)
        if (blocks.isEmpty()) {
            result += LocalRewriteChunk("", separatorAfter)
            return
        }
        result += blocks.dropLast(1)
        result += blocks.last().copy(separatorAfter = separatorAfter)
    }

    private fun splitBlock(
        source: String,
        maximumCharacters: Int,
    ): List<LocalRewriteChunk> {
        if (source.isEmpty()) return emptyList()
        if (source.length <= maximumCharacters) return listOf(LocalRewriteChunk(source, ""))

        val result = mutableListOf<LocalRewriteChunk>()
        var start = 0
        while (source.length - start > maximumCharacters) {
            val window = source.substring(start, start + maximumCharacters)
            val minimumBoundary = maximumCharacters / 3
            val boundary =
                sentenceBoundary(window, minimumBoundary)
                    ?: plainBoundary(window, lineBreak, minimumBoundary)
                    ?: plainBoundary(window, wordBreak, minimumBoundary)

            if (boundary == null) {
                result += LocalRewriteChunk(source.substring(start), "")
                start = source.length
            } else {
                result += LocalRewriteChunk(
                    source.substring(start, start + boundary.first),
                    source.substring(
                        start + boundary.first,
                        start + boundary.second,
                    ),
                )
                start += boundary.second
            }
        }
        if (start < source.length) {
            result += LocalRewriteChunk(source.substring(start), "")
        }
        return result
    }

    private fun sentenceBoundary(value: String, minimum: Int): Pair<Int, Int>? {
        val match = sentenceBreak.findAll(value)
            .lastOrNull { it.range.first >= minimum }
            ?: return null
        val spaces = match.groupValues[1]
        val separatorStart = match.range.last + 1 - spaces.length
        return separatorStart to (match.range.last + 1)
    }

    private fun plainBoundary(
        value: String,
        expression: Regex,
        minimum: Int,
    ): Pair<Int, Int>? {
        val match = expression.findAll(value)
            .lastOrNull { it.range.first >= minimum }
            ?: return null
        return match.range.first to (match.range.last + 1)
    }
}
