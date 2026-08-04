package com.echolink.mobile

data class ToneMenuItem(
    val tone: WritingTone,
    val label: String,
    val selected: Boolean,
)

object ToneMenuPolicy {
    fun items(selectedTone: WritingTone): List<ToneMenuItem> =
        WritingTone.entries.map { tone ->
            ToneMenuItem(
                tone = tone,
                label = tone.label,
                selected = tone == selectedTone,
            )
        }

    fun shouldRegenerate(
        current: WritingTone,
        next: WritingTone,
        reviewOpen: Boolean,
    ): Boolean = reviewOpen && current != next
}
