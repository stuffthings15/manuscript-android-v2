package com.echolink.mobile

import androidx.work.Data
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWorkContractTest {
    @Test
    fun everyGemmaTargetAndEveryLegacyTierRoundTripsWithFixedWireCodes() {
        validTargets.forEachIndexed { index, target ->
            val workerId = UUID(100L + index, 200L + index)
            val attempt = DownloadAttempt(
                target = target,
                generation = DownloadGeneration(index + 1L),
                workIdentity = DownloadWorkIdentity.from(workerId),
            )

            val encoded = DownloadWorkContract.encode(attempt)

            assertEquals(attempt, DownloadWorkContract.decode(encoded, workerId))
            assertEquals(
                DownloadTelemetryWire.code(target.family),
                encoded.getInt(DownloadWorkContract.KEY_FAMILY, Int.MIN_VALUE),
            )
            assertEquals(
                DownloadTelemetryWire.code(target.tier),
                encoded.getInt(DownloadWorkContract.KEY_TIER, Int.MIN_VALUE),
            )
            assertEquals(
                DownloadTelemetryWire.code(target.backend),
                encoded.getInt(DownloadWorkContract.KEY_BACKEND, Int.MIN_VALUE),
            )
        }
    }

    @Test
    fun v1KeysAndEncodedWireCodesStayLiteral() {
        assertEquals(v1LiteralKeys, DownloadWorkContract.requiredKeys)

        v1TargetFixtures.forEachIndexed { index, fixture ->
            val workerId = UUID(500L + index, 600L + index)
            val encoded = DownloadWorkContract.encode(
                DownloadAttempt(
                    target = fixture.target,
                    generation = DownloadGeneration(index + 1L),
                    workIdentity = DownloadWorkIdentity.from(workerId),
                ),
            )

            assertEquals(v1LiteralKeys, encoded.keyValueMap.keys)
            assertEquals(
                fixture.familyCode,
                encoded.getInt("manuscript.download.family.v1", Int.MIN_VALUE),
            )
            assertEquals(
                fixture.tierCode,
                encoded.getInt("manuscript.download.tier.v1", Int.MIN_VALUE),
            )
            assertEquals(
                fixture.backendCode,
                encoded.getInt("manuscript.download.backend.v1", Int.MIN_VALUE),
            )
        }
    }

    @Test
    fun literalV1PayloadsFromExistingWorkDecodeAfterUpgrade() {
        v1TargetFixtures.forEachIndexed { index, fixture ->
            val workerId = UUID(700L + index, 800L + index)
            val generation = index + 21L
            val encoded = Data.Builder()
                .putInt("manuscript.download.family.v1", fixture.familyCode)
                .putInt("manuscript.download.tier.v1", fixture.tierCode)
                .putInt("manuscript.download.backend.v1", fixture.backendCode)
                .putLong("manuscript.download.generation.v1", generation)
                .putLong("manuscript.download.work_uuid_most.v1", workerId.mostSignificantBits)
                .putLong("manuscript.download.work_uuid_least.v1", workerId.leastSignificantBits)
                .build()

            assertEquals(
                DownloadAttempt(
                    target = fixture.target,
                    generation = DownloadGeneration(generation),
                    workIdentity = DownloadWorkIdentity.from(workerId),
                ),
                DownloadWorkContract.decode(encoded, workerId),
            )
        }
    }

    @Test
    fun everyWorkerInputCarriesOnlyTargetGenerationAndExactUuidPrimitives() {
        validTargets.forEachIndexed { index, target ->
            val attempt = DownloadAttempt(
                target = target,
                generation = DownloadGeneration(index + 11L),
                workIdentity = DownloadWorkIdentity(300L + index, 400L + index),
            )

            val values = DownloadWorkContract.encode(attempt).keyValueMap

            assertEquals(DownloadWorkContract.requiredKeys, values.keys)
            assertEquals(attempt.generation.value, values[DownloadWorkContract.KEY_GENERATION])
            assertEquals(
                attempt.workIdentity.mostSignificantBits,
                values[DownloadWorkContract.KEY_WORK_UUID_MOST],
            )
            assertEquals(
                attempt.workIdentity.leastSignificantBits,
                values[DownloadWorkContract.KEY_WORK_UUID_LEAST],
            )
            assertTrue(values.values.all { it is Int || it is Long })
        }
    }

    @Test
    fun everyMissingRequiredKeyFailsClosed() {
        val attempt = attempt()
        val encoded = DownloadWorkContract.encode(attempt)

        DownloadWorkContract.requiredKeys.forEach { missing ->
            assertNull(
                missing,
                DownloadWorkContract.decode(
                    dataFrom(encoded.keyValueMap - missing),
                    attempt.workIdentity.toUuid(),
                ),
            )
        }
    }

    @Test
    fun unknownFutureWireCodesFailClosed() {
        val attempt = attempt()
        val encoded = DownloadWorkContract.encode(attempt)

        listOf(
            DownloadWorkContract.KEY_FAMILY,
            DownloadWorkContract.KEY_TIER,
            DownloadWorkContract.KEY_BACKEND,
        ).forEach { key ->
            assertNull(
                key,
                DownloadWorkContract.decode(
                    mutate(encoded, key, Int.MAX_VALUE),
                    attempt.workIdentity.toUuid(),
                ),
            )
        }
    }

    @Test
    fun individuallyValidButImpossibleFamilyTierBackendTargetsFailClosed() {
        val gemma = attempt()
        val legacy = attempt(
            target = DownloadTargetKey(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_COMPACT,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
        )

        val gemmaWithLegacyTier = mutate(
            DownloadWorkContract.encode(gemma),
            DownloadWorkContract.KEY_TIER,
            DownloadTelemetryWire.TIER_LEGACY_COMPACT,
        )
        val legacyWithLiteRt = mutate(
            DownloadWorkContract.encode(legacy),
            DownloadWorkContract.KEY_BACKEND,
            DownloadTelemetryWire.BACKEND_LITERT_CPU,
        )

        assertNull(DownloadWorkContract.decode(gemmaWithLegacyTier, gemma.workIdentity.toUuid()))
        assertNull(DownloadWorkContract.decode(legacyWithLiteRt, legacy.workIdentity.toUuid()))
    }

    @Test
    fun zeroNegativeAndWrongTypedGenerationsFailClosed() {
        val attempt = attempt()
        val encoded = DownloadWorkContract.encode(attempt)

        listOf(0L, -1L, Long.MIN_VALUE).forEach { invalid ->
            assertNull(
                DownloadWorkContract.decode(
                    mutate(encoded, DownloadWorkContract.KEY_GENERATION, invalid),
                    attempt.workIdentity.toUuid(),
                ),
            )
        }
        assertNull(
            DownloadWorkContract.decode(
                mutate(encoded, DownloadWorkContract.KEY_GENERATION, "private text"),
                attempt.workIdentity.toUuid(),
            ),
        )
    }

    @Test
    fun decoderRequiresActualWorkerUuidToMatchBothEncodedLongsExactly() {
        val attempt = attempt()
        val encoded = DownloadWorkContract.encode(attempt)
        val actual = attempt.workIdentity.toUuid()

        assertNull(DownloadWorkContract.decode(encoded, UUID(actual.mostSignificantBits + 1L, actual.leastSignificantBits)))
        assertNull(DownloadWorkContract.decode(encoded, UUID(actual.mostSignificantBits, actual.leastSignificantBits + 1L)))
        assertNull(
            DownloadWorkContract.decode(
                mutate(encoded, DownloadWorkContract.KEY_WORK_UUID_MOST, actual.mostSignificantBits + 1L),
                actual,
            ),
        )
        assertNull(
            DownloadWorkContract.decode(
                mutate(encoded, DownloadWorkContract.KEY_WORK_UUID_LEAST, actual.leastSignificantBits + 1L),
                actual,
            ),
        )
    }

    @Test
    fun extraIdentityPrivacyAndLargeValuesAreRejectedWithoutBeingParsed() {
        val attempt = attempt()
        val encoded = DownloadWorkContract.encode(attempt)
        val actual = attempt.workIdentity.toUuid()
        val ambiguousIdentity = mutate(encoded, "artifact_tier", "FAST")
        val privacyBearing = mutate(encoded, "prompt", "correct this private sentence")
        val largeButBuildable = mutate(encoded, "payload", "x".repeat(8_000))

        assertNull(DownloadWorkContract.decode(ambiguousIdentity, actual))
        assertNull(DownloadWorkContract.decode(privacyBearing, actual))
        assertNull(DownloadWorkContract.decode(largeButBuildable, actual))
        assertThrows(IllegalStateException::class.java) {
            mutate(encoded, "oversized", "x".repeat(20_000))
        }
    }

    @Test
    fun wrongTypesForWireAndUuidFieldsFailClosed() {
        val attempt = attempt()
        val encoded = DownloadWorkContract.encode(attempt)
        val actual = attempt.workIdentity.toUuid()

        listOf(
            DownloadWorkContract.KEY_FAMILY,
            DownloadWorkContract.KEY_TIER,
            DownloadWorkContract.KEY_BACKEND,
            DownloadWorkContract.KEY_WORK_UUID_MOST,
            DownloadWorkContract.KEY_WORK_UUID_LEAST,
        ).forEach { key ->
            assertNull(
                key,
                DownloadWorkContract.decode(mutate(encoded, key, "not a number"), actual),
            )
        }
    }

    private fun attempt(
        target: DownloadTargetKey = validTargets.first(),
    ): DownloadAttempt {
        val workerId = UUID.fromString("55733304-16a7-4bf8-9f6e-bb5ae1f91c9b")
        return DownloadAttempt(target, DownloadGeneration(7L), DownloadWorkIdentity.from(workerId))
    }

    private fun mutate(data: Data, key: String, value: Any?): Data =
        dataFrom(data.keyValueMap.toMutableMap().apply { put(key, value) })

    private fun dataFrom(values: Map<String, Any?>): Data = Data.Builder()
        .putAll(values)
        .build()

    private data class V1TargetFixture(
        val target: DownloadTargetKey,
        val familyCode: Int,
        val tierCode: Int,
        val backendCode: Int,
    )

    private val v1LiteralKeys = setOf(
        "manuscript.download.family.v1",
        "manuscript.download.tier.v1",
        "manuscript.download.backend.v1",
        "manuscript.download.generation.v1",
        "manuscript.download.work_uuid_most.v1",
        "manuscript.download.work_uuid_least.v1",
    )

    private val v1TargetFixtures = listOf(
        V1TargetFixture(
            DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.FAST, BackendKind.LITERT_CPU),
            familyCode = 1,
            tierCode = 1,
            backendCode = 1,
        ),
        V1TargetFixture(
            DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.FAST, BackendKind.LITERT_GPU),
            familyCode = 1,
            tierCode = 1,
            backendCode = 2,
        ),
        V1TargetFixture(
            DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.THOROUGH, BackendKind.LITERT_CPU),
            familyCode = 1,
            tierCode = 2,
            backendCode = 1,
        ),
        V1TargetFixture(
            DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.THOROUGH, BackendKind.LITERT_GPU),
            familyCode = 1,
            tierCode = 2,
            backendCode = 2,
        ),
        V1TargetFixture(
            DownloadTargetKey(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_COMPACT,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            familyCode = 2,
            tierCode = 101,
            backendCode = 101,
        ),
        V1TargetFixture(
            DownloadTargetKey(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_BALANCED,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            familyCode = 2,
            tierCode = 102,
            backendCode = 101,
        ),
        V1TargetFixture(
            DownloadTargetKey(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_ADVANCED,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            familyCode = 2,
            tierCode = 103,
            backendCode = 101,
        ),
    )

    private val validTargets = v1TargetFixtures.map { it.target }
}
