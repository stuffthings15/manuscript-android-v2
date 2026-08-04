package com.echolink.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class AiJobStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class AiCorrectionJob(
    val jobId: String = UUID.randomUUID().toString(),
    val originalText: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val operation: WritingOperation,
    val tone: WritingTone,
    val qualityMode: QualityMode,
    val sourceApplication: String?,
    val paragraphBoundaries: List<Int>,
    val protectedSpanManifest: List<ProtectedSpan> = emptyList(),
    val candidateRevisions: List<String> = emptyList(),
    val acceptedChangeIds: Set<String> = emptySet(),
    val rejectedChangeIds: Set<String> = emptySet(),
    val status: AiJobStatus = AiJobStatus.ACTIVE,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val reviewParagraphIndex: Int = 0,
    val reviewScrollY: Int = 0,
)

data class EncryptedJobText(
    val ivBase64: String,
    val ciphertextBase64: String,
)

interface AiJobTextCipher {
    fun encrypt(plainText: String): EncryptedJobText
    fun decrypt(value: EncryptedJobText): String
}

interface AiJobStore {
    suspend fun save(job: AiCorrectionJob)
    suspend fun load(jobId: String): AiCorrectionJob?
    suspend fun deleteCompletedBefore(cutoffEpochMs: Long): Int
}

