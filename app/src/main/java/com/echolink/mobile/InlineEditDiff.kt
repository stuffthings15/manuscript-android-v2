package com.echolink.mobile

enum class InlineEditKind {
    UNCHANGED,
    REMOVED,
    INSERTED,
}

data class InlineEditSegment(
    val text: String,
    val kind: InlineEditKind,
)

enum class EditCategory {
    CAPITALIZATION,
    PUNCTUATION,
    SPELLING,
    WORD_FORM,
    AGREEMENT,
    PRONOUN,
    CONTEXTUAL_WORD,
    INSERTION,
    DELETION,
    REORDERING,
    STYLE,
}

data class ApplicationEdit(
    val id: String,
    val originalStart: Int,
    val originalEnd: Int,
    val replacementStart: Int,
    val replacementEnd: Int,
    val originalText: String,
    val replacementText: String,
    val category: EditCategory,
    val note: String,
)

/**
 * Produces a stable word-and-punctuation diff for the review UI.
 * Removed text stays in place so it can be crossed out; inserted text follows it.
 */
object InlineEditDiff {
    private val tokenPattern = Regex("""\s+|[\p{L}\p{N}\u2019']+|[^\p{L}\p{N}\s]""")

    fun changes(
        original: String,
        replacement: String,
        tone: WritingTone,
    ): List<ApplicationEdit> {
        if (original == replacement) return emptyList()
        val before = tokens(original)
        val after = tokens(replacement)
        val lengths = lcsLengths(before, after)
        val output = mutableListOf<ApplicationEdit>()
        var left = 0
        var right = 0
        var originalCursor = 0
        var replacementCursor = 0
        var groupOriginalStart: Int? = null
        var groupReplacementStart: Int? = null

        fun beginGroup() {
            if (groupOriginalStart == null) {
                groupOriginalStart = originalCursor
                groupReplacementStart = replacementCursor
            }
        }

        fun flushGroup() {
            val originalStart = groupOriginalStart ?: return
            val replacementStart = requireNotNull(groupReplacementStart)
            val originalText = original.substring(originalStart, originalCursor)
            val replacementText = replacement.substring(replacementStart, replacementCursor)
            val category = classify(originalText, replacementText, tone)
            output += ApplicationEdit(
                id = "$originalStart:$originalCursor:$replacementStart:$replacementCursor",
                originalStart = originalStart,
                originalEnd = originalCursor,
                replacementStart = replacementStart,
                replacementEnd = replacementCursor,
                originalText = originalText,
                replacementText = replacementText,
                category = category,
                note = noteFor(category),
            )
            groupOriginalStart = null
            groupReplacementStart = null
        }

        while (left < before.size || right < after.size) {
            when {
                left < before.size &&
                    right < after.size &&
                    before[left].text == after[right].text -> {
                    flushGroup()
                    originalCursor = before[left].end
                    replacementCursor = after[right].end
                    left += 1
                    right += 1
                }
                right < after.size &&
                    (
                        left >= before.size ||
                            lengths[left][right + 1] > lengths[left + 1][right]
                        ) -> {
                    beginGroup()
                    replacementCursor = after[right].end
                    right += 1
                }
                else -> {
                    beginGroup()
                    originalCursor = before[left].end
                    left += 1
                }
            }
        }
        flushGroup()
        return output
    }

    fun applyDecisions(
        original: String,
        changes: List<ApplicationEdit>,
        acceptedChangeIds: Set<String>,
    ): String = buildString {
        var cursor = 0
        changes.sortedWith(
            compareBy<ApplicationEdit>(ApplicationEdit::originalStart)
                .thenBy(ApplicationEdit::originalEnd),
        ).forEach { change ->
            require(change.originalStart >= cursor) { "Overlapping application edits." }
            append(original, cursor, change.originalStart)
            if (change.id in acceptedChangeIds) {
                append(change.replacementText)
            } else {
                append(change.originalText)
            }
            cursor = change.originalEnd
        }
        append(original, cursor, original.length)
    }

    fun segments(original: String, replacement: String): List<InlineEditSegment> {
        if (original == replacement) {
            return listOf(InlineEditSegment(original, InlineEditKind.UNCHANGED))
        }
        val before = tokenPattern.findAll(original).map { it.value }.toList()
        val after = tokenPattern.findAll(replacement).map { it.value }.toList()
        val lengths = Array(before.size + 1) { IntArray(after.size + 1) }
        for (left in before.indices.reversed()) {
            for (right in after.indices.reversed()) {
                lengths[left][right] = if (before[left] == after[right]) {
                    lengths[left + 1][right + 1] + 1
                } else {
                    maxOf(lengths[left + 1][right], lengths[left][right + 1])
                }
            }
        }

        val raw = mutableListOf<InlineEditSegment>()
        var left = 0
        var right = 0
        while (left < before.size || right < after.size) {
            when {
                left < before.size && right < after.size && before[left] == after[right] -> {
                    raw += InlineEditSegment(before[left], InlineEditKind.UNCHANGED)
                    left += 1
                    right += 1
                }
                right < after.size &&
                    (left >= before.size || lengths[left][right + 1] > lengths[left + 1][right]) -> {
                    raw += InlineEditSegment(after[right], InlineEditKind.INSERTED)
                    right += 1
                }
                else -> {
                    raw += InlineEditSegment(before[left], InlineEditKind.REMOVED)
                    left += 1
                }
            }
        }
        return raw.fold(mutableListOf()) { merged, segment ->
            val previous = merged.lastOrNull()
            if (previous?.kind == segment.kind) {
                merged[merged.lastIndex] = previous.copy(text = previous.text + segment.text)
            } else {
                merged += segment
            }
            merged
        }
    }

