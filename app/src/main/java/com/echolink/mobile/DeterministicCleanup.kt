package com.echolink.mobile

import java.util.Locale

data class CleanupResult(
    val text: String,
    val source: String = "deterministic",
    val changed: Boolean,
)

data class ProtectedToken(
    val token: String,
    val value: String,
)

data class ProtectedText(
    val text: String,
    val tokens: List<ProtectedToken>,
)

/**
 * Pure, bounded, on-device speech cleanup shared by the Android app and contract adapter.
 *
 * This intentionally ports only stable deterministic behavior. It performs no model call,
 * file access, process launch, or network operation.
 */
object DeterministicCleanup {
    private const val MAX_TEXT_LENGTH = 200_000
    private const val MAX_VOCABULARY_SIZE = 1_000

    private val questionStart = Regex(
        "^(?:who|what|when|where|why|how|can|could|would|should|will|do|does|did|" +
            "is|are|am|was|were|have|has|had)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val protectedPattern = Regex(
        "(?:\"(?:[^\"\\\\]|\\\\.)*\"|“[^”]*”|" +
            "\\b\\d{1,2}(?::\\d{2})?\\s*(?:a\\.m\\.|p\\.m\\.)|" +
            "https?://\\S+|www\\.\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|" +
            "(?:[A-Za-z]:\\\\|/)[^\\s]+|\\b[\\w-]+\\.[A-Za-z0-9]{1,10}\\b|" +
            "\\b\\d+(?:[.,:/-]\\d+)*\\b)",
    )
    private val semanticWord = Regex("[A-Za-z0-9][A-Za-z0-9'_-]*")
    private val numberedListMarkers = linkedMapOf(
        "one" to 1,
        "first" to 1,
        "two" to 2,
        "second" to 2,
        "three" to 3,
        "third" to 3,
        "four" to 4,
        "fourth" to 4,
        "five" to 5,
        "fifth" to 5,
        "six" to 6,
        "sixth" to 6,
        "seven" to 7,
        "seventh" to 7,
        "eight" to 8,
        "eighth" to 8,
        "nine" to 9,
        "ninth" to 9,
        "ten" to 10,
        "tenth" to 10,
    )

    fun clean(
        text: String,
        vocabulary: Map<String, String> = emptyMap(),
        appendTrailingSpace: Boolean = false,
    ): CleanupResult {
        validateInput(text, vocabulary)
        var result = stripRepetitions(text)
        result = normalizeSpokenPunctuation(result)
        result = applySelfCorrections(result)
        result = removeFillers(result)
        result = applyVocabulary(result, vocabulary)

        val protected = protect(result, vocabulary.values)
        result = inferSentenceBoundaries(protected.text)
        result = formatSentences(result)
        result = restore(result, protected.tokens)
        result = Regex("\\.{2,}(?=\\s|$)").replace(result, ".")
        result = formatAutomaticList(result)
        if (appendTrailingSpace && result.isNotEmpty() && !result.endsWith(' ')) {
            result += " "
        }
        return CleanupResult(result, changed = result != text)
    }

    fun protect(text: String, protectedTerms: Collection<String> = emptyList()): ProtectedText {
        require(text.length <= MAX_TEXT_LENGTH) { "Text is too long." }
        val candidates = linkedSetOf<String>()
        protectedTerms.filterTo(candidates) { it.isNotEmpty() }
        protectedPattern.findAll(text).forEach { candidates += it.value }
        val ordered = candidates.withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { it.value.length }.thenBy { it.index })
            .map { it.value }

