package com.echolink.mobile

data class ToneMenuItem(
    val tone: WritingTone,
    val label: String,
    val selected: Boolean,
)

object ToneMenuPolicy {
    /**
     * The six presets offered on screen.
     *
     * There were twenty-four, which is a scrolling strip nobody reads to the
     * end of. These six are the ones the product is actually for: fixing
     * writing, then the two register choices a writer makes most.
     *
     * The other tones still EXIST. Curtis's saved tone, the prompt library,
     * ProcessText and the corpus tooling all resolve WritingTone by name, and
     * deleting eighteen enum entries would touch nine to twelve files each and
     * throw away working prompts to shorten a menu. Reducing the presets is
     * what shortens the menu; the enum is not the menu.
     */
    val PRESETS: List<WritingTone> = listOf(
        WritingTone.PROOFREAD,
        WritingTone.IMPROVE,
        WritingTone.REPHRASE,
        WritingTone.SHORTEN,
        WritingTone.FORMAL,
        WritingTone.FRIENDLY,
    )

    /**
     * The presets, plus the selected tone when it is not one of them.
     *
     * Without that a writer whose saved tone is Professional would open the
     * menu, see six chips with none of them selected, and have no way back to
     * the tone their work is being corrected in.
     */
    fun items(selectedTone: WritingTone): List<ToneMenuItem> {
        val tones = if (PRESETS.contains(selectedTone)) PRESETS else PRESETS + selectedTone
        return tones.map { tone ->
            ToneMenuItem(
                tone = tone,
                label = tone.label,
                selected = tone == selectedTone,
            )
        }
    }

    fun shouldRegenerate(
        current: WritingTone,
        next: WritingTone,
        reviewOpen: Boolean,
    ): Boolean = reviewOpen && current != next
}
