package com.echolink.mobile

data class PayloadReadResult(
    val text: String? = null,
    val error: String? = null,
) {
    val available: Boolean get() = text != null
}

interface ApprovedPayloadPersistence {
    fun readEnvelope(): EncryptedPayloadEnvelope?
    fun writeEnvelope(envelope: EncryptedPayloadEnvelope): Boolean
    fun clear(): Boolean
}

class ApprovedPayloadVault(
    private val persistence: ApprovedPayloadPersistence,
    private val cipher: AesGcmPayloadCipher,
) {
    @Synchronized
    fun approve(raw: String): ApprovalResult {
        val validated = ApprovedTextPolicy.validate(raw)
        val approved = validated.text ?: return validated

        return try {
            val envelope = cipher.encrypt(approved)
            if (!persistence.writeEnvelope(envelope)) {
                persistence.clear()
                ApprovalResult(error = SECURE_WRITE_ERROR)
            } else {
                validated
            }
        } catch (_: Exception) {
            persistence.clear()
            ApprovalResult(error = SECURE_WRITE_ERROR)
        }
    }

    @Synchronized
    fun read(): PayloadReadResult {
        return try {
            val envelope = persistence.readEnvelope() ?: return PayloadReadResult()
            val plaintext = cipher.decrypt(envelope)
            val validated = ApprovedTextPolicy.validate(plaintext)
            if (!validated.accepted || validated.text != plaintext) {
                throw IllegalArgumentException("Decrypted payload failed validation.")
            }
            PayloadReadResult(text = plaintext)
        } catch (_: Exception) {
            persistence.clear()
            PayloadReadResult(error = SECURE_READ_ERROR)
        }
    }

    /** Clears durable data before releasing plaintext for one insertion attempt. */
    @Synchronized
    fun consume(): PayloadReadResult {
        val result = read()
        if (!result.available) return result
        if (!persistence.clear()) return PayloadReadResult(error = SECURE_CONSUME_ERROR)
        return result
    }

    @Synchronized
    fun clear(): Boolean = persistence.clear()

    companion object {
        const val SECURE_WRITE_ERROR = "Secure storage failed. Nothing was approved."
        const val SECURE_READ_ERROR = "Approved text could not be decrypted and was cleared. Approve it again."
        const val SECURE_CONSUME_ERROR = "Approved text could not be consumed safely. Nothing was inserted."
    }
}