        val tokens = ordered.mapIndexedNotNull { index, value ->
            val pattern = Regex(Regex.escape(value), RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(text)) ProtectedToken("ECHOLINKTOKEN${index}X", value) else null
        }
        val matchableTokens = tokens.distinctBy { it.value.lowercase(Locale.ROOT) }
        val combined = matchableTokens.takeIf { it.isNotEmpty() }?.let { active ->
            Regex(active.joinToString("|") { Regex.escape(it.value) }, RegexOption.IGNORE_CASE)
        }
        val protectedText = combined?.replace(text) { match ->
            matchableTokens.first { it.value.equals(match.value, ignoreCase = true) }.token
        } ?: text
        return ProtectedText(protectedText, tokens)
    }

    fun restore(text: String, tokens: Collection<ProtectedToken>): String {
        var result = text
        tokens.forEach { result = result.replace(it.token, it.value) }
        return result
    }

    private fun validateInput(text: String, vocabulary: Map<String, String>) {
        require(text.length <= MAX_TEXT_LENGTH) { "Text is too long." }
        require(vocabulary.size <= MAX_VOCABULARY_SIZE) { "Vocabulary is too large." }
        vocabulary.forEach { (spoken, replacement) ->
            require(spoken.isNotEmpty() && spoken.length <= 200) { "Vocabulary key is invalid." }
            require(replacement.isNotEmpty() && replacement.length <= 500) {
                "Vocabulary value is invalid."
            }
        }
    }

    private fun stripRepetitions(text: String): String {
        var words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        words = words.fold(mutableListOf()) { output, word ->
            if (output.lastOrNull()?.equals(word, ignoreCase = true) != true) output += word
            output
        }
        for (size in 2..6) {
            var index = 0
            val output = mutableListOf<String>()
            while (index < words.size) {
                val repeats = index + size * 2 <= words.size &&
                    words.subList(index, index + size).map { it.lowercase(Locale.ROOT) } ==
                    words.subList(index + size, index + size * 2).map { it.lowercase(Locale.ROOT) }
                if (repeats) {
                    output += words.subList(index, index + size)
                    index += size * 2
                } else {
                    output += words[index]
                    index += 1
                }
            }
            words = output
        }
        return words.joinToString(" ")
    }

    private fun normalizeSpokenPunctuation(text: String): String {
        var result = text
        val replacements = listOf(
            Regex("\\bnew paragraph\\b", RegexOption.IGNORE_CASE) to "\n\n",
            Regex("\\bnew sentence\\b", RegexOption.IGNORE_CASE) to ". ",
            Regex("\\bnew line\\b", RegexOption.IGNORE_CASE) to "\n",
            Regex("\\bquestion mark\\b", RegexOption.IGNORE_CASE) to "?",
            Regex("\\bexclamation (?:mark|point)\\b", RegexOption.IGNORE_CASE) to "!",
            Regex("\\bfull stop\\b", RegexOption.IGNORE_CASE) to ".",
            Regex("\\bperiod\\b", RegexOption.IGNORE_CASE) to ".",
            Regex("\\bsemicolon\\b", RegexOption.IGNORE_CASE) to ";",
            Regex("\\bcolon\\b", RegexOption.IGNORE_CASE) to ":",
        )
        replacements.forEach { (pattern, replacement) -> result = pattern.replace(result, replacement) }
        result = Regex("\\s+comma(?=\\s|$)", RegexOption.IGNORE_CASE).replace(result, ",")
        result = Regex("\\?{2,}").replace(result, "?")
        return Regex("!{2,}").replace(result, "!")
    }

    private fun applySelfCorrections(text: String): String {
        var result = text
        val replacementMarker = Regex(
            "\\b([\\w'-]+)[\\s,.;:!?…-]+(?:actually[\\s,.;:!?…-]+)?" +
                "(?:make|change)[\\s,.;:!?…-]+that(?:\\s+to)?\\s+([\\w'-]+)\\b",
            RegexOption.IGNORE_CASE,
        )
        while (true) {
            val match = replacementMarker.find(result) ?: break
            result = result.substring(0, match.range.first) + match.groupValues[2] +
                result.substring(match.range.last + 1)
        }

        val simpleMarker = Regex("\\b(?:no[,.]?\\s+wait|actually[,.]?\\s+no)\\b", RegexOption.IGNORE_CASE)
        while (true) {
            val match = simpleMarker.find(result) ?: break
            var prefix = result.substring(0, match.range.first).trimEnd()
            val suffix = result.substring(match.range.last + 1).trimStart(' ', ',', '.', ';', ':')
            prefix = Regex("(?:\\b[\\w'-]+)[\\s,.;:!?]*$").replace(prefix, "").trimEnd()
            result = listOf(prefix, suffix).filter { it.isNotEmpty() }.joinToString(" ")
        }

        val scratchMarker = Regex("\\b(?:scratch that|disregard that)\\b", RegexOption.IGNORE_CASE)
        while (true) {
            val match = scratchMarker.find(result) ?: break
            var prefix = result.substring(0, match.range.first).trimEnd()
            val suffix = result.substring(match.range.last + 1).trimStart(' ', ',', '.', ';', ':')
            val boundary = listOf(prefix.lastIndexOf('.'), prefix.lastIndexOf('?'), prefix.lastIndexOf('!'), prefix.lastIndexOf('\n')).max()
            prefix = if (boundary >= 0) prefix.substring(0, boundary + 1) else ""
            result = listOf(prefix, suffix).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return result
    }

    private fun removeFillers(text: String): String {
        var result = Regex(
            "(?<!\\w)(?:um+|uh+|erm+|er+|ah+|hmm+)(?!\\w)[\\s,.;:!?…-]*",
            RegexOption.IGNORE_CASE,
        ).replace(text, " ")
        result = Regex("^\\s*(?:you know|like)\\s*[,;:…-]+\\s*", RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("\\s*[,;:…-]+\\s*(?:you know|like)\\s*[,;:…-]+\\s*", RegexOption.IGNORE_CASE).replace(result, " ")
        result = Regex("\\s*[,;:…-]?\\s+you know\\s*[,;:…-]*\\s*$", RegexOption.IGNORE_CASE).replace(result, "")
        return Regex("(?:\\[|\\(|<)\\s*(?:long\\s+)?pause\\s*(?:]|\\)|>)", RegexOption.IGNORE_CASE).replace(result, " ")
    }

    private fun applyVocabulary(text: String, vocabulary: Map<String, String>): String {
        if (vocabulary.isEmpty()) return text
        val ordered = vocabulary.keys.withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { it.value.length }.thenBy { it.index })
            .map { it.value }
        val alternatives = ordered.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("(?<!\\w)($alternatives)(?!\\w)", RegexOption.IGNORE_CASE)
        return pattern.replace(text) { match ->
            val spoken = ordered.first { it.equals(match.value, ignoreCase = true) }
            vocabulary.getValue(spoken)
        }
    }

    private fun inferSentenceBoundaries(text: String): String {
        val imperative = "create|open|save|send|run|start|stop|update|install|build|check|" +
            "write|copy|insert|review|test|make|show|close|continue"
        var result = Regex(
            "(?<![.!?\\r\\n])[,;:]?[ \\t]+(now|next|after[ \\t]+that|finally)[ \\t]+(?=(?:$imperative)\\b)",
            RegexOption.IGNORE_CASE,
        ).replace(text) { ". ${capitalizeFirst(it.groupValues[1])} " }

        val auxiliary = "can|could|would|should|will|do|does|did|is|are|was|were|have|has|had"
        val subject = "i|you|we|they|he|she|it|this|that|there|my|your|our|their|his|her"
        val questionStartPattern = "(?:(?:$auxiliary)(?=\\s+(?:$subject)\\b)|" +
            "(?:who|what|when|where|why|how)\\s+(?:$auxiliary)(?=\\s+\\w))"
        val questionPattern = Regex(
            "(?<![.!?\\r\\n])[,;:]?[ \\t]+($questionStartPattern)\\b",
            RegexOption.IGNORE_CASE,
        )
        val unsafePrefix = Regex(
            "(?:\\b(?:and|or|but|if|whether|because|since|although|while|when|where|why|how|" +
                "what|who|that|to|now|next|then|also|so|please)|" +
                "\\b(?:ask|asked|wonder|wondered|wondering|know|knew|explain|decide|check|" +
                "confirm|see)|\\b(?:tell|show)\\s+me|\\bquestion\\s+is)\\s*$",
            RegexOption.IGNORE_CASE,
        )
        val embeddedClauseBridge = Regex(
            "\\b(?:if|whether|because|since|although|while|when|where|why|how|what|who|that|to)\\b.*$",
            RegexOption.IGNORE_CASE,
        )
        val subordinateOpening = Regex(
            "^(?:if|whether|because|since|although|while|when|where|why|how|what|who|that)\\b",
            RegexOption.IGNORE_CASE,
        )
        val original = result
        result = questionPattern.replace(original) { match ->
            val prefix = original.substring(0, match.range.first).trimEnd()
            val boundary = listOf(
                prefix.lastIndexOf('.'), prefix.lastIndexOf('?'), prefix.lastIndexOf('!'),
                prefix.lastIndexOf('\n'),
            ).max()
            val currentClause = prefix.substring(boundary + 1).trim()
            if (
                semanticWord.findAll(currentClause).count() < 2 ||
                unsafePrefix.containsMatchIn(currentClause) ||
                subordinateOpening.containsMatchIn(currentClause) ||
                embeddedClauseBridge.containsMatchIn(currentClause)
            ) {
                match.value
            } else {
                ". ${capitalizeFirst(match.groupValues[1])}"
            }
        }
        return result
    }

    private fun formatSentences(text: String): String {
        var result = text.trim()
        if (result.isEmpty()) return ""
        result = Regex("[ \\t]+").replace(result, " ")
        result = Regex("\\s+([,.;:!?])").replace(result, "$1")
        result = Regex("([,.;:!?])(?=[A-Za-z0-9])").replace(result, "$1 ")
        result = Regex("[ \\t]+\\n").replace(result, "\n")
        result = Regex("\\n[ \\t]+").replace(result, "\n")
        result = Regex("\\n{3,}").replace(result, "\n\n")

        val characters = result.toCharArray()
        var capitalizeNext = true
        characters.indices.forEach { index ->
            val character = characters[index]
            when {
                capitalizeNext && character.isLetter() -> {
                    characters[index] = character.uppercaseChar()
                    capitalizeNext = false
                }
                character == '.' || character == '!' || character == '?' || character == '\n' -> capitalizeNext = true
                !character.isWhitespace() -> capitalizeNext = false
            }
        }
        result = String(characters).trim()
        result = Regex("\\.{2,}$").replace(result, ".")
        if (result.last() !in ".!?") {
            val finalSentence = result.split(Regex("[.!?]\\s+|\\n+")).last().trim()
            result += if (questionStart.containsMatchIn(finalSentence)) "?" else "."
        }
        return result
    }

    private fun formatAutomaticList(text: String): String {
        val value = text.trim()
        val lines = value.lines()
        if (lines.size >= 3 && lines.all { it.isNotBlank() }) {
            val firstWords = lines.map { it.trim().substringBefore(' ').lowercase(Locale.ROOT) }
            if (firstWords.toSet().size == 1) return lines.joinToString("\n") { "- ${it.trim()}" }
        }

        val bulletPattern = Regex("\\bbullet(?:\\s+point)?\\b", RegexOption.IGNORE_CASE)
        val bulletParts = bulletPattern.split(value).map { stripListPunctuation(it) }.filter { it.isNotEmpty() }
        if (bulletPattern.find(value)?.range?.first == 0 && bulletParts.size >= 3) {
            return bulletParts.joinToString("\n") { formatListItem(it) }
        }

        val markerNames = numberedListMarkers.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val markerPattern = Regex("\\b($markerNames)\\b", RegexOption.IGNORE_CASE)
        val markers = markerPattern.findAll(value).toList()
        if (markers.size < 3 || stripListPunctuation(value.substring(0, markers.first().range.first)).isNotEmpty()) {
            return text
        }
        val numbers = markers.map { numberedListMarkers.getValue(it.value.lowercase(Locale.ROOT)) }
        if (numbers != (1..numbers.size).toList()) return text
        val items = markers.mapIndexed { index, marker ->
            val end = if (index + 1 < markers.size) markers[index + 1].range.first else value.length
            stripListPunctuation(value.substring(marker.range.last + 1, end))
        }
        if (items.any { it.isEmpty() }) return text
        return items.joinToString("\n") { formatListItem(it) }
    }

    private fun stripListPunctuation(text: String): String =
        text.trim(' ', ',', '.', ';', ':', '-')

    private fun formatListItem(text: String): String = "- ${capitalizeFirst(text.trim())}"

    private fun capitalizeFirst(text: String): String =
        if (text.isEmpty()) text else text[0].uppercaseChar() + text.substring(1)
}
