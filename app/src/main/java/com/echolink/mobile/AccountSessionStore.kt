package com.echolink.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class AccountSessionEnvelope(
    val version: String,
    val iv: String,
    val ciphertext: String,
)

internal class AccountSessionCipher(
    private val keyProvider: () -> SecretKey,
) {
    fun encrypt(session: AccountSession): AccountSessionEnvelope {
        val plaintext = buildJsonObject {
            put("accountId", session.accountId)
            put("email", session.email)
            put("displayName", session.displayName)
            put("accessToken", session.accessToken)
            put("refreshToken", session.refreshToken)
            put("expiresIn", session.expiresIn)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(ASSOCIATED_DATA)
        val encrypted = cipher.doFinal(plaintext)
        val encoder = Base64.getEncoder().withoutPadding()
        return AccountSessionEnvelope(
            version = VERSION,
            iv = encoder.encodeToString(requireNotNull(cipher.iv)),
            ciphertext = encoder.encodeToString(encrypted),
        )
    }

    fun decrypt(envelope: AccountSessionEnvelope): AccountSession {
        require(envelope.version == VERSION) { "Unsupported account session." }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(envelope.iv)
        val encrypted = decoder.decode(envelope.ciphertext)
        require(iv.size == IV_BYTES && encrypted.size >= TAG_BITS / 8) {
            "Invalid account session envelope."
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(ASSOCIATED_DATA)
        val root = Json.parseToJsonElement(
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8),
        ).jsonObject
        return AccountSession(
            accountId = root.getValue("accountId").jsonPrimitive.content,
            email = root.getValue("email").jsonPrimitive.content,
            displayName = root.getValue("displayName").jsonPrimitive.content,
            accessToken = root.getValue("accessToken").jsonPrimitive.content,
            refreshToken = root.getValue("refreshToken").jsonPrimitive.content,
            expiresIn = root.getValue("expiresIn").jsonPrimitive.int,
        )
    }

    private companion object {
        const val VERSION = "account-session-aes-gcm-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val ASSOCIATED_DATA = "Manuscript account session v1".toByteArray(StandardCharsets.UTF_8)
    }
}

internal class AndroidAccountSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val cipher = AccountSessionCipher(AndroidAccountSessionKeyProvider()::getOrCreate)

    fun save(session: AccountSession): Boolean {
        val envelope = cipher.encrypt(session)
        return preferences.edit()
            .clear()
            .putString(VERSION_KEY, envelope.version)
            .putString(IV_KEY, envelope.iv)
            .putString(CIPHERTEXT_KEY, envelope.ciphertext)
            .commit()
    }

    fun load(): AccountSession? {
        val version = preferences.getString(VERSION_KEY, null) ?: return null
        val iv = preferences.getString(IV_KEY, null) ?: return clearCorrupt()
        val ciphertext = preferences.getString(CIPHERTEXT_KEY, null) ?: return clearCorrupt()
        return runCatching {
            cipher.decrypt(AccountSessionEnvelope(version, iv, ciphertext))
        }.getOrElse { clearCorrupt() }
    }

    fun clear(): Boolean = preferences.edit().clear().commit()

    private fun clearCorrupt(): AccountSession? {
        clear()
        return null
    }

    private companion object {
        const val PREFERENCES = "manuscript_account_session_encrypted_v1"
        const val VERSION_KEY = "version"
        const val IV_KEY = "iv"
        const val CIPHERTEXT_KEY = "ciphertext"
    }
}

private class AndroidAccountSessionKeyProvider {
    @Synchronized
    fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "manuscript_account_session_aes_v1"
    }
}