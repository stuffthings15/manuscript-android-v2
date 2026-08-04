package com.echolink.mobile

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class LocalVocabularyEntry(
    val spokenForm: String,
    val writtenForm: String,
    val category: String = "custom",
)

data class LocalSnippet(
    val trigger: String,
    val content: String,
)

data class LocalPersonalToolsSnapshot(
    val vocabulary: List<LocalVocabularyEntry> = emptyList(),
    val snippets: List<LocalSnippet> = emptyList(),
)

data class LocalToolsResult<T>(
    val value: T? = null,
    val error: String? = null,
) {
    val successful: Boolean get() = error == null
}

interface LocalToolsPersistence {
    fun readEnvelope(): EncryptedPayloadEnvelope?
    fun writeEnvelope(envelope: EncryptedPayloadEnvelope): Boolean
    fun clear(): Boolean
}

/** AES-GCM envelope with a personal-tools-specific authenticated context. */
class LocalToolsCipher(
    private val keyProvider: () -> SecretKey,
) {
    fun encrypt(plaintext: String): EncryptedPayloadEnvelope {
        val bytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= LocalPersonalToolsPolicy.MAX_JSON_BYTES) { "Personal tools data is too large." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(ASSOCIATED_DATA)
        val encrypted = cipher.doFinal(bytes)
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
        require(envelope.version == ENVELOPE_VERSION) { "Unsupported local tools envelope version." }
        require(envelope.iv.length <= MAX_IV_BASE64_CHARS) { "Invalid AES-GCM IV." }
        require(envelope.ciphertext.length <= MAX_CIPHERTEXT_BASE64_CHARS) {
            "Encrypted personal tools data is too large."
        }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(envelope.iv)
        val encrypted = decoder.decode(envelope.ciphertext)
        require(iv.size == IV_BYTES) { "Invalid AES-GCM IV." }
        require(encrypted.size in (TAG_BYTES + 1)..(LocalPersonalToolsPolicy.MAX_JSON_BYTES + TAG_BYTES)) {
            "Invalid AES-GCM ciphertext."
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(ASSOCIATED_DATA)
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    companion object {
        const val ENVELOPE_VERSION = "echo-link-personal-tools-aes-gcm-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val MAX_IV_BASE64_CHARS = 32
        private const val MAX_CIPHERTEXT_BASE64_CHARS = 1_400_000
        private val ASSOCIATED_DATA = "Echo-Link local personal tools v1".toByteArray(StandardCharsets.UTF_8)
    }
}

object LocalPersonalToolsPolicy {
    const val MAX_VOCABULARY_ENTRIES = 1_000
    const val MAX_SNIPPETS = 500
    const val MAX_SPOKEN_CHARS = 200
    const val MAX_WRITTEN_CHARS = 500
    const val MAX_CATEGORY_CHARS = 50
    const val MAX_SNIPPET_CHARS = 20_000
    const val MAX_JSON_BYTES = 1_000_000

    fun normalizeSpoken(value: String, field: String): String {
        val normalized = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
        require(normalized.isNotEmpty()) { "$field is required." }
        require(normalized.length <= MAX_SPOKEN_CHARS) { "$field is too long." }
        require(normalized.none { it.isISOControl() }) { "$field contains control characters." }
        return normalized
    }

    fun validateWritten(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Written form is required." }
        require(normalized.length <= MAX_WRITTEN_CHARS) { "Written form is too long." }
        require(normalized.none { it.isISOControl() }) { "Written form contains control characters." }
        return normalized
    }

    fun validateCategory(value: String): String {
        val normalized = value.trim().ifEmpty { "custom" }
        require(normalized.length <= MAX_CATEGORY_CHARS) { "Category is too long." }
        require(normalized.none { it.isISOControl() }) { "Category contains control characters." }
        return normalized
    }

    fun validateSnippetContent(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Snippet content is required." }
        require(normalized.length <= MAX_SNIPPET_CHARS) { "Snippet content is too long." }
        require(normalized.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            "Snippet content contains unsupported control characters."
        }
        return normalized
    }

    fun validate(snapshot: LocalPersonalToolsSnapshot): LocalPersonalToolsSnapshot {
        require(snapshot.vocabulary.size <= MAX_VOCABULARY_ENTRIES) { "Vocabulary is too large." }
        require(snapshot.snippets.size <= MAX_SNIPPETS) { "Snippet list is too large." }
        val vocabularyKeys = mutableSetOf<String>()
        val vocabulary = snapshot.vocabulary.map { entry ->
            val normalized = LocalVocabularyEntry(
                spokenForm = normalizeSpoken(entry.spokenForm, "Spoken form"),
                writtenForm = validateWritten(entry.writtenForm),
                category = validateCategory(entry.category),
            )
            require(vocabularyKeys.add(key(normalized.spokenForm))) { "Duplicate vocabulary spoken form." }
            normalized
        }
        val snippetKeys = mutableSetOf<String>()
        val snippets = snapshot.snippets.map { snippet ->
            val normalized = LocalSnippet(
                trigger = normalizeSpoken(snippet.trigger, "Snippet trigger"),
                content = validateSnippetContent(snippet.content),
            )
            require(snippetKeys.add(key(normalized.trigger))) { "Duplicate snippet trigger." }
            normalized
        }
        return LocalPersonalToolsSnapshot(vocabulary, snippets)
    }

    fun key(value: String): String = value.lowercase(Locale.ROOT)
}

object LocalPersonalToolsCodec {
    private const val SCHEMA = "echo-link-local-personal-tools-v1"
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }

    fun encode(snapshot: LocalPersonalToolsSnapshot): String {
        val validated = LocalPersonalToolsPolicy.validate(snapshot)
        val root = buildJsonObject {
            put("schema", JsonPrimitive(SCHEMA))
            put("vocabulary", buildJsonArray {
                validated.vocabulary.forEach { entry ->
                    add(buildJsonObject {
                        put("spoken_form", JsonPrimitive(entry.spokenForm))
                        put("written_form", JsonPrimitive(entry.writtenForm))
                        put("category", JsonPrimitive(entry.category))
                    })
                }
            })
            put("snippets", buildJsonArray {
                validated.snippets.forEach { snippet ->
                    add(buildJsonObject {
                        put("trigger", JsonPrimitive(snippet.trigger))
                        put("content", JsonPrimitive(snippet.content))
                    })
                }
            })
        }
        val encoded = root.toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= LocalPersonalToolsPolicy.MAX_JSON_BYTES) {
            "Personal tools data is too large."
        }
        return encoded
    }

    fun decode(raw: String): LocalPersonalToolsSnapshot {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= LocalPersonalToolsPolicy.MAX_JSON_BYTES) {
            "Personal tools data is too large."
        }
        val root = json.parseToJsonElement(raw).jsonObject
        requireExactKeys(root, setOf("schema", "vocabulary", "snippets"))
        require(requireString(root.getValue("schema")) == SCHEMA) { "Unsupported personal tools schema." }
        val vocabulary = requireArray(root.getValue("vocabulary")).map { element ->
            val item = element.jsonObject
            requireExactKeys(item, setOf("spoken_form", "written_form", "category"))
            LocalVocabularyEntry(
                spokenForm = requireString(item.getValue("spoken_form")),
                writtenForm = requireString(item.getValue("written_form")),
                category = requireString(item.getValue("category")),
            )
        }
        val snippets = requireArray(root.getValue("snippets")).map { element ->
            val item = element.jsonObject
            requireExactKeys(item, setOf("trigger", "content"))
            LocalSnippet(
                trigger = requireString(item.getValue("trigger")),
                content = requireString(item.getValue("content")),
            )
        }
        return LocalPersonalToolsPolicy.validate(LocalPersonalToolsSnapshot(vocabulary, snippets))
    }

    private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
        require(value.keys == expected) { "Personal tools JSON keys are invalid." }
    }

    private fun requireString(value: kotlinx.serialization.json.JsonElement): String {
        val primitive = value.jsonPrimitive
        require(primitive.isString) { "Personal tools JSON value must be text." }
        return primitive.content
    }

    private fun requireArray(value: kotlinx.serialization.json.JsonElement): JsonArray = value.jsonArray
}

