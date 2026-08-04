package com.echolink.mobile

data class ApprovalResult(
    val text: String? = null,
    val error: String? = null,
) {
    val accepted: Boolean get() = text != null
}

object ApprovedTextPolicy {
    const val MAX_CHARACTERS = 10_000

    fun validate(raw: String): ApprovalResult {
        val normalized = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { character ->
                character == '\n' || character == '\t' || !character.isISOControl()
            }
            .trim()

        if (normalized.isBlank()) {
            return ApprovalResult(error = "Enter text before approving it.")
        }
        if (normalized.length > MAX_CHARACTERS) {
            return ApprovalResult(error = "Approved text is limited to $MAX_CHARACTERS characters.")
        }
        return ApprovalResult(text = normalized)
    }
}
