package com.echolink.mobile

import android.content.Context
import android.content.SharedPreferences

data class EditorDraft(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val scrollY: Int,
)

data class PersistedEditorDraft(
    val encryptedText: EncryptedJobText,
    val selectionStart: Int,
    val selectionEnd: Int,
    val scrollY: Int,
)

interface EditorDraftPersistence {
    fun read(): PersistedEditorDraft?
    fun write(value: PersistedEditorDraft): Boolean
    fun clear(): Boolean
}

class EditorDraftVault(
    private val persistence: EditorDraftPersistence,
    private val cipher: AiJobTextCipher,
) {
    @Synchronized
    fun save(draft: EditorDraft): Boolean {
        if (draft.text.isEmpty()) return persistence.clear()
        return try {
            val encryptedText = cipher.encrypt(draft.text)
            if (
                persistence.write(
                    PersistedEditorDraft(
                        encryptedText = encryptedText,
                        selectionStart = draft.selectionStart.coerceIn(0, draft.text.length),
                        selectionEnd = draft.selectionEnd.coerceIn(0, draft.text.length),
                        scrollY = draft.scrollY.coerceAtLeast(0),
                    ),
                )
            ) {
                true
            } else {
                persistence.clear()
                false
            }
        } catch (_: Exception) {
            persistence.clear()
            false
        }
    }

    @Synchronized
    fun load(): EditorDraft? {
        return try {
            val stored = persistence.read() ?: return null
            val text = cipher.decrypt(stored.encryptedText)
            val start = stored.selectionStart.coerceIn(0, text.length)
            val end = stored.selectionEnd.coerceIn(0, text.length)
            EditorDraft(
                text = text,
                selectionStart = minOf(start, end),
                selectionEnd = maxOf(start, end),
                scrollY = stored.scrollY.coerceAtLeast(0),
            )
        } catch (_: Exception) {
            persistence.clear()
            null
        }
    }

    @Synchronized
    fun clear(): Boolean = persistence.clear()
}

class EncryptedEditorDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val vault = EditorDraftVault(
        persistence = SharedPreferencesDraftPersistence(preferences),
        cipher = AndroidKeystoreAiJobTextCipher(KEY_ALIAS),
    )

    fun save(draft: EditorDraft): Boolean = vault.save(draft)

    fun load(): EditorDraft? = vault.load()

    fun clear(): Boolean = vault.clear()

    private class SharedPreferencesDraftPersistence(
        private val preferences: SharedPreferences,
    ) : EditorDraftPersistence {
        override fun read(): PersistedEditorDraft? {
            val requiredKeys = listOf(
                IV_KEY,
                CIPHERTEXT_KEY,
                SELECTION_START_KEY,
                SELECTION_END_KEY,
                SCROLL_Y_KEY,
            )
            val present = requiredKeys.count(preferences::contains)
            if (present == 0) return null
            require(present == requiredKeys.size) { "Incomplete encrypted editor draft." }
            return PersistedEditorDraft(
                encryptedText = EncryptedJobText(
                    ivBase64 = requireNotNull(preferences.getString(IV_KEY, null)),
                    ciphertextBase64 = requireNotNull(
                        preferences.getString(CIPHERTEXT_KEY, null),
                    ),
                ),
                selectionStart = preferences.getInt(SELECTION_START_KEY, 0),
                selectionEnd = preferences.getInt(SELECTION_END_KEY, 0),
                scrollY = preferences.getInt(SCROLL_Y_KEY, 0),
            )
        }

        override fun write(value: PersistedEditorDraft): Boolean = preferences.edit()
            .clear()
            .putString(IV_KEY, value.encryptedText.ivBase64)
            .putString(CIPHERTEXT_KEY, value.encryptedText.ciphertextBase64)
            .putInt(SELECTION_START_KEY, value.selectionStart)
            .putInt(SELECTION_END_KEY, value.selectionEnd)
            .putInt(SCROLL_Y_KEY, value.scrollY)
            .commit()

        override fun clear(): Boolean = preferences.edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "manuscript_main_editor_draft_v1"
        const val KEY_ALIAS = "manuscript_main_editor_draft_aes_v1"
        const val IV_KEY = "text_iv"
        const val CIPHERTEXT_KEY = "text_ciphertext"
        const val SELECTION_START_KEY = "selection_start"
        const val SELECTION_END_KEY = "selection_end"
        const val SCROLL_Y_KEY = "scroll_y"
    }
}
