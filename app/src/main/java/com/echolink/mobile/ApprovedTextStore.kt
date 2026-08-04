package com.echolink.mobile

import android.content.Context
import android.content.SharedPreferences

class ApprovedTextStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyPlaintextPurged = purgeLegacyPlaintext()
    private val vault = ApprovedPayloadVault(
        persistence = SharedPreferencesPersistence(preferences),
        cipher = AesGcmPayloadCipher(AndroidKeystoreKeyProvider()::getOrCreate),
    )

    fun approve(raw: String): ApprovalResult {
        if (!legacyPlaintextPurged) {
            vault.clear()
            return ApprovalResult(error = LEGACY_PURGE_ERROR)
        }
        return vault.approve(raw)
    }

    fun read(): PayloadReadResult {
        if (!legacyPlaintextPurged) {
            vault.clear()
            return PayloadReadResult(error = LEGACY_PURGE_ERROR)
        }
        return vault.read()
    }

    fun consume(): PayloadReadResult {
        if (!legacyPlaintextPurged) {
            vault.clear()
            return PayloadReadResult(error = LEGACY_PURGE_ERROR)
        }
        return vault.consume()
    }

    fun clear(): Boolean = vault.clear()

    private fun purgeLegacyPlaintext(): Boolean {
        val legacy = appContext.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) {
            appContext.deleteSharedPreferences(LEGACY_PREFERENCES_NAME)
            return true
        }
        val cleared = legacy.edit().clear().commit()
        if (cleared) appContext.deleteSharedPreferences(LEGACY_PREFERENCES_NAME)
        return cleared
    }

    private class SharedPreferencesPersistence(
        private val preferences: SharedPreferences,
    ) : ApprovedPayloadPersistence {
        override fun readEnvelope(): EncryptedPayloadEnvelope? {
            val fields = listOf(VERSION_KEY, IV_KEY, CIPHERTEXT_KEY)
            val present = fields.count(preferences::contains)
            if (present == 0) return null
            require(present == fields.size) { "Incomplete encrypted payload." }
            return EncryptedPayloadEnvelope(
                version = requireNotNull(preferences.getString(VERSION_KEY, null)),
                iv = requireNotNull(preferences.getString(IV_KEY, null)),
                ciphertext = requireNotNull(preferences.getString(CIPHERTEXT_KEY, null)),
            )
        }

        override fun writeEnvelope(envelope: EncryptedPayloadEnvelope): Boolean = preferences.edit()
            .clear()
            .putString(VERSION_KEY, envelope.version)
            .putString(IV_KEY, envelope.iv)
            .putString(CIPHERTEXT_KEY, envelope.ciphertext)
            .commit()

        override fun clear(): Boolean = preferences.edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "echo_link_approved_payload_encrypted_v1"
        const val VERSION_KEY = "envelope_version"
        const val IV_KEY = "iv"
        const val CIPHERTEXT_KEY = "ciphertext"
        const val LEGACY_PREFERENCES_NAME = "echo_link_approved_text_v1"
        const val LEGACY_PURGE_ERROR = "Old plaintext storage could not be cleared. Secure approval is blocked."
    }
}
