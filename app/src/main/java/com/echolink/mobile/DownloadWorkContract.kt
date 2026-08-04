package com.echolink.mobile

import androidx.work.Data
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID

internal object DownloadWorkContract {
    const val KEY_FAMILY = "manuscript.download.family.v1"
    const val KEY_TIER = "manuscript.download.tier.v1"
    const val KEY_BACKEND = "manuscript.download.backend.v1"
    const val KEY_GENERATION = "manuscript.download.generation.v1"
    const val KEY_WORK_UUID_MOST = "manuscript.download.work_uuid_most.v1"
    const val KEY_WORK_UUID_LEAST = "manuscript.download.work_uuid_least.v1"

    val requiredKeys: Set<String> = Collections.unmodifiableSet(
        LinkedHashSet(
            listOf(
                KEY_FAMILY,
                KEY_TIER,
                KEY_BACKEND,
                KEY_GENERATION,
                KEY_WORK_UUID_MOST,
                KEY_WORK_UUID_LEAST,
            ),
        ),
    )

    fun encode(attempt: DownloadAttempt): Data = Data.Builder()
        .putInt(KEY_FAMILY, DownloadTelemetryWire.code(attempt.target.family))
        .putInt(KEY_TIER, DownloadTelemetryWire.code(attempt.target.tier))
        .putInt(KEY_BACKEND, DownloadTelemetryWire.code(attempt.target.backend))
        .putLong(KEY_GENERATION, attempt.generation.value)
        .putLong(KEY_WORK_UUID_MOST, attempt.workIdentity.mostSignificantBits)
        .putLong(KEY_WORK_UUID_LEAST, attempt.workIdentity.leastSignificantBits)
        .build()

    fun decode(
        data: Data,
        actualWorkerId: UUID,
    ): DownloadAttempt? {
        val values = data.keyValueMap
        if (values.keys != requiredKeys) return null

        val familyCode = values[KEY_FAMILY] as? Int ?: return null
        val tierCode = values[KEY_TIER] as? Int ?: return null
        val backendCode = values[KEY_BACKEND] as? Int ?: return null
        val generationValue = values[KEY_GENERATION] as? Long ?: return null
        val uuidMost = values[KEY_WORK_UUID_MOST] as? Long ?: return null
        val uuidLeast = values[KEY_WORK_UUID_LEAST] as? Long ?: return null
        if (generationValue <= 0L) return null
        if (UUID(uuidMost, uuidLeast) != actualWorkerId) return null

        val family = DownloadTelemetryWire.family(familyCode) ?: return null
        val tier = DownloadTelemetryWire.tier(tierCode) ?: return null
        val backend = DownloadTelemetryWire.backend(backendCode) ?: return null
        val target = try {
            DownloadTargetKey(family, tier, backend)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return DownloadAttempt(
            target = target,
            generation = DownloadGeneration(generationValue),
            workIdentity = DownloadWorkIdentity(uuidMost, uuidLeast),
        )
    }
}
