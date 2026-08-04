package com.echolink.mobile

data class ExternalEditorSnapshot(
    val packageName: String,
    val text: String,
    val editable: Boolean,
    val password: Boolean,
    val selectionStart: Int,
    val selectionEnd: Int,
    private val metadataTextLength: Int? = null,
) {
    val textLength: Int
        get() = metadataTextLength ?: text.length

    val hasText: Boolean
        get() = textLength > 0
}

/** Privacy and safety gate for the opt-in across-app writing helper. */
object ExternalWritingPolicy {
    const val MAX_EXTERNAL_INPUT_LENGTH = 50_000

    fun canOffer(snapshot: ExternalEditorSnapshot, ownPackage: String): Boolean =
        snapshot.packageName != ownPackage &&
            snapshot.editable &&
            !snapshot.password &&
            snapshot.hasText &&
            snapshot.textLength <= MAX_EXTERNAL_INPUT_LENGTH

    fun metadataSnapshot(
        packageName: String,
        text: CharSequence?,
        editable: Boolean,
        password: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
    ): ExternalEditorSnapshot {
        val textLength = runCatching { text?.length ?: 0 }.getOrDefault(0)
        return ExternalEditorSnapshot(
            packageName = packageName,
            text = "",
            editable = editable,
            password = password,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            metadataTextLength = textLength,
        )
    }

    fun materializeReviewText(text: CharSequence?): String? {
        val textLength = runCatching { text?.length ?: 0 }.getOrDefault(0)
        if (textLength !in 1..MAX_EXTERNAL_INPUT_LENGTH) return null
        val materialized = runCatching { text.toString() }.getOrNull() ?: return null
        return materialized.takeIf {
            it.isNotBlank() && it.length <= MAX_EXTERNAL_INPUT_LENGTH
        }
    }

    fun capturedSnapshot(
        snapshot: ExternalEditorSnapshot,
        text: CharSequence?,
        selectionStart: Int,
        selectionEnd: Int,
    ): ExternalEditorSnapshot? {
        val materialized = materializeReviewText(text) ?: return null
        return snapshot.copy(
            text = materialized,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            metadataTextLength = null,
        )
    }

    fun scrubbedSnapshot(snapshot: ExternalEditorSnapshot): ExternalEditorSnapshot =
        snapshot.copy(
            text = "",
            selectionStart = 0,
            selectionEnd = 0,
            metadataTextLength = snapshot.textLength,
        )

    fun safeSelection(snapshot: ExternalEditorSnapshot): IntRange? {
        val start = minOf(snapshot.selectionStart, snapshot.selectionEnd)
        val end = maxOf(snapshot.selectionStart, snapshot.selectionEnd)
        return if (start >= 0 && end <= snapshot.text.length && start < end) start until end else null
    }
}