class AndroidKeystoreAiJobTextCipher(
    private val keyAlias: String = "manuscript_ai_job_text_v1",
) : AiJobTextCipher {
    override fun encrypt(plainText: String): EncryptedJobText {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedJobText(
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertextBase64 = Base64.encodeToString(
                cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP,
            ),
        )
    }

    override fun decrypt(value: EncryptedJobText): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(
                GCM_TAG_BITS,
                Base64.decode(value.ivBase64, Base64.NO_WRAP),
            ),
        )
        return String(
            cipher.doFinal(Base64.decode(value.ciphertextBase64, Base64.NO_WRAP)),
            Charsets.UTF_8,
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

@Entity(tableName = "ai_correction_jobs")
data class AiJobEntity(
    @PrimaryKey val jobId: String,
    val originalIv: String,
    val originalCiphertext: String,
    val candidatesIv: String,
    val candidatesCiphertext: String,
    val manifestIv: String,
    val manifestCiphertext: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val operation: String,
    val tone: String,
    val qualityMode: String,
    val sourceApplication: String?,
    val paragraphBoundariesJson: String,
    val acceptedChangeIdsJson: String,
    val rejectedChangeIdsJson: String,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val reviewParagraphIndex: Int,
    val reviewScrollY: Int,
)

@Dao
interface AiJobDao {
    @Query("SELECT * FROM ai_correction_jobs WHERE jobId = :jobId LIMIT 1")
    suspend fun findById(jobId: String): AiJobEntity?

    @Upsert
    suspend fun upsert(entity: AiJobEntity)

    @Query(
        """
        DELETE FROM ai_correction_jobs
        WHERE status = :completedStatus
          AND completedAtEpochMs IS NOT NULL
          AND completedAtEpochMs < :cutoffEpochMs
        """,
    )
    suspend fun deleteCompletedBefore(
        completedStatus: String,
        cutoffEpochMs: Long,
    ): Int
}

@Database(
    entities = [AiJobEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ManuscriptAiDatabase : RoomDatabase() {
    abstract fun aiJobDao(): AiJobDao

    companion object {
        @Volatile
        private var instance: ManuscriptAiDatabase? = null

        fun get(context: Context): ManuscriptAiDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ManuscriptAiDatabase::class.java,
                    "manuscript-ai-jobs.db",
                ).build().also { instance = it }
            }
    }
}

class ImmutableSourceRevisionException(jobId: String) :
    IllegalStateException("The source revision for job $jobId is immutable.")

class AiJobRepository(
    private val dao: AiJobDao,
    private val cipher: AiJobTextCipher,
) : AiJobStore {
    override suspend fun save(job: AiCorrectionJob) {
        val current = dao.findById(job.jobId)
        if (current != null) {
            val storedOriginal = cipher.decrypt(
                EncryptedJobText(current.originalIv, current.originalCiphertext),
            )
            if (storedOriginal != job.originalText) {
                throw ImmutableSourceRevisionException(job.jobId)
            }
        }

        val encryptedOriginal = current?.let {
            EncryptedJobText(it.originalIv, it.originalCiphertext)
        } ?: cipher.encrypt(job.originalText)
        val encryptedCandidates = cipher.encrypt(stringListJson(job.candidateRevisions))
        val encryptedManifest = cipher.encrypt(protectedSpanJson(job.protectedSpanManifest))

        dao.upsert(
            AiJobEntity(
                jobId = job.jobId,
                originalIv = encryptedOriginal.ivBase64,
                originalCiphertext = encryptedOriginal.ciphertextBase64,
                candidatesIv = encryptedCandidates.ivBase64,
                candidatesCiphertext = encryptedCandidates.ciphertextBase64,
                manifestIv = encryptedManifest.ivBase64,
                manifestCiphertext = encryptedManifest.ciphertextBase64,
                selectionStart = job.selectionStart,
                selectionEnd = job.selectionEnd,
                operation = job.operation.name,
                tone = job.tone.name,
                qualityMode = job.qualityMode.name,
                sourceApplication = job.sourceApplication,
                paragraphBoundariesJson = intListJson(job.paragraphBoundaries),
                acceptedChangeIdsJson = stringListJson(job.acceptedChangeIds.sorted()),
                rejectedChangeIdsJson = stringListJson(job.rejectedChangeIds.sorted()),
                status = job.status.name,
                createdAtEpochMs = job.createdAtEpochMs,
                updatedAtEpochMs = job.updatedAtEpochMs,
                completedAtEpochMs = job.completedAtEpochMs,
                reviewParagraphIndex = job.reviewParagraphIndex,
                reviewScrollY = job.reviewScrollY,
            ),
        )
    }

    override suspend fun load(jobId: String): AiCorrectionJob? {
        val entity = dao.findById(jobId) ?: return null
        return AiCorrectionJob(
            jobId = entity.jobId,
            originalText = cipher.decrypt(
                EncryptedJobText(entity.originalIv, entity.originalCiphertext),
            ),
            selectionStart = entity.selectionStart,
            selectionEnd = entity.selectionEnd,
            operation = WritingOperation.valueOf(entity.operation),
            tone = WritingTone.valueOf(entity.tone),
            qualityMode = QualityMode.valueOf(entity.qualityMode),
            sourceApplication = entity.sourceApplication,
            paragraphBoundaries = parseIntList(entity.paragraphBoundariesJson),
            protectedSpanManifest = parseProtectedSpans(
                cipher.decrypt(
                    EncryptedJobText(entity.manifestIv, entity.manifestCiphertext),
                ),
            ),
            candidateRevisions = parseStringList(
                cipher.decrypt(
                    EncryptedJobText(entity.candidatesIv, entity.candidatesCiphertext),
                ),
            ),
            acceptedChangeIds = parseStringList(entity.acceptedChangeIdsJson).toSet(),
            rejectedChangeIds = parseStringList(entity.rejectedChangeIdsJson).toSet(),
            status = AiJobStatus.valueOf(entity.status),
            createdAtEpochMs = entity.createdAtEpochMs,
            updatedAtEpochMs = entity.updatedAtEpochMs,
            completedAtEpochMs = entity.completedAtEpochMs,
            reviewParagraphIndex = entity.reviewParagraphIndex,
            reviewScrollY = entity.reviewScrollY,
        )
    }

    override suspend fun deleteCompletedBefore(cutoffEpochMs: Long): Int =
        dao.deleteCompletedBefore(AiJobStatus.COMPLETED.name, cutoffEpochMs)

    private fun stringListJson(values: Collection<String>): String =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

    private fun intListJson(values: Collection<Int>): String =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

    private fun protectedSpanJson(values: Collection<ProtectedSpan>): String =
        buildJsonArray {
            values.forEach { span ->
                add(
                    buildJsonObject {
                        put("index", span.index)
                        put("type", span.type.name)
                        put("token", span.token)
                        put("original", span.original)
                    },
                )
            }
        }.toString()

    private fun parseStringList(value: String): List<String> =
        Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.content }

    private fun parseIntList(value: String): List<Int> =
        Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.int }

    private fun parseProtectedSpans(value: String): List<ProtectedSpan> =
        Json.parseToJsonElement(value).jsonArray.map { element ->
            val item = element.jsonObject
            ProtectedSpan(
                index = requireNotNull(item["index"]).jsonPrimitive.int,
                type = ProtectedSpanType.valueOf(
                    requireNotNull(item["type"]).jsonPrimitive.content,
                ),
                token = requireNotNull(item["token"]).jsonPrimitive.content,
                original = requireNotNull(item["original"]).jsonPrimitive.content,
            )
        }
}