    fun note(original: String, replacement: String, tone: WritingTone): String {
        if (original == replacement) return "No change needed."
        if (tone != WritingTone.PROOFREAD) return "Rewritten in the ${tone.label} tone."

        val originalLetters = original.filter(Char::isLetterOrDigit)
        val replacementLetters = replacement.filter(Char::isLetterOrDigit)
        if (originalLetters.equals(replacementLetters, ignoreCase = true)) {
            return if (originalLetters == replacementLetters) {
                "Punctuation corrected."
            } else {
                "Capitalization and punctuation corrected."
            }
        }
        val lower = original.lowercase()
        if (
            listOf("teh", "recieve", "seperate", "alot", "dont", "cant", "wont")
                .any(lower::contains)
        ) {
            return "Spelling and grammar corrected."
        }
        if (replacement.length + 8 < original.length) return "Wording tightened for clarity."
        return "Grammar and wording improved."
    }

    private fun tokens(value: String): List<DiffToken> =
        tokenPattern.findAll(value).map { match ->
            DiffToken(match.value, match.range.first, match.range.last + 1)
        }.toList()

    private fun lcsLengths(
        before: List<DiffToken>,
        after: List<DiffToken>,
    ): Array<IntArray> {
        val lengths = Array(before.size + 1) { IntArray(after.size + 1) }
        for (left in before.indices.reversed()) {
            for (right in after.indices.reversed()) {
                lengths[left][right] = if (before[left].text == after[right].text) {
                    lengths[left + 1][right + 1] + 1
                } else {
                    maxOf(lengths[left + 1][right], lengths[left][right + 1])
                }
            }
        }
        return lengths
    }

    private fun classify(
        original: String,
        replacement: String,
        tone: WritingTone,
    ): EditCategory {
        if (tone != WritingTone.PROOFREAD) return EditCategory.STYLE
        if (original.isEmpty()) return EditCategory.INSERTION
        if (replacement.isEmpty()) return EditCategory.DELETION
        if (original.equals(replacement, ignoreCase = true)) {
            return EditCategory.CAPITALIZATION
        }
        if (
            original.filter(Char::isLetterOrDigit)
                .equals(replacement.filter(Char::isLetterOrDigit), ignoreCase = true)
        ) {
            return EditCategory.PUNCTUATION
        }
        val left = original.trim().lowercase()
        val right = replacement.trim().lowercase()
        if (
            left to right in setOf(
                "is" to "are",
                "are" to "is",
                "was" to "were",
                "were" to "was",
                "has" to "have",
                "have" to "has",
                "does" to "do",
                "do" to "does",
            )
        ) {
            return EditCategory.AGREEMENT
        }
        val pronouns = setOf(
            "i", "me", "we", "us", "he", "him", "she", "her", "they", "them",
            "who", "whom",
        )
        if (left in pronouns || right in pronouns) return EditCategory.PRONOUN
        if (editDistance(left, right) <= 2) return EditCategory.SPELLING
        if (
            left.removeSuffix("s") == right.removeSuffix("s") ||
            left.removeSuffix("ed") == right.removeSuffix("ed") ||
            left.removeSuffix("ing") == right.removeSuffix("ing")
        ) {
            return EditCategory.WORD_FORM
        }
        val leftWords = Regex("[\\p{L}\\p{N}]+").findAll(left).map { it.value }.toList()
        val rightWords = Regex("[\\p{L}\\p{N}]+").findAll(right).map { it.value }.toList()
        if (leftWords.sorted() == rightWords.sorted()) return EditCategory.REORDERING
        return EditCategory.CONTEXTUAL_WORD
    }

    private fun noteFor(category: EditCategory): String = when (category) {
        EditCategory.CAPITALIZATION -> "Capitalization corrected."
        EditCategory.PUNCTUATION -> "Punctuation corrected."
        EditCategory.SPELLING -> "Spelling corrected."
        EditCategory.WORD_FORM -> "Word form corrected."
        EditCategory.AGREEMENT -> "Grammar agreement corrected."
        EditCategory.PRONOUN -> "Pronoun corrected."
        EditCategory.CONTEXTUAL_WORD -> "Contextual wording corrected."
        EditCategory.INSERTION -> "Missing text inserted."
        EditCategory.DELETION -> "Unneeded text removed."
        EditCategory.REORDERING -> "Wording reordered for correctness."
        EditCategory.STYLE -> "Wording changed for the selected style."
    }

    private fun editDistance(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftCharacter ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightCharacter ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] +
                        if (leftCharacter == rightCharacter) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private data class DiffToken(
        val text: String,
        val start: Int,
        val end: Int,
    )
}
