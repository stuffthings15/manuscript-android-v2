package com.echolink.mobile

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedPayloadVaultTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmPayloadCipher(keyProvider = { key })

    @Test
    fun storesCiphertextAndConsumesItExactlyOnce() {
        val persistence = MemoryPersistence()
        val vault = ApprovedPayloadVault(persistence, cipher)

        assertTrue(vault.approve("one approved payload").accepted)
        assertFalse(persistence.envelope!!.ciphertext.contains("one approved payload"))
        assertEquals("one approved payload", vault.read().text)
        assertEquals("one approved payload", vault.consume().text)
        assertNull(vault.consume().text)
        assertNull(persistence.envelope)
    }

    @Test
    fun failedConsumeNeverReleasesPlaintext() {
        val persistence = MemoryPersistence()
        val vault = ApprovedPayloadVault(persistence, cipher)
        vault.approve("do not release")
        persistence.failClear = true

        val result = vault.consume()

        assertNull(result.text)
        assertEquals(ApprovedPayloadVault.SECURE_CONSUME_ERROR, result.error)
    }

    @Test
    fun authenticationFailureClearsTheStoredEnvelope() {
        val persistence = MemoryPersistence()
        val vault = ApprovedPayloadVault(persistence, cipher)
        vault.approve("protected")
        persistence.envelope = persistence.envelope!!.copy(ciphertext = "AAAA")

        val result = vault.read()

        assertNull(result.text)
        assertEquals(ApprovedPayloadVault.SECURE_READ_ERROR, result.error)
        assertNull(persistence.envelope)
    }

    @Test
    fun failedEncryptedWriteApprovesNothing() {
        val persistence = MemoryPersistence(failWrite = true)
        val vault = ApprovedPayloadVault(persistence, cipher)

        val result = vault.approve("cannot persist")

        assertFalse(result.accepted)
        assertEquals(ApprovedPayloadVault.SECURE_WRITE_ERROR, result.error)
        assertNull(persistence.envelope)
    }

    private class MemoryPersistence(
        var failWrite: Boolean = false,
        var failClear: Boolean = false,
        var envelope: EncryptedPayloadEnvelope? = null,
    ) : ApprovedPayloadPersistence {
        override fun readEnvelope(): EncryptedPayloadEnvelope? = envelope

        override fun writeEnvelope(envelope: EncryptedPayloadEnvelope): Boolean {
            if (failWrite) return false
            this.envelope = envelope
            return true
        }

        override fun clear(): Boolean {
            if (failClear) return false
            envelope = null
            return true
        }
    }
}
