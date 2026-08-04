package com.echolink.mobile

import java.text.Normalizer

enum class LineEnding(val value: String) {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r"),
}

data class NormalizedSource(
    val text: String,
    val lineEndings: List<LineEnding>,
) {
    fun restoreLineEndings(candidate: String): String {
        require(candidate.count { it == '\n' } == lineEndings.size) {
            "AI changed the document line count."
        }
        val restored = StringBuilder(candidate.length + lineEndings.size)
        var lineEndingIndex = 0
        candidate.forEach { character ->
            if (character == '\n') {
                restored.append(lineEndings[lineEndingIndex++].value)
            } else {
                restored.append(character)
            }
        }
        return restored.toString()
    }
}

object LosslessSourceNormalizer {
    fun normalize(source: String): NormalizedSource {
        val endings = mutableListOf<LineEnding>()
        val normalizedLines = buildString(source.length) {
            var index = 0
            while (index < source.length) {
                val character = source[index]
                when {
                    character == '\r' &&
                        index + 1 < source.length &&
                        source[index + 1] == '\n' -> {
                        endings += LineEnding.CRLF
                        append('\n')
                        index += 2
                    }
                    character == '\r' -> {
                        endings += LineEnding.CR
                        append('\n')
                        index += 1
                    }
                    character == '\n' -> {
                        endings += LineEnding.LF
                        append('\n')
                        index += 1
                    }
                    character == '\t' ||
                        Character.getType(character) != Character.CONTROL.toInt() -> {
                        append(character)
                        index += 1
                    }
                    else -> index += 1
                }
            }
        }
        return NormalizedSource(
            text = Normalizer.normalize(normalizedLines, Normalizer.Form.NFC),
            lineEndings = endings,
        )
    }
}

enum class ProtectedSpanType {
    MARKDOWN_PREFIX,
    CODE,
    QUOTATION,
    DOMAIN_TERM,
    URL,
    EMAIL,
    FILE_PATH,
    USERNAME,
    DATE,
    TIME,
    DEVICE_MODEL,
    MEASUREMENT,
    NUMBER,
    ABBREVIATION,
    NAME,
}

data class ProtectedSpan(
    val index: Int,
    val type: ProtectedSpanType,
    val token: String,
    val original: String,
)

class ProtectedSpanViolation(message: String) : IllegalArgumentException(message)

data class ProtectedDocument(
    val encodedText: String,
    val placeholders: List<ProtectedSpan>,
) {
    fun restore(candidate: String): String {
        val actualTokens = PLACEHOLDER.findAll(candidate).map(MatchResult::value).toList()
        val expectedTokens = placeholders.map(ProtectedSpan::token)
        if (actualTokens != expectedTokens) {
            throw ProtectedSpanViolation(
                "AI changed the identity, order, or count of protected text.",
            )
        }
        var restored = candidate
        placeholders.forEach { span ->
            restored = restored.replace(span.token, span.original)
        }
        if (PLACEHOLDER.containsMatchIn(restored)) {
            throw ProtectedSpanViolation("AI returned an unknown protected placeholder.")
        }
        return restored
    }

    companion object {
        internal val PLACEHOLDER = Regex("\\[\\[MS_[A-Z_]+_\\d{4}]]")
    }
}

object ProtectedSpanCodec {
    private data class PatternSpec(
        val type: ProtectedSpanType,
        val regex: Regex,
    )

    private data class SourceMatch(
        val start: Int,
        val endExclusive: Int,
        val type: ProtectedSpanType,
        val value: String,
        val priority: Int,
    )

