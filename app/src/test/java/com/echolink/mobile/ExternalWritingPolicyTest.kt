package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWritingPolicyTest {
    @Test
    fun offersOnlyForNonPasswordEditableTextOutsideManuscript() {
        val normal = snapshot(packageName = "com.google.android.apps.messaging")

        assertTrue(ExternalWritingPolicy.canOffer(normal, "com.remote.Manuscript"))
        assertFalse(ExternalWritingPolicy.canOffer(normal.copy(password = true), "com.remote.Manuscript"))
        assertFalse(ExternalWritingPolicy.canOffer(normal.copy(editable = false), "com.remote.Manuscript"))
        assertFalse(ExternalWritingPolicy.canOffer(normal.copy(text = ""), "com.remote.Manuscript"))
        assertFalse(
            ExternalWritingPolicy.canOffer(
                normal.copy(packageName = "com.remote.Manuscript"),
                "com.remote.Manuscript",
            ),
        )
    }

    @Test
    fun metadataSnapshotDoesNotMaterializeTextBeforeUserOpensReview() {
        val text = LengthOnlyCharSequence(27)

        val captured = ExternalWritingPolicy.metadataSnapshot(
            packageName = "com.google.android.apps.messaging",
            text = text,
            editable = true,
            password = false,
            selectionStart = 2,
            selectionEnd = 8,
        )

        assertEquals("", captured.text)
        assertEquals(27, captured.textLength)
        assertTrue(captured.hasText)
        assertTrue(ExternalWritingPolicy.canOffer(captured, "com.remote.Manuscript"))
    }

    @Test
    fun materializesOnlyBoundedNonblankExternalText() {
        val maximum = "x".repeat(ExternalWritingPolicy.MAX_EXTERNAL_INPUT_LENGTH)
        val oversized = "x".repeat(ExternalWritingPolicy.MAX_EXTERNAL_INPUT_LENGTH + 1)

        assertEquals(maximum, ExternalWritingPolicy.materializeReviewText(maximum))
        assertNull(ExternalWritingPolicy.materializeReviewText(oversized))
        assertNull(ExternalWritingPolicy.materializeReviewText("   \n"))
        assertNull(ExternalWritingPolicy.materializeReviewText(ThrowingToStringCharSequence()))
    }

    @Test
    fun scrubbingDropsCapturedTextButKeepsSafeTargetMetadata() {
        val captured = snapshot(
            text = "private message",
            selectionStart = 2,
            selectionEnd = 7,
        )

        val scrubbed = ExternalWritingPolicy.scrubbedSnapshot(captured)

        assertEquals("", scrubbed.text)
        assertEquals(15, scrubbed.textLength)
        assertTrue(scrubbed.hasText)
        assertEquals(0, scrubbed.selectionStart)
        assertEquals(0, scrubbed.selectionEnd)
    }

    @Test
    fun rejectsExternalFieldsLargerThanTheReviewLimit() {
        val oversized = snapshot(
            text = "x".repeat(ExternalWritingPolicy.MAX_EXTERNAL_INPUT_LENGTH + 1),
        )

        assertFalse(ExternalWritingPolicy.canOffer(oversized, "com.remote.Manuscript"))
    }

    @Test
    fun acceptsOnlyAnExactInBoundsSelection() {
        assertEquals(5 until 12, ExternalWritingPolicy.safeSelection(snapshot(selectionStart = 12, selectionEnd = 5)))
        assertNull(ExternalWritingPolicy.safeSelection(snapshot(selectionStart = 2, selectionEnd = 2)))
        assertNull(ExternalWritingPolicy.safeSelection(snapshot(selectionStart = -1, selectionEnd = -1)))
        assertNull(ExternalWritingPolicy.safeSelection(snapshot(selectionStart = 0, selectionEnd = 99)))
    }

    private fun snapshot(
        packageName: String = "example.app",
        text: String = "this sentence has errors",
        editable: Boolean = true,
        password: Boolean = false,
        selectionStart: Int = 0,
        selectionEnd: Int = 0,
    ) = ExternalEditorSnapshot(
        packageName,
        text,
        editable,
        password,
        selectionStart,
        selectionEnd,
    )

    private class LengthOnlyCharSequence(
        override val length: Int,
    ) : CharSequence {
        override fun get(index: Int): Char = error("Text content must not be read before review.")
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            error("Text content must not be copied before review.")
        override fun toString(): String = error("Text content must not be materialized before review.")
    }

    private class ThrowingToStringCharSequence : CharSequence {
        override val length: Int = 12
        override fun get(index: Int): Char = 'x'
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this
        override fun toString(): String = error("Malformed external text.")
    }
}
