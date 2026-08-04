package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPreparationTest {
    @Test
    fun normalizationTracksExactLineEndingsAndRemovesInvalidControls() {
        val source = "Cafe\u0301\r\nFirst line.\nSecond\u0000 line.\r\n"

        val normalized = LosslessSourceNormalizer.normalize(source)

        assertEquals("Café\nFirst line.\nSecond line.\n", normalized.text)
        assertEquals(
            listOf(LineEnding.CRLF, LineEnding.LF, LineEnding.CRLF),
            normalized.lineEndings,
        )
        assertEquals(
            "Café\r\nFirst line.\nSecond line.\r\n",
            normalized.restoreLineEndings(normalized.text),
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalized.restoreLineEndings("Café\nFirst line.")
        }
    }

    @Test
    fun protectedSpansRoundTripExactlyAndRejectMissingOrReorderedTokens() {
        val source = """
            # Project Atlas
            Email Alex Rivera at alex@example.com by July 14, 2027 at 4:30 p.m.
            Keep 42%, https://example.com/a, C:\Docs\plan.txt, @curtis, `x += 1`, and "Do not publish."
            Device SM-S918U uses 12 GB.
        """.trimIndent()

        val protected = ProtectedSpanCodec.protect(source)

        assertFalse(protected.encodedText.contains("alex@example.com"))
        assertFalse(protected.encodedText.contains("Project Atlas"))
        assertEquals(source, protected.restore(protected.encodedText))
        val first = protected.placeholders.first()
        assertThrows(ProtectedSpanViolation::class.java) {
            protected.restore(protected.encodedText.replace(first.token, ""))
        }
        val reversed = protected.placeholders
            .take(2)
            .fold(protected.encodedText) { text, span ->
                text.replace(span.token, "__TEMP_${span.index}__")
            }
            .replace("__TEMP_0__", protected.placeholders[1].token)
            .replace("__TEMP_1__", protected.placeholders[0].token)
        assertThrows(ProtectedSpanViolation::class.java) {
            protected.restore(reversed)
        }
    }

    @Test
    fun laterNameReferencesAndWrittenNumbersRemainExact() {
        val source =
            "Mira Chen leads operations. Mira will configure three loaner tablets."

        val protected = ProtectedSpanCodec.protect(source)

        assertFalse(protected.encodedText.contains("Mira"))
        assertTrue(protected.encodedText.contains("three"))
        assertEquals(source, protected.restore(protected.encodedText))
        assertTrue(
            protected.placeholders.count { it.type == ProtectedSpanType.NAME } >= 2,
        )

    }

    @Test
    fun plainNumericFactsStayReadableToTheModel() {
        val source = "Room 12 has 186 guests and a $24,750 budget."

        val protected = ProtectedSpanCodec.protect(source)

        assertTrue(protected.encodedText.contains("Room 12"))
        assertTrue(protected.encodedText.contains("186 guests"))
        assertFalse(protected.encodedText.contains("$24,750"))
        assertFalse(
            protected.placeholders.any {
                it.type == ProtectedSpanType.NUMBER && it.original in setOf("12", "186")
            },
        )
        assertTrue(
            protected.placeholders.any {
                it.type == ProtectedSpanType.NUMBER && it.original == "$24,750"
            },
        )
    }

    @Test
    fun writtenNumbersUseOpaqueTokensThatCannotSuggestWordFragments() {
        val source = "Bring one phone, four microphones, and two projectors."

        val protected = ProtectedSpanCodec.protect(source)
        val writtenNumbers = protected.placeholders.filter {
            it.type == ProtectedSpanType.NUMBER
        }

        assertTrue(writtenNumbers.isEmpty())
        assertEquals(source, protected.encodedText)
        assertEquals(source, protected.restore(protected.encodedText))
    }
    @Test
    fun establishedHyphenatedDomainTermsRemainExact() {
        val source =
            "Run the digital-access pilot with full-device encryption and local-only AI."

        val protected = ProtectedSpanCodec.protect(source)

        assertEquals(
            listOf("digital-access", "full-device", "local-only"),
            protected.placeholders
                .filter { it.type == ProtectedSpanType.DOMAIN_TERM }
                .map(ProtectedSpan::original),
        )
        assertEquals(source, protected.restore(protected.encodedText))
    }

    @Test
    fun urlProtectionLeavesSentencePunctuationOutsideThePlaceholder() {
        val source =
            "Read https://northstar.example.org/brief?rev=4, then open " +
                "https://northstar.example.org/checkin."

        val protected = ProtectedSpanCodec.protect(source)
        val urls = protected.placeholders
            .filter { it.type == ProtectedSpanType.URL }
            .map(ProtectedSpan::original)

        assertEquals(
            listOf(
                "https://northstar.example.org/brief?rev=4",
                "https://northstar.example.org/checkin",
            ),
            urls,
        )
        assertTrue(protected.encodedText.contains("]], then open"))
        assertTrue(protected.encodedText.endsWith("]]."))
        assertEquals(source, protected.restore(protected.encodedText))
    }

    @Test
    fun longTitlePhraseIsNotMisclassifiedAsAName() {
        val source = "# project northstar: Five Page Field Readyness Brief"

        val protected = ProtectedSpanCodec.protect(source)

        assertTrue(protected.encodedText.contains("Readyness Brief"))
        assertFalse(
            protected.placeholders.any {
                it.type == ProtectedSpanType.NAME && "Readyness" in it.original
            },
        )
    }

    @Test
    fun sentenceLeadingDateAndPermissionMayAreClassifiedCorrectly() {
        val source =
            "On April 30, 2027, Jonah Patel will deliver kits. Only Mira Chen may access them."

        val protected = ProtectedSpanCodec.protect(source)

        assertTrue(
            protected.placeholders.any {
                it.type == ProtectedSpanType.DATE && it.original == "April 30, 2027"
            },
        )
        assertTrue(
            protected.placeholders.any {
                it.type == ProtectedSpanType.NAME && it.original == "Mira Chen"
            },
        )
        assertFalse(
            protected.placeholders.any {
                it.type == ProtectedSpanType.NAME && it.original.startsWith("On ")
            },
        )
        assertFalse(
            protected.placeholders.any {
                it.type == ProtectedSpanType.NAME && it.original.startsWith("Only ")
            },
        )
        assertFalse(
            protected.placeholders.any {
                it.type == ProtectedSpanType.DATE && it.original == "may"
            },
        )
        assertTrue(protected.encodedText.contains(" may access them."))
        assertEquals(source, protected.restore(protected.encodedText))
    }

    @Test
    fun currencySymbolAndAmountAreOneProtectedSpan() {
        val source = "The approved budget is $18,750 and the reserve is \u00A3500."

        val protected = ProtectedSpanCodec.protect(source)

        assertTrue(
            protected.placeholders.any {
                it.type == ProtectedSpanType.NUMBER && it.original == "$18,750"
            },
        )
        assertTrue(
            protected.placeholders.any {
                it.type == ProtectedSpanType.NUMBER && it.original == "\u00A3500"
            },
        )
        assertFalse(protected.encodedText.contains("$18,750"))
        assertEquals(source, protected.restore(protected.encodedText))
    }

    @Test
    fun paragraphChunkerUsesSoftWordBoundariesAndPreservesExactDocument() {
        val longParagraph = (1..420).joinToString(" ") { index -> "word$index" }
        val source = "Short first paragraph.\n\n$longParagraph\n\n- Keep this list together.\n- Keep this item too."
        val prepared = ProtectedSpanCodec.protect(source)

        val chunks = ParagraphChunker.chunk(prepared.encodedText)

        assertTrue(
            "chunks=${chunks.map { it.wordCount to it.text.take(24) }}",
            chunks.size >= 5,
        )
        val longChunks = chunks.filter { it.text.contains("word") }
        assertTrue(longChunks.all { it.wordCount in 100..180 })
        assertTrue(
            chunks.any {
                it.text.contains("Keep this list together.") &&
                    it.text.contains("Keep this item too.")
            },
        )
        assertEquals(
            prepared.encodedText,
            chunks.joinToString("") { it.text + it.separatorAfter },
        )
        chunks.forEachIndexed { index, chunk ->
            assertEquals(chunks.getOrNull(index - 1)?.text, chunk.previousContext)
            assertEquals(chunks.getOrNull(index + 1)?.text, chunk.nextContext)
        }
    }

    @Test
    fun paragraphChunkerKeepsFinalNewlineOutOfEditableText() {
        val source = "First paragraph.\n\nFinal paragraph.\n"

        val chunks = ParagraphChunker.chunk(source)

        assertEquals(2, chunks.size)
        assertEquals("Final paragraph.", chunks.last().text)
        assertEquals("\n", chunks.last().separatorAfter)
        assertEquals(source, chunks.joinToString("") { it.text + it.separatorAfter })
    }
}
