package com.echolink.mobile

import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPersonalToolsTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun vocabularyCrudNormalizesAndExportsToReviewCleanup() {
        val repository = repository()

        assertTrue(repository.upsertVocabulary("  echo   link  ", " Echo-Link ", " product ").successful)
        assertTrue(repository.upsertVocabulary("ECHO LINK", "Echo-Link Mobile", "name").successful)

        val entries = repository.listVocabulary().value!!
        assertEquals(1, entries.size)
        assertEquals(LocalVocabularyEntry("ECHO LINK", "Echo-Link Mobile", "name"), entries.single())
        assertEquals(mapOf("ECHO LINK" to "Echo-Link Mobile"), repository.vocabularyMap().value)
        assertEquals("Echo-Link Mobile is ready.", repository.reviewDraft("echo link is ready").value!!.reviewedText)
        assertTrue(repository.deleteVocabulary(" echo link ").value!!)
        assertFalse(repository.deleteVocabulary("echo link").value!!)
        assertTrue(repository.listVocabulary().value!!.isEmpty())
    }

    @Test
    fun snippetsExpandOnlyExactLocalCommandsAndKeepContentExact() {
        val repository = repository()
        repository.upsertSnippet(" email   signature ", "  Regards,\nCurtis  ")

        assertEquals("Regards,\nCurtis", repository.expandSnippet("email signature").value)
        assertEquals("Regards,\nCurtis", repository.expandSnippet(" INSERT   SNIPPET  EMAIL signature ").value)
        assertEquals("Regards,\nCurtis", repository.expandSnippet("snippet email signature").value)
        assertNull(repository.expandSnippet("please insert snippet email signature").value)
        assertNull(repository.expandSnippet("email signature now").value)

        repository.upsertSnippet("EMAIL SIGNATURE", "Updated")
        assertEquals(1, repository.listSnippets().value!!.size)
        assertEquals("Updated", repository.expandSnippet("email signature").value)
        assertTrue(repository.deleteSnippet("Email Signature").value!!)
    }

    @Test
    fun durableEnvelopeContainsNoPlaintextAndUsesUniqueIv() {
        val persistence = MemoryPersistence()
        val cipher = LocalToolsCipher { key }
        val repository = LocalPersonalToolsRepository(persistence, cipher)
        repository.upsertVocabulary("private codename", "Project Nightbird")
        repository.upsertSnippet("private signoff", "Private regards")

        val first = persistence.envelope!!
        val encryptedBytes = String(Base64.getDecoder().decode(first.ciphertext), Charsets.ISO_8859_1)
        assertFalse(first.ciphertext.contains("Nightbird"))
        assertFalse(first.ciphertext.contains("Private"))
        assertFalse(encryptedBytes.contains("Nightbird"))
        assertFalse(encryptedBytes.contains("Private"))

        repository.upsertSnippet("second", "Same data, new envelope")
        assertFalse(first.iv == persistence.envelope!!.iv)
    }

    @Test
    fun ciphertextCorruptionFailsClosedAndClearsStorage() {
        val persistence = MemoryPersistence()
        val repository = LocalPersonalToolsRepository(persistence, LocalToolsCipher { key })
        repository.upsertSnippet("secret", "Never return this")
        val bytes = Base64.getDecoder().decode(persistence.envelope!!.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        persistence.envelope = persistence.envelope!!.copy(
            ciphertext = Base64.getEncoder().withoutPadding().encodeToString(bytes),
        )

        val result = repository.listSnippets()

        assertNull(result.value)
        assertEquals(LocalPersonalToolsRepository.READ_ERROR, result.error)
        assertNull(persistence.envelope)
    }

    @Test
    fun wrongKeyFailsClosedAndClearsStorage() {
        val persistence = MemoryPersistence()
        val writer = LocalPersonalToolsRepository(persistence, LocalToolsCipher { key })
        writer.upsertVocabulary("secret", "PrivateValue")
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        val result = LocalPersonalToolsRepository(
            persistence,
            LocalToolsCipher { wrongKey },
        ).vocabularyMap()

        assertNull(result.value)
        assertEquals(LocalPersonalToolsRepository.READ_ERROR, result.error)
        assertNull(persistence.envelope)
    }

    @Test
    fun authenticatedButMalformedJsonFailsClosed() {
        val persistence = MemoryPersistence()
        val cipher = LocalToolsCipher { key }
        persistence.envelope = cipher.encrypt("{\"schema\":\"wrong\",\"vocabulary\":[],\"snippets\":[]}")

        val result = LocalPersonalToolsRepository(persistence, cipher).listVocabulary()

        assertNull(result.value)
        assertEquals(LocalPersonalToolsRepository.READ_ERROR, result.error)
        assertNull(persistence.envelope)
    }

    @Test
    fun invalidInputsAndEntryLimitsDoNotCreateNewPlaintextState() {
        val repository = repository()
        assertFalse(repository.upsertVocabulary("", "value").successful)
        assertFalse(repository.upsertVocabulary("name", "").successful)
        assertFalse(repository.upsertSnippet("trigger", "\u0000").successful)
        assertFalse(repository.expandSnippet("x".repeat(LocalPersonalToolsPolicy.MAX_SPOKEN_CHARS + 33)).successful)

        val fullVocabulary = (0 until LocalPersonalToolsPolicy.MAX_VOCABULARY_ENTRIES).map {
            LocalVocabularyEntry("spoken $it", "Written$it")
        }
        val persistence = MemoryPersistence()
        val cipher = LocalToolsCipher { key }
        persistence.envelope = cipher.encrypt(
            LocalPersonalToolsCodec.encode(LocalPersonalToolsSnapshot(vocabulary = fullVocabulary)),
        )
        val bounded = LocalPersonalToolsRepository(persistence, cipher)

        val result = bounded.upsertVocabulary("one more", "No")

        assertEquals(LocalPersonalToolsRepository.LIMIT_ERROR, result.error)
        assertEquals(LocalPersonalToolsPolicy.MAX_VOCABULARY_ENTRIES, bounded.listVocabulary().value!!.size)
    }

    @Test
    fun duplicateOrUnknownJsonFieldsAreRejected() {
        val duplicate = """
            {
              "schema":"echo-link-local-personal-tools-v1",
              "vocabulary":[
                {"spoken_form":"Name","written_form":"One","category":"custom"},
                {"spoken_form":"name","written_form":"Two","category":"custom"}
              ],
              "snippets":[]
            }
        """.trimIndent()
        assertFails { LocalPersonalToolsCodec.decode(duplicate) }
        assertFails {
            LocalPersonalToolsCodec.decode(
                "{\"schema\":\"echo-link-local-personal-tools-v1\",\"vocabulary\":[]," +
                    "\"snippets\":[],\"extra\":true}",
            )
        }
    }

    @Test
    fun failedWriteClearsEncryptedStateAndReturnsNoValue() {
        val persistence = MemoryPersistence(failWrite = true)
        val repository = LocalPersonalToolsRepository(persistence, LocalToolsCipher { key })

        val result = repository.upsertSnippet("trigger", "private content")

        assertNull(result.value)
        assertEquals(LocalPersonalToolsRepository.WRITE_ERROR, result.error)
        assertNull(persistence.envelope)
    }

    private fun repository(
        persistence: MemoryPersistence = MemoryPersistence(),
    ): LocalPersonalToolsRepository = LocalPersonalToolsRepository(
        persistence,
        LocalToolsCipher { key },
    )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected failure")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private class MemoryPersistence(
        var failWrite: Boolean = false,
        var envelope: EncryptedPayloadEnvelope? = null,
    ) : LocalToolsPersistence {
        override fun readEnvelope(): EncryptedPayloadEnvelope? = envelope

        override fun writeEnvelope(envelope: EncryptedPayloadEnvelope): Boolean {
            if (failWrite) return false
            this.envelope = envelope
            return true
        }

        override fun clear(): Boolean {
            envelope = null
            return true
        }
    }
}
