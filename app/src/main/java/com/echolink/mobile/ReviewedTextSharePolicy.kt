package com.echolink.mobile

/** Keeps sharing explicit and rejects drafts with no visible user text. */
object ReviewedTextSharePolicy {
    fun accept(raw: CharSequence): String? {
        val reviewedText = raw.toString()
        return reviewedText.takeUnless { it.isBlank() }
    }
}