    private val patterns = listOf(
        PatternSpec(
            ProtectedSpanType.CODE,
            Regex("(?s)```.*?```|`[^`\\n]+`"),
        ),
        PatternSpec(
            ProtectedSpanType.QUOTATION,
            Regex("\"[^\"\\n]*\"|“[^”\\n]*”"),
        ),
        PatternSpec(
            ProtectedSpanType.DOMAIN_TERM,
            Regex(
                "(?i)\\b(?:digital-access|full-device|sign-in|check-in|on-device|" +
                    "local-only)\\b",
            ),
        ),
        PatternSpec(
            ProtectedSpanType.MARKDOWN_PREFIX,
            Regex("(?m)^[ \\t]*(?:#{1,6}[ \\t]+|[-*+][ \\t]+|\\d+[.)][ \\t]+)"),
        ),
        PatternSpec(
            ProtectedSpanType.URL,
            Regex(
                "(?i)\\bhttps?://[^\\s<>\"']*" +
                    "[^\\s<>\"'.,;:!?\\)\\]\\}]",
            ),
        ),
        PatternSpec(
            ProtectedSpanType.EMAIL,
            Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"),
        ),
        PatternSpec(
            ProtectedSpanType.FILE_PATH,
            Regex(
                "(?:\\b[A-Za-z]:\\\\(?:[^\\\\\\s]+\\\\)*[^\\\\\\s]+)|" +
                    "(?<![A-Za-z0-9:])/(?:[^/\\s]+/)*[^/\\s]+",
            ),
        ),
        PatternSpec(
            ProtectedSpanType.USERNAME,
            Regex("(?<![A-Za-z0-9_])@[A-Za-z0-9_]+"),
        ),
        PatternSpec(
            ProtectedSpanType.DATE,
            Regex(
                "\\b(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|" +
                    "January|February|March|April|May|June|July|August|September|" +
                    "October|November|December)(?:[ \\t]+\\d{1,2})?(?:,[ \\t]+\\d{4})?\\b|" +
                    "\\b\\d{1,4}[-/]\\d{1,2}[-/]\\d{1,4}\\b",
            ),
        ),
        PatternSpec(
            ProtectedSpanType.TIME,
            Regex("(?i)\\b\\d{1,2}:\\d{2}(?:[ \\t]*(?:a\\.?m\\.?|p\\.?m\\.?))?\\b"),
        ),
        PatternSpec(
            ProtectedSpanType.DEVICE_MODEL,
            Regex("\\b(?=[A-Z0-9-]*\\d)[A-Z]{1,6}-?[A-Z0-9-]{2,}\\b"),
        ),
        PatternSpec(
            ProtectedSpanType.NUMBER,
            Regex("(?<![\\p{L}\\p{N}_])(?:\\$|\\u20AC|\\u00A3)[ \\t]*\\d+(?:[.,]\\d+)*\\b"),
        ),
        PatternSpec(
            ProtectedSpanType.MEASUREMENT,
            Regex(
                "(?i)\\b\\d+(?:[.,]\\d+)?[ \\t]*(?:%|GB|MB|KB|TB|kg|g|lb|oz|km|m|cm|" +
                    "mm|mi|ft|in|°C|°F|ms|sec|seconds?|minutes?|hours?)\\b",
            ),
        ),
        PatternSpec(
            ProtectedSpanType.ABBREVIATION,
            Regex("(?i)\\b(?:[A-Z]\\.){2,}|\\b(?:a\\.m\\.|p\\.m\\.)"),
        ),
        PatternSpec(
            ProtectedSpanType.NAME,
            Regex(
                "\\b(?!(?:A|An|After|Although|Before|Each|Every|If|On|Only|Page|The|" +
                    "That|These|This|Those|When|While)[ \\t]+)" +
                    "[A-Z][a-z]+(?:[ \\t]+[A-Z][a-z]+){1,3}\\b",
            ),
        ),
    )

    fun protect(source: String): ProtectedDocument {
        val staticCandidates = patterns.flatMapIndexed { priority, spec ->
            spec.regex.findAll(source)
                .filter { match ->
                    spec.type != ProtectedSpanType.NAME ||
                        Regex("[A-Z][a-z]+").findAll(match.value).count() <= MAX_NAME_WORDS
                }
                .map { match ->
                SourceMatch(
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                    type = spec.type,
                    value = match.value,
                    priority = priority,
                )
                }.toList()
        }
        val fullNames = patterns
            .first { it.type == ProtectedSpanType.NAME }
            .regex
            .findAll(source)
            .map(MatchResult::value)
            .filter { value ->
                Regex("[A-Z][a-z]+").findAll(value).count() <= MAX_NAME_WORDS
            }
            .toList()
        val nameComponents = fullNames
            .flatMap { name -> Regex("[A-Z][a-z]+").findAll(name).map(MatchResult::value) }
            .filter { it.length >= 3 }
            .distinct()
        val referencedNameCandidates = nameComponents.flatMap { name ->
            Regex(
                "(?<![\\p{L}\\p{N}_])${Regex.escape(name)}(?![\\p{L}\\p{N}_])",
            ).findAll(source).map { match ->
                SourceMatch(
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                    type = ProtectedSpanType.NAME,
                    value = match.value,
                    priority = patterns.size,
                )
            }.toList()
        }
        val candidates = (staticCandidates + referencedNameCandidates).sortedWith(
            compareBy<SourceMatch> { it.start }
                .thenBy { it.priority }
                .thenByDescending { it.endExclusive - it.start },
        )

        val selected = mutableListOf<SourceMatch>()
        candidates.forEach { candidate ->
            if (selected.none { existing ->
                    candidate.start < existing.endExclusive &&
                        candidate.endExclusive > existing.start
                }
            ) {
                selected += candidate
            }
        }
        selected.sortBy(SourceMatch::start)

        val placeholders = mutableListOf<ProtectedSpan>()
        val encoded = StringBuilder(source.length)
        var cursor = 0
        selected.forEachIndexed { index, match ->
            encoded.append(source, cursor, match.start)
            val tokenLabel = if (match.type == ProtectedSpanType.NUMBER) {
                "LOCK"
            } else {
                match.type.name
            }
            val token = "[[MS_${tokenLabel}_${index.toString().padStart(4, '0')}]]"
            encoded.append(token)
            placeholders += ProtectedSpan(
                index = index,
                type = match.type,
                token = token,
                original = match.value,
            )
            cursor = match.endExclusive
        }
        encoded.append(source, cursor, source.length)
        return ProtectedDocument(encoded.toString(), placeholders)
    }

