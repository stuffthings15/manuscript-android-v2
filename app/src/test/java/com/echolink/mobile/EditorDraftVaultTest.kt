package com.echolink.mobile

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDraftVaultTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val testCipher = AesGcmPayloadCipher(keyProvider = { key })
    private val cipher = object : AiJobTextCipher {
        override fun encrypt(plainText: String): EncryptedJobText {
            val encrypted = testCipher.encrypt(plainText)
            return EncryptedJobText(encrypted.iv, encrypted.ciphertext)
        }

        override fun decrypt(value: EncryptedJobText): String = testCipher.decrypt(
            EncryptedPayloadEnvelope(
                AesGcmPayloadCipher.ENVELOPE_VERSION,
                value.ivBase64,
                value.ciphertextBase64,
            ),
        )
    }

    @Test
    fun savesTextEncryptedAndRestoresCursorAndScroll() {
        val persistence = MemoryDraftPersistence()
        val vault = EditorDraftVault(persistence, cipher)
        val draft = EditorDraft(
            text = "Private unfinished writing",
            selectionStart = 7,
            selectionEnd = 16,
            scrollY = 240,
        )

        assertTrue(vault.save(draft))

        assertFalse(persistence.value!!.encryptedText.ciphertextBase64.contains(draft.text))
        assertEquals(draft, vault.load())
    }

    @Test
    fun emptyDraftClearsPreviousState() {
        val persistence = MemoryDraftPersistence()
        val vault = EditorDraftVault(persistence, cipher)
        vault.save(EditorDraft("Keep this", 4, 4, 10))

        assertTrue(vault.save(EditorDraft("", 0, 0, 0)))

        assertNull(vault.load())
        assertNull(persistence.value)
    }

    @Test
    fun invalidCursorStateIsClampedOnLoad() {
        val persistence = MemoryDraftPersistence()
        val vault = EditorDraftVault(persistence, cipher)
        vault.save(EditorDraft("Short", 2, 4, 8))
        persistence.value = persistence.value!!.copy(
            selectionStart = -12,
            selectionEnd = 999,
            scrollY = -4,
        )

        assertEquals(EditorDraft("Short", 0, 5, 0), vault.load())
    }

    @Test
    fun unreadableStateIsClearedWithoutReleasingText() {
        val persistence = MemoryDraftPersistence()
        val vault = EditorDraftVault(persistence, cipher)
        vault.save(EditorDraft("Do not leak", 3, 3, 0))
        val stored = requireNotNull(persistence.value)
        persistence.value = stored.copy(
            encryptedText = stored.encryptedText.copy(ciphertextBase64 = "AAAA"),
        )

        assertNull(vault.load())
        assertNull(persistence.value)
    }

    private class MemoryDraftPersistence(
        var value: PersistedEditorDraft? = null,
    ) : EditorDraftPersistence {
        override fun read(): PersistedEditorDraft? = value

        override fun write(value: PersistedEditorDraft): Boolean {
            this.value = value
            return true
        }

        override fun clear(): Boolean {
            value = null
            return true
        }
    }
}
