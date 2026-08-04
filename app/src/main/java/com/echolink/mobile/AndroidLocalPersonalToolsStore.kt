package com.echolink.mobile

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Android adapter. Durable preferences contain only the authenticated encrypted envelope. */
class AndroidLocalPersonalToolsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val repository = LocalPersonalToolsRepository(
        SharedPreferencesPersistence(preferences),
        LocalToolsCipher(AndroidPersonalToolsKeyProvider()::getOrCreate),
    )

    fun listVocabulary(): LocalToolsResult<List<LocalVocabularyEntry>> = repository.listVocabulary()
    fun listSnippets(): LocalToolsResult<List<LocalSnippet>> = repository.listSnippets()
    fun vocabularyMap(): LocalToolsResult<Map<String, String>> = repository.vocabularyMap()
    fun reviewDraft(transcript: String): LocalToolsResult<ReviewDraft> = repository.reviewDraft(transcript)
    fun expandSnippet(spokenText: String): LocalToolsResult<String?> = repository.expandSnippet(spokenText)

    fun upsertVocabulary(
        spokenForm: String,
        writtenForm: String,
        category: String = "custom",
    ): LocalToolsResult<LocalVocabularyEntry> =
        repository.upsertVocabulary(spokenForm, writtenForm, category)

    fun deleteVocabulary(spokenForm: String): LocalToolsResult<Boolean> =
        repository.deleteVocabulary(spokenForm)

    fun upsertSnippet(trigger: String, content: String): LocalToolsResult<LocalSnippet> =
        repository.upsertSnippet(trigger, content)

    fun deleteSnippet(trigger: String): LocalToolsResult<Boolean> =
        repository.deleteSnippet(trigger)

    fun clear(): LocalToolsResult<Boolean> = repository.clear()

    private class SharedPreferencesPersistence(
        private val preferences: SharedPreferences,
    ) : LocalToolsPersistence {
        override fun readEnvelope(): EncryptedPayloadEnvelope? {
            val fields = listOf(VERSION_KEY, IV_KEY, CIPHERTEXT_KEY)
            val present = fields.count(preferences::contains)
            if (present == 0) return null
            require(present == fields.size) { "Incomplete encrypted local tools envelope." }
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
        const val PREFERENCES_NAME = "echo_link_local_personal_tools_encrypted_v1"
        const val VERSION_KEY = "envelope_version"
        const val IV_KEY = "iv"
        const val CIPHERTEXT_KEY = "ciphertext"
    }
}

private class AndroidPersonalToolsKeyProvider {
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
        const val KEY_ALIAS = "echo_link_local_personal_tools_aes_v1"
    }
}