    private const val MAX_NAME_WORDS = 3
}

data class ParagraphChunk(
    val index: Int,
    val text: String,
    val separatorAfter: String,
    val previousContext: String?,
    val nextContext: String?,
) {
    val wordCount: Int
        get() = WORD.findAll(text).count()

    companion object {
        private val WORD = Regex("\\S+")
    }
}

object ParagraphChunker {
    private const val MIN_WORDS = 100
    private const val MAX_WORDS = 180
    private val paragraphSeparator = Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)*")
    private val word = Regex("\\S+")

    fun chunk(source: String): List<ParagraphChunk> {
        if (source.isEmpty()) return emptyList()
        val raw = mutableListOf<Pair<String, String>>()
        var cursor = 0
        paragraphSeparator.findAll(source).forEach { separator ->
            val paragraph = source.substring(cursor, separator.range.first)
            appendParagraphPieces(raw, paragraph, separator.value)
            cursor = separator.range.last + 1
        }
        if (cursor <= source.length) {
            appendParagraphPieces(raw, source.substring(cursor), "")
        }
        return raw.mapIndexed { index, (text, separator) ->
            ParagraphChunk(
                index = index + 1,
                text = text,
                separatorAfter = separator,
                previousContext = raw.getOrNull(index - 1)?.first,
                nextContext = raw.getOrNull(index + 1)?.first,
            )
        }
    }

    private fun appendParagraphPieces(
        destination: MutableList<Pair<String, String>>,
        paragraph: String,
        paragraphSeparatorAfter: String,
    ) {
        val pieces = splitLongParagraph(paragraph).toMutableList()
        if (pieces.isEmpty()) {
            destination += "" to paragraphSeparatorAfter
            return
        }
        pieces.forEachIndexed { index, piece ->
            destination += piece.first to if (index == pieces.lastIndex) {
                piece.second + paragraphSeparatorAfter
            } else {
                piece.second
            }
        }
    }

    private fun splitLongParagraph(paragraph: String): List<Pair<String, String>> {
        if (word.findAll(paragraph).count() <= MAX_WORDS) {
            return listOf(separateTrailingWhitespace(paragraph))
        }
        val pieces = mutableListOf<Pair<String, String>>()
        var remaining = paragraph
        while (word.findAll(remaining).count() > MAX_WORDS) {
            val words = word.findAll(remaining).toList()
            val chunksNeeded =
                (words.size + MAX_WORDS - 1) / MAX_WORDS
            val targetWordCount =
                ((words.size + chunksNeeded - 1) / chunksNeeded)
                    .coerceIn(MIN_WORDS, MAX_WORDS)
            val minimumCharacter = words[MIN_WORDS - 1].range.last + 1
            val maximumCharacter = words[targetWordCount].range.first
            val preferred = Regex("[;:\\n](?:[ \\t]+)?")
                .findAll(remaining.substring(minimumCharacter, maximumCharacter))
                .lastOrNull()
                ?.let { match -> minimumCharacter + match.range.last + 1 }
            val splitAt = preferred ?: maximumCharacter
            val prefix = remaining.substring(0, splitAt)
            val trailingWhitespace = Regex("\\s+$").find(prefix)?.range?.first ?: prefix.length
            val text = prefix.substring(0, trailingWhitespace)
            val separator = prefix.substring(trailingWhitespace)
            pieces += text to separator
            remaining = remaining.substring(splitAt)
        }
        pieces += separateTrailingWhitespace(remaining)
        return pieces
    }

    private fun separateTrailingWhitespace(value: String): Pair<String, String> {
        val start = Regex("\\s+$").find(value)?.range?.first ?: value.length
        return value.substring(0, start) to value.substring(start)
    }
}
