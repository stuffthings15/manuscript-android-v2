package com.echolink.mobile

data class ReviewDraft(
    val rawText: String,
    val reviewedText: String,
    val changed: Boolean,
) {
    companion object {
        fun fromTranscript(
            transcript: String,
            vocabulary: Map<String, String> = emptyMap(),
        ): ReviewDraft {
            val cleaned = DeterministicCleanup.clean(transcript, vocabulary)
            return ReviewDraft(
                rawText = transcript,
                reviewedText = cleaned.text,
                changed = cleaned.changed,
            )
        }
    }
}