/**
 * Thread-safe encrypted CRUD for local vocabulary and snippets.
 *
 * Reads return no data on authentication, key, JSON, or storage failure. Invalid durable data
 * is cleared so plaintext cannot leak through a fallback path.
 */
class LocalPersonalToolsRepository(
    private val persistence: LocalToolsPersistence,
    private val cipher: LocalToolsCipher,
) {
    @Synchronized
    fun listVocabulary(): LocalToolsResult<List<LocalVocabularyEntry>> =
        readSnapshot().map { it.vocabulary.toList() }

    @Synchronized
    fun listSnippets(): LocalToolsResult<List<LocalSnippet>> =
        readSnapshot().map { it.snippets.toList() }

    @Synchronized
    fun vocabularyMap(): LocalToolsResult<Map<String, String>> = readSnapshot().map { snapshot ->
        linkedMapOf<String, String>().apply {
            snapshot.vocabulary.forEach { put(it.spokenForm, it.writtenForm) }
        }
    }

    @Synchronized
    fun reviewDraft(transcript: String): LocalToolsResult<ReviewDraft> {
        val vocabulary = vocabularyMap()
        val entries = vocabulary.value ?: return LocalToolsResult(error = vocabulary.error)
        return try {
            LocalToolsResult(value = ReviewDraft.fromTranscript(transcript, entries))
        } catch (error: IllegalArgumentException) {
            invalid(error)
        }
    }

    @Synchronized
    fun expandSnippet(spokenText: String): LocalToolsResult<String?> {
        val normalized = try {
            normalizeSnippetCommand(spokenText)
        } catch (error: IllegalArgumentException) {
            return invalid(error)
        }
        return readSnapshot().map { snapshot ->
            snapshot.snippets.firstOrNull {
                LocalPersonalToolsPolicy.key(it.trigger) == LocalPersonalToolsPolicy.key(normalized)
            }?.content
        }
    }

    @Synchronized
    fun upsertVocabulary(
        spokenForm: String,
        writtenForm: String,
        category: String = "custom",
    ): LocalToolsResult<LocalVocabularyEntry> {
        val entry = try {
            LocalVocabularyEntry(
                LocalPersonalToolsPolicy.normalizeSpoken(spokenForm, "Spoken form"),
                LocalPersonalToolsPolicy.validateWritten(writtenForm),
                LocalPersonalToolsPolicy.validateCategory(category),
            )
        } catch (error: IllegalArgumentException) {
            return invalid(error)
        }
        val current = readSnapshot()
        val snapshot = current.value ?: return LocalToolsResult(error = current.error)
        val items = snapshot.vocabulary.toMutableList()
        val index = items.indexOfFirst {
            LocalPersonalToolsPolicy.key(it.spokenForm) == LocalPersonalToolsPolicy.key(entry.spokenForm)
        }
        if (index >= 0) {
            items[index] = entry
        } else {
            if (items.size >= LocalPersonalToolsPolicy.MAX_VOCABULARY_ENTRIES) {
                return LocalToolsResult(error = LIMIT_ERROR)
            }
            items += entry
        }
        return writeSnapshot(snapshot.copy(vocabulary = items)).map { entry }
    }

    @Synchronized
    fun deleteVocabulary(spokenForm: String): LocalToolsResult<Boolean> {
        val key = try {
            LocalPersonalToolsPolicy.key(LocalPersonalToolsPolicy.normalizeSpoken(spokenForm, "Spoken form"))
        } catch (error: IllegalArgumentException) {
            return invalid(error)
        }
        val current = readSnapshot()
        val snapshot = current.value ?: return LocalToolsResult(error = current.error)
        val items = snapshot.vocabulary.filterNot { LocalPersonalToolsPolicy.key(it.spokenForm) == key }
        if (items.size == snapshot.vocabulary.size) return LocalToolsResult(value = false)
        return writeSnapshot(snapshot.copy(vocabulary = items)).map { true }
    }

    @Synchronized
    fun upsertSnippet(trigger: String, content: String): LocalToolsResult<LocalSnippet> {
        val snippet = try {
            LocalSnippet(
                LocalPersonalToolsPolicy.normalizeSpoken(trigger, "Snippet trigger"),
                LocalPersonalToolsPolicy.validateSnippetContent(content),
            )
        } catch (error: IllegalArgumentException) {
            return invalid(error)
        }
        val current = readSnapshot()
        val snapshot = current.value ?: return LocalToolsResult(error = current.error)
        val items = snapshot.snippets.toMutableList()
        val index = items.indexOfFirst {
            LocalPersonalToolsPolicy.key(it.trigger) == LocalPersonalToolsPolicy.key(snippet.trigger)
        }
        if (index >= 0) {
            items[index] = snippet
        } else {
            if (items.size >= LocalPersonalToolsPolicy.MAX_SNIPPETS) {
                return LocalToolsResult(error = LIMIT_ERROR)
            }
            items += snippet
        }
        return writeSnapshot(snapshot.copy(snippets = items)).map { snippet }
    }

    @Synchronized
    fun deleteSnippet(trigger: String): LocalToolsResult<Boolean> {
        val key = try {
            LocalPersonalToolsPolicy.key(LocalPersonalToolsPolicy.normalizeSpoken(trigger, "Snippet trigger"))
        } catch (error: IllegalArgumentException) {
            return invalid(error)
        }
        val current = readSnapshot()
        val snapshot = current.value ?: return LocalToolsResult(error = current.error)
        val items = snapshot.snippets.filterNot { LocalPersonalToolsPolicy.key(it.trigger) == key }
        if (items.size == snapshot.snippets.size) return LocalToolsResult(value = false)
        return writeSnapshot(snapshot.copy(snippets = items)).map { true }
    }

    @Synchronized
    fun clear(): LocalToolsResult<Boolean> = try {
        if (persistence.clear()) LocalToolsResult(value = true) else LocalToolsResult(error = CLEAR_ERROR)
    } catch (_: Exception) {
        LocalToolsResult(error = CLEAR_ERROR)
    }

    private fun readSnapshot(): LocalToolsResult<LocalPersonalToolsSnapshot> {
        return try {
            val envelope = persistence.readEnvelope()
                ?: return LocalToolsResult(value = LocalPersonalToolsSnapshot())
            val snapshot = LocalPersonalToolsCodec.decode(cipher.decrypt(envelope))
            LocalToolsResult(value = snapshot)
        } catch (_: Exception) {
            try {
                persistence.clear()
            } catch (_: Exception) {
                // Still return no data. Never fall back to plaintext or partially parsed values.
            }
            LocalToolsResult(error = READ_ERROR)
        }
    }

    private fun writeSnapshot(snapshot: LocalPersonalToolsSnapshot): LocalToolsResult<LocalPersonalToolsSnapshot> = try {
        val validated = LocalPersonalToolsPolicy.validate(snapshot)
        val envelope = cipher.encrypt(LocalPersonalToolsCodec.encode(validated))
        if (!persistence.writeEnvelope(envelope)) {
            persistence.clear()
            LocalToolsResult(error = WRITE_ERROR)
        } else {
            LocalToolsResult(value = validated)
        }
    } catch (_: Exception) {
        try {
            persistence.clear()
        } catch (_: Exception) {
            // Storage remains unavailable. No plaintext fallback is allowed.
        }
        LocalToolsResult(error = WRITE_ERROR)
    }

    private fun normalizeSnippetCommand(value: String): String {
        require(value.length <= LocalPersonalToolsPolicy.MAX_SPOKEN_CHARS + 32) {
            "Snippet command is too long."
        }
        var normalized = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
        listOf("insert snippet ", "snippet ").firstOrNull {
            normalized.startsWith(it, ignoreCase = true)
        }?.let { normalized = normalized.substring(it.length).trim() }
        return normalized
    }

    private fun <T> invalid(error: IllegalArgumentException): LocalToolsResult<T> =
        LocalToolsResult(error = error.message ?: INVALID_ERROR)

    private fun <T, R> LocalToolsResult<T>.map(transform: (T) -> R): LocalToolsResult<R> =
        value?.let { LocalToolsResult(value = transform(it)) } ?: LocalToolsResult(error = error)

    companion object {
        const val READ_ERROR = "Local personal tools could not be decrypted and were cleared."
        const val WRITE_ERROR = "Local personal tools could not be stored securely."
        const val CLEAR_ERROR = "Local personal tools could not be cleared."
        const val LIMIT_ERROR = "Local personal tools entry limit reached."
        const val INVALID_ERROR = "Local personal tools input is invalid."
    }
}
