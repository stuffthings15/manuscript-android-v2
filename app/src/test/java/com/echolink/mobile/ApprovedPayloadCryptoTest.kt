package com.echolink.mobile

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedPayloadCryptoTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmPayloadCipher(keyProvider = { key })

    @Test
    fun encryptsAndAuthenticatesUnicodeText() {
        val plaintext = "Review me: café 日本語 🔒"
        val envelope = cipher.encrypt(plaintext)

        assertEquals(AesGcmPayloadCipher.ENVELOPE_VERSION, envelope.version)
        assertNotEquals(plaintext, envelope.ciphertext)
        assertEquals(12, Base64.getDecoder().decode(envelope.iv).size)
        assertEquals(plaintext, cipher.decrypt(envelope))
    }

    @Test
    fun usesAUniqueIvForEachApproval() {
        val first = cipher.encrypt("same text")
        val second = cipher.encrypt("same text")

        assertNotEquals(first.iv, second.iv)
        assertNotEquals(first.ciphertext, second.ciphertext)
    }

    @Test
    fun rejectsCiphertextTampering() {
        val envelope = cipher.encrypt("approved once")
        val bytes = Base64.getDecoder().decode(envelope.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = envelope.copy(
            ciphertext = Base64.getEncoder().withoutPadding().encodeToString(bytes),
        )

        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(tampered) }
    }

    @Test
    fun rejectsUnknownEnvelopeVersion() {
        val envelope = cipher.encrypt("approved once").copy(version = "future-version")

        val error = assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(envelope) }
        assertTrue(error.message.orEmpty().contains("Unsupported"))
    }
}
