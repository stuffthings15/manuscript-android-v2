package com.echolink.mobile

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedPayloadEnvelope(
    val version: String,
    val iv: String,
    val ciphertext: String,
)

class AesGcmPayloadCipher(
    private val keyProvider: () -> SecretKey,
) {
    fun encrypt(plaintext: String): EncryptedPayloadEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore must generate the IV when randomized encryption is required.
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(ASSOCIATED_DATA)
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val iv = requireNotNull(cipher.iv)
        require(iv.size == IV_BYTES) { "Invalid AES-GCM IV from provider." }
        val encoder = Base64.getEncoder().withoutPadding()
        return EncryptedPayloadEnvelope(
            version = ENVELOPE_VERSION,
            iv = encoder.encodeToString(iv),
            ciphertext = encoder.encodeToString(encrypted),
        )
    }

    fun decrypt(envelope: EncryptedPayloadEnvelope): String {
        require(envelope.version == ENVELOPE_VERSION) { "Unsupported encrypted payload version." }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(envelope.iv)
        val encrypted = decoder.decode(envelope.ciphertext)
        require(iv.size == IV_BYTES) { "Invalid AES-GCM IV." }
        require(encrypted.size >= TAG_BITS / 8) { "Invalid AES-GCM ciphertext." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(ASSOCIATED_DATA)
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    companion object {
        const val ENVELOPE_VERSION = "aes-gcm-keystore-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val ASSOCIATED_DATA = "Echo-Link approved payload v1".toByteArray(StandardCharsets.UTF_8)
    }
}
