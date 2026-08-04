package com.echolink.mobile

data class WritingReviewBlock(
    val start: Int,
    val end: Int,
    val original: String,
    val replacement: String,
    val tone: WritingTone,
    val index: Int,
    val total: Int,
) {
    val changed: Boolean get() = original != replacement
}

object PendingReviewPolicy {
    fun visible(block: WritingReviewBlock, aiReady: Boolean): WritingReviewBlock =
        if (aiReady) block else block.copy(replacement = block.original)
}

/**
 * Keeps a review anchored to exact character ranges so repeated sentences are safe to replace.
 * One block contains at most three complete sentences. Replacements adjust every later range.
 */
class WritingReviewSession private constructor(
    private var source: String,
    private val ranges: MutableList<TextRange>,
    private val reviewed: MutableList<Boolean>,
    private var position: Int,
    private val modelReplacements: MutableMap<Pair<Int, WritingTone>, String>,
    private val initialSource: String,
    private val initialRanges: List<TextRange>,
    private val history: MutableList<SessionState>,
) {
    val currentIndex: Int get() = (position + 1).coerceAtMost(ranges.size)
    val total: Int get() = ranges.size
    val complete: Boolean get() = reviewed.all { it }

    fun matches(value: String): Boolean = source == value

    fun current(tone: WritingTone): WritingReviewBlock? {
        if (complete || reviewed.getOrNull(position) != false) return null
        return blockAt(position, tone)
    }

    fun pending(tone: WritingTone): List<WritingReviewBlock> = ranges.indices
        .filterNot(reviewed::get)
        .map { blockAt(it, tone) }

    fun pendingDocumentSource(tone: WritingTone): String =
        pending(tone).joinToString("\n\n", transform = WritingReviewBlock::original)

    fun select(index: Int): Boolean {
        if (index !in ranges.indices || reviewed[index]) return false
        position = index
        return true
    }

    private fun blockAt(index: Int, tone: WritingTone): WritingReviewBlock {
        val range = ranges[index]
        val original = source.substring(range.start, range.end)
        return WritingReviewBlock(
            start = range.start,
            end = range.end,
            original = original,
            replacement = modelReplacements[index to tone] ?: original,
            tone = tone,
            index = index + 1,
            total = ranges.size,
        )
    }

    fun hasModelReplacement(index: Int, tone: WritingTone): Boolean =
        modelReplacements.containsKey((index - 1) to tone)

    /** How many blocks already carry a completed AI result for this tone. */
    fun readyCount(tone: WritingTone): Int =
        ranges.indices.count { modelReplacements.containsKey(it to tone) }

    fun currentChanges(tone: WritingTone): List<ApplicationEdit> {
        val block = current(tone) ?: return emptyList()
        if (!hasModelReplacement(block.index, tone)) return emptyList()
        return InlineEditDiff.changes(block.original, block.replacement, tone)
    }

    fun setModelReplacement(index: Int, tone: WritingTone, replacement: String): Boolean {
        val zeroBased = index - 1
        if (zeroBased !in ranges.indices || reviewed[zeroBased] || replacement.isBlank()) return false
        modelReplacements[zeroBased to tone] = replacement
        return true
    }

    fun clearModelReplacements(tone: WritingTone) {
        modelReplacements.keys.removeAll { it.second == tone }
    }

    fun replaceCurrent(value: String, tone: WritingTone): String {
        require(matches(value)) { "The text changed. Open a new review." }
        val block = requireNotNull(current(tone)) { "The review is complete." }
        check(hasModelReplacement(block.index, tone)) {
            "AI correction must finish before replacement."
        }
        saveHistory()
        val updated = source.replaceRange(block.start, block.end, block.replacement)
        val delta = block.replacement.length - (block.end - block.start)
        ranges[position] = TextRange(block.start, block.start + block.replacement.length)
        for (index in position + 1 until ranges.size) {
            val range = ranges[index]
            ranges[index] = TextRange(range.start + delta, range.end + delta)
        }
        source = updated
        reviewed[position] = true
        position = nextUnreviewed(position)
        return updated
    }

    fun acceptChange(
        value: String,
        tone: WritingTone,
        changeId: String,
    ): String {
        val updated = previewAcceptChange(value, tone, changeId)
        require(matches(value)) { "The text changed. Open a new review." }
        val block = requireNotNull(current(tone)) { "The review is complete." }
        saveHistory()
        val fullReplacement = block.replacement
        val partialReplacement = updated.substring(
            block.start,
            block.start + updated.length - (source.length - block.end),
        )
        updateCurrentSource(block, partialReplacement)
        clearOtherToneReplacements(position, tone)
        modelReplacements[position to tone] = fullReplacement
        finishCurrentIfNoChanges(tone)
        return source
    }

    fun previewAcceptChange(
        value: String,
        tone: WritingTone,
        changeId: String,
    ): String {
        require(matches(value)) { "The text changed. Open a new review." }
        val block = requireNotNull(current(tone)) { "The review is complete." }
        check(hasModelReplacement(block.index, tone)) {
            "AI correction must finish before accepting a change."
        }
        val changes = currentChanges(tone)
        require(changes.any { it.id == changeId }) { "Unknown correction change." }
        val partialReplacement = InlineEditDiff.applyDecisions(
            original = block.original,
            changes = changes,
            acceptedChangeIds = setOf(changeId),
        )
        return source.replaceRange(block.start, block.end, partialReplacement)
    }

    fun rejectChange(
        value: String,
        tone: WritingTone,
        changeId: String,
    ): String {
        require(matches(value)) { "The text changed. Open a new review." }
        val block = requireNotNull(current(tone)) { "The review is complete." }
        check(hasModelReplacement(block.index, tone)) {
            "AI correction must finish before rejecting a change."
        }
        val changes = currentChanges(tone)
        require(changes.any { it.id == changeId }) { "Unknown correction change." }
        saveHistory()
        val remainingCandidate = InlineEditDiff.applyDecisions(
            original = block.original,
            changes = changes,
            acceptedChangeIds = changes.map(ApplicationEdit::id).toSet() - changeId,
        )
        clearOtherToneReplacements(position, tone)
        modelReplacements[position to tone] = remainingCandidate
        finishCurrentIfNoChanges(tone)
        return source
    }

    fun dismissCurrent() {
        if (!complete && reviewed.getOrNull(position) == false) {
            saveHistory()
            reviewed[position] = true
            position = nextUnreviewed(position)
        }
    }

    fun acceptAll(value: String, tone: WritingTone): String {
        require(matches(value)) { "The text changed. Open a new review." }
        val pendingIndexes = ranges.indices.filterNot(reviewed::get)
        check(pendingIndexes.all { modelReplacements.containsKey(it to tone) }) {
            "Every AI correction must finish before accepting all."
        }
        saveHistory()
        var updated = source
        pendingIndexes.asReversed().forEach { index ->
            val range = ranges[index]
            val replacement = requireNotNull(modelReplacements[index to tone])
            updated = updated.replaceRange(range.start, range.end, replacement)
        }
        source = updated
        reviewed.indices.forEach { reviewed[it] = true }
        position = ranges.size
        return source
    }

    fun undo(value: String): String {
        require(matches(value)) { "The text changed. Open a new review." }
        val previous = history.removeLastOrNull() ?: return source
        restoreState(previous)
        return source
    }

    fun restoreOriginal(value: String): String {
        require(matches(value)) { "The text changed. Open a new review." }
        if (source == initialSource) return source
        saveHistory()
        source = initialSource
        ranges.clear()
        ranges.addAll(initialRanges)
        reviewed.indices.forEach { reviewed[it] = false }
        position = 0
        modelReplacements.clear()
        return source
    }

    fun canUndo(): Boolean = history.isNotEmpty()

    fun allModelReplacementsReady(tone: WritingTone): Boolean =
        ranges.indices.filterNot(reviewed::get).all { modelReplacements.containsKey(it to tone) }

    private fun updateCurrentSource(
        block: WritingReviewBlock,
        replacement: String,
    ) {
        val updated = source.replaceRange(block.start, block.end, replacement)
        val delta = replacement.length - (block.end - block.start)
        ranges[position] = TextRange(block.start, block.start + replacement.length)
        for (index in position + 1 until ranges.size) {
            val range = ranges[index]
            ranges[index] = TextRange(range.start + delta, range.end + delta)
        }
        source = updated
    }

    private fun clearOtherToneReplacements(
        blockIndex: Int,
        selectedTone: WritingTone,
    ) {
        modelReplacements.keys.removeAll { (index, tone) ->
            index == blockIndex && tone != selectedTone
        }
    }

    private fun finishCurrentIfNoChanges(tone: WritingTone) {
        if (currentChanges(tone).isNotEmpty()) return
        reviewed[position] = true
        position = nextUnreviewed(position)
    }

    private fun saveHistory() {
        history += SessionState(
            source = source,
            ranges = ranges.toList(),
            reviewed = reviewed.toList(),
            position = position,
            replacements = modelReplacements.toMap(),
        )
    }

    private fun restoreState(state: SessionState) {
        source = state.source
        ranges.clear()
        ranges.addAll(state.ranges)
        reviewed.clear()
        reviewed.addAll(state.reviewed)
        position = state.position
        modelReplacements.clear()
        modelReplacements.putAll(state.replacements)
    }

    private fun nextUnreviewed(after: Int): Int {
        for (index in after + 1 until reviewed.size) if (!reviewed[index]) return index
        for (index in 0 until after) if (!reviewed[index]) return index
        return ranges.size
    }

    companion object {
        fun create(
            source: String,
            selectionStart: Int = 0,
            selectionEnd: Int = 0,
            sentencesPerBlock: Int = 3,
        ): WritingReviewSession? {
            require(sentencesPerBlock in 1..3) { "Sentence batch must be between one and three." }
            if (source.isBlank()) return null
            val hasSelection = selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd
            val scopeStart = if (hasSelection) minOf(selectionStart, selectionEnd) else 0
            val scopeEnd = if (hasSelection) maxOf(selectionStart, selectionEnd) else source.length
            if (scopeStart !in 0..source.length || scopeEnd !in 0..source.length || scopeStart >= scopeEnd) {
                return null
            }
            val sentences = sentenceRanges(source, scopeStart, scopeEnd)
            if (sentences.isEmpty()) return null
            val groups = groupSentences(source, sentences, sentencesPerBlock).toMutableList()
            return WritingReviewSession(
                source = source,
                ranges = groups,
                reviewed = MutableList(groups.size) { false },
                position = 0,
                modelReplacements = mutableMapOf(),
                initialSource = source,
                initialRanges = groups.toList(),
                history = mutableListOf(),
            )
        }

        fun createParagraphs(
            source: String,
            selectionStart: Int = 0,
            selectionEnd: Int = 0,
        ): WritingReviewSession? {
            if (source.isBlank()) return null
            val hasSelection =
                selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd
            val scopeStart = if (hasSelection) minOf(selectionStart, selectionEnd) else 0
            val scopeEnd = if (hasSelection) maxOf(selectionStart, selectionEnd) else source.length
            if (
                scopeStart !in 0..source.length ||
                scopeEnd !in 0..source.length ||
                scopeStart >= scopeEnd
            ) {
                return null
            }
            val paragraphRanges = paragraphRanges(source, scopeStart, scopeEnd)
            if (paragraphRanges.isEmpty()) return null
            return WritingReviewSession(
                source = source,
                ranges = paragraphRanges.toMutableList(),
                reviewed = MutableList(paragraphRanges.size) { false },
                position = 0,
                modelReplacements = mutableMapOf(),
                initialSource = source,
                initialRanges = paragraphRanges,
                history = mutableListOf(),
            )
        }

        private fun sentenceRanges(value: String, start: Int, end: Int): List<TextRange> {
            val output = mutableListOf<TextRange>()
            var sentenceStart = skipWhitespace(value, start, end)
            var index = sentenceStart
            while (index < end) {
                val character = value[index]
                val punctuationBoundary = character in ".!?" &&
                    (index + 1 >= end || value[index + 1].isWhitespace())
                val paragraphBoundary = character == '\n' && index + 1 < end && value[index + 1] == '\n'
                if (punctuationBoundary || paragraphBoundary) {
                    val sentenceEnd = if (punctuationBoundary) index + 1 else index
                    if (sentenceEnd > sentenceStart) output += TextRange(sentenceStart, sentenceEnd)
                    sentenceStart = skipWhitespace(value, if (paragraphBoundary) index + 2 else index + 1, end)
                    index = sentenceStart
                    continue
                }
                index += 1
            }
            if (sentenceStart < end) {
                val trimmedEnd = trimTrailingWhitespace(value, sentenceStart, end)
                if (trimmedEnd > sentenceStart) output += TextRange(sentenceStart, trimmedEnd)
            }
            return output
        }

        private fun groupSentences(
            source: String,
            sentences: List<TextRange>,
            maximumSentences: Int,
        ): List<TextRange> {
            val groups = mutableListOf<TextRange>()
            var groupStart = sentences.first().start
            var groupEnd = sentences.first().end
            var groupSize = 1
            sentences.drop(1).forEach { sentence ->
                val crossesParagraph =
                    Regex("\\n\\s*\\n").containsMatchIn(
                        source.substring(groupEnd, sentence.start),
                    )
                if (groupSize >= maximumSentences || crossesParagraph) {
                    groups += TextRange(groupStart, groupEnd)
                    groupStart = sentence.start
                    groupSize = 0
                }
                groupEnd = sentence.end
                groupSize += 1
            }
            groups += TextRange(groupStart, groupEnd)
            return groups
        }

        private fun skipWhitespace(value: String, from: Int, end: Int): Int {
            var index = from
            while (index < end && value[index].isWhitespace()) index += 1
            return index
        }

        private fun trimTrailingWhitespace(value: String, start: Int, end: Int): Int {
            var index = end
            while (index > start && value[index - 1].isWhitespace()) index -= 1
            return index
        }

        private fun paragraphRanges(
            source: String,
            start: Int,
            end: Int,
        ): List<TextRange> {
            val output = mutableListOf<TextRange>()
            val scope = source.substring(start, end)
            var cursor = start
            ParagraphChunker.chunk(scope).forEach { chunk ->
                if (chunk.text.isBlank()) {
                    cursor += chunk.text.length + chunk.separatorAfter.length
                    return@forEach
                }
                val located = source.indexOf(chunk.text, cursor)
                if (located < cursor || located + chunk.text.length > end) return@forEach
                addTrimmedParagraph(
                    output,
                    source,
                    located,
                    located + chunk.text.length,
                )
                cursor = located + chunk.text.length + chunk.separatorAfter.length
            }
            return output
        }

        private fun addTrimmedParagraph(
            output: MutableList<TextRange>,
            source: String,
            rawStart: Int,
            rawEnd: Int,
        ) {
            var start = rawStart
            var end = rawEnd
            while (start < end && source[start].isWhitespace()) start += 1
            while (end > start && source[end - 1].isWhitespace()) end -= 1
            if (start < end) output += TextRange(start, end)
        }
    }

    private data class TextRange(val start: Int, val end: Int)

    private data class SessionState(
        val source: String,
        val ranges: List<TextRange>,
        val reviewed: List<Boolean>,
        val position: Int,
        val replacements: Map<Pair<Int, WritingTone>, String>,
    )
}
