package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

class DownloadTelemetryPresentationTest {
    private val calculator = DownloadTelemetryCalculator(clock = { 1_000L })

    @Test
    fun uiLoadFailureIsNonblankAndOffersRetry() {
        val presentation = DownloadTelemetryPresentation.uiFailure()

        assertFalse(presentation.statusText.isBlank())
        assertFalse(presentation.detailText.isBlank())
        assertEquals(listOf(DownloadAction.RETRY), presentation.actions)
        assertNull(presentation.progressText)
        assertNull(presentation.percent)
    }

    @Test
    fun everyPhaseMapsToNonblankPlatformNeutralText() {
        DownloadPhase.entries.forEach { phase ->
            val presentation = DownloadTelemetryPresentation.from(telemetryFor(phase))

            assertFalse("phase=$phase status", presentation.statusText.isBlank())
            assertFalse("phase=$phase detail", presentation.detailText.isBlank())
        }
    }

    @Test
    fun everyNetworkHasAStableNonblankLabel() {
        val expected = mapOf(
            DownloadNetwork.UNKNOWN to "Network not detected",
            DownloadNetwork.OFFLINE to "Offline",
            DownloadNetwork.WIFI to "Wi-Fi",
            DownloadNetwork.MOBILE to "Mobile data",
            DownloadNetwork.ETHERNET to "Ethernet",
            DownloadNetwork.OTHER to "Connected network",
        )

        expected.forEach { (network, label) ->
            val presentation = DownloadTelemetryPresentation.from(
                telemetryFor(DownloadPhase.DOWNLOADING, network = network),
            )
            assertEquals("network=$network", label, presentation.networkText)
        }
    }

    @Test
    fun firstDownloadSampleReportsCalculatingEtaWithoutZeroClaims() {
        val presentation = DownloadTelemetryPresentation.from(
            telemetryFor(
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = 500L,
                totalBytes = 1_000L,
                network = DownloadNetwork.MOBILE,
            ),
        )

        assertEquals(50, presentation.percent)
        assertEquals("Mobile data", presentation.networkText)
        assertNull(presentation.speedText)
        assertEquals("Calculating ETA", presentation.etaText)
        assertFalse(presentation.detailText.contains("0 seconds"))
        assertEquals(listOf(DownloadAction.CANCEL), presentation.actions)
    }

    @Test
    fun provenDownloadPresentationIncludesProgressSpeedNetworkAndRealEta() {
        var now = 1_000L
        val rolling = DownloadTelemetryCalculator(clock = { now })
        val first = rolling.record(
            observation(downloadedBytes = 100L, totalBytes = 1_000L),
        )
        now = 3_000L
        val telemetry = rolling.record(
            observation(downloadedBytes = 500L, totalBytes = 1_000L),
            first,
        )

        val presentation = DownloadTelemetryPresentation.from(telemetry)

        assertEquals("500 of 1,000 bytes", presentation.progressText)
        assertEquals("200 bytes/s", presentation.speedText)
        assertEquals("3 seconds remaining", presentation.etaText)
        assertTrue(presentation.detailText.contains("50%"))
        assertTrue(presentation.detailText.contains("Wi-Fi"))
    }

    @Test
    fun singularByteAndSecondUnitsAreGrammaticallyCorrect() {
        var now = 1_000L
        val rolling = DownloadTelemetryCalculator(clock = { now })
        val first = rolling.record(
            observation(downloadedBytes = 0L, totalBytes = 2L),
        )
        now = 2_000L
        val proven = rolling.record(
            observation(downloadedBytes = 1L, totalBytes = 2L),
            first,
        )
        val knownTotal = DownloadTelemetryPresentation.from(proven)
        val unknownTotal = DownloadTelemetryPresentation.from(
            calculator.record(
                observation(downloadedBytes = 1L, totalBytes = null),
            ),
        )

        assertEquals("1 byte/s", knownTotal.speedText)
        assertEquals("1 second remaining", knownTotal.etaText)
        assertEquals("1 byte downloaded", unknownTotal.progressText)
    }

    @Test
    fun waitingAndPausedStateTheirSafeReason() {
        val waiting = DownloadTelemetryPresentation.from(
            telemetryFor(
                phase = DownloadPhase.WAITING,
                blockReason = DownloadBlockReason.WIFI_REQUIRED,
            ),
        )
        val paused = DownloadTelemetryPresentation.from(
            telemetryFor(
                phase = DownloadPhase.PAUSED,
                blockReason = DownloadBlockReason.USER_PAUSED,
            ),
        )

        assertTrue(waiting.detailText.contains("Wi-Fi"))
        assertTrue(paused.detailText.contains("paused by you", ignoreCase = true))
        assertEquals(
            listOf(DownloadAction.RETRY, DownloadAction.CANCEL),
            waiting.actions,
        )
        assertTrue(paused.actions.isEmpty())
    }

    @Test
    fun wifiOnlyWaitingAlwaysOffersRetryAndCancelDespiteWorkerFlags() {
        val waiting = calculator.record(
            observation(
                phase = DownloadPhase.WAITING,
                network = DownloadNetwork.MOBILE,
                blockReason = DownloadBlockReason.WIFI_REQUIRED,
                retryAllowed = false,
            ),
        )

        assertEquals(
            listOf(DownloadAction.RETRY, DownloadAction.CANCEL),
            DownloadTelemetryPresentation.from(waiting).actions,
        )
    }

    @Test
    fun everyTypedBlockReasonMapsToItsExactSafeExplanation() {
        DownloadBlockReason.entries.forEach { reason ->
            val presentation = DownloadTelemetryPresentation.from(
                telemetryFor(
                    phase = reason.phase,
                    blockReason = reason,
                ),
            )

            assertEquals("reason=$reason", reason.userMessage, presentation.detailText)
        }
    }

    @Test
    fun failedPresentationUsesSafeMessageAndRetryOnly() {
        val telemetry = telemetryFor(
            phase = DownloadPhase.FAILED,
            failureCode = DownloadFailureCode.INTEGRITY_CHECK_FAILED,
        )

        val presentation = DownloadTelemetryPresentation.from(telemetry)

        assertEquals(telemetry.failure?.userMessage, presentation.detailText)
        assertEquals(listOf(DownloadAction.RETRY), presentation.actions)
        assertFalse(presentation.detailText.contains("/"))
        assertFalse(presentation.detailText.contains("\\"))
    }

    @Test
    fun everyFailureCodeMapsToItsExactSafeMessage() {
        DownloadFailureCode.entries.forEach { code ->
            val telemetry = telemetryFor(
                phase = DownloadPhase.FAILED,
                failureCode = code,
            )
            val presentation = DownloadTelemetryPresentation.from(telemetry)

            assertEquals("failure=$code", SafeDownloadFailure.from(code), telemetry.failure)
            assertEquals("failure=$code", telemetry.failure?.userMessage, presentation.detailText)
            assertEquals(listOf(DownloadAction.RETRY), presentation.actions)
        }
    }

    @Test
    fun unknownTotalOmitsPercentAndUsesDownloadedBytesOnly() {
        val presentation = DownloadTelemetryPresentation.from(
            telemetryFor(
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = 500L,
                totalBytes = null,
            ),
        )

        assertNull(presentation.percent)
        assertEquals("500 bytes downloaded", presentation.progressText)
        assertFalse(presentation.detailText.contains("%"))
        assertEquals("Calculating ETA", presentation.etaText)
    }

    @Test
    fun unknownTotalKeepsProvenSpeedButStillCalculatesEta() {
        var now = 1_000L
        val rolling = DownloadTelemetryCalculator(clock = { now })
        val first = rolling.record(
            observation(
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = 100L,
                totalBytes = null,
                network = DownloadNetwork.WIFI,
                blockReason = null,
                failureCode = null,
                verificationSucceeded = false,
            ),
        )
        now = 2_000L
        val second = rolling.record(
            observation(
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = 300L,
                totalBytes = null,
                network = DownloadNetwork.WIFI,
                blockReason = null,
                failureCode = null,
                verificationSucceeded = false,
            ),
            first,
        )

        val presentation = DownloadTelemetryPresentation.from(second)

        assertEquals("200 bytes/s", presentation.speedText)
        assertEquals("Calculating ETA", presentation.etaText)
    }

    @Test
    fun equalTelemetryProducesEqualPresentationData() {
        val telemetry = telemetryFor(
            phase = DownloadPhase.DOWNLOADING,
            downloadedBytes = 500L,
            totalBytes = 1_000L,
        )

        val first = DownloadTelemetryPresentation.from(telemetry)
        val second = DownloadTelemetryPresentation.from(telemetry)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, DownloadTelemetryPresentation.from(telemetryFor(DownloadPhase.READY)))
    }

    @Test
    fun allActionsComeFromTelemetryAvailability() {
        val preparing = DownloadTelemetryPresentation.from(
            telemetryFor(DownloadPhase.PREPARING),
        )
        val verifying = DownloadTelemetryPresentation.from(
            telemetryFor(DownloadPhase.VERIFYING),
        )
        val ready = DownloadTelemetryPresentation.from(
            telemetryFor(DownloadPhase.READY),
        )

        assertEquals(listOf(DownloadAction.CANCEL), preparing.actions)
        assertEquals(listOf(DownloadAction.CANCEL), verifying.actions)
        assertTrue(ready.actions.isEmpty())
    }

    @Test
    fun pausedPresentationShowsRetryOnlyWhenAllowed() {
        val retryable = calculator.record(
            observation(
                phase = DownloadPhase.PAUSED,
                blockReason = DownloadBlockReason.USER_PAUSED,
                retryAllowed = true,
            ),
        )
        val notRetryable = calculator.record(
            observation(
                phase = DownloadPhase.PAUSED,
                blockReason = DownloadBlockReason.USER_PAUSED,
                retryAllowed = false,
            ),
        )

        assertEquals(
            listOf(DownloadAction.RETRY),
            DownloadTelemetryPresentation.from(retryable).actions,
        )
        assertTrue(DownloadTelemetryPresentation.from(notRetryable).actions.isEmpty())
    }

    @Test
    fun publicInputsCannotAcceptPrivateStrings() {
        val inputTypes = listOf(
            DownloadGeneration::class.java,
            DownloadSample::class.java,
            DownloadObservation::class.java,
        )

        inputTypes.flatMap { it.constructors.toList() }
            .filter { Modifier.isPublic(it.modifiers) }
            .flatMap { it.parameterTypes.toList() }
            .forEach { parameterType ->
                assertFalse(parameterType == String::class.java)
                assertFalse(CharSequence::class.java.isAssignableFrom(parameterType))
            }

        listOf(
            DownloadTelemetry::class.java,
            DownloadTelemetryPresentation::class.java,
            SafeDownloadFailure::class.java,
        ).forEach { protectedType ->
            assertFalse("interface ${protectedType.simpleName}", protectedType.isInterface)
            assertTrue("nonfinal ${protectedType.simpleName}", Modifier.isFinal(protectedType.modifiers))
            assertTrue(
                "forgeable ${protectedType.simpleName}",
                protectedType.constructors.none {
                    Modifier.isPublic(it.modifiers) && !it.isSynthetic
                },
            )
            assertTrue(
                "copy or setter on ${protectedType.simpleName}",
                protectedType.declaredMethods.none {
                    Modifier.isPublic(it.modifiers) &&
                        (it.name == "copy" || it.name.startsWith("set"))
                },
            )
        }
        assertTrue(SafeDownloadFailure::class.java.isEnum)
    }

    @Test
    fun presentationActionsCannotBeMutated() {
        val presentation = DownloadTelemetryPresentation.from(
            telemetryFor(DownloadPhase.DOWNLOADING),
        )

        assertThrowsUnsupported {
            @Suppress("UNCHECKED_CAST")
            (presentation.actions as MutableList<DownloadAction>).add(DownloadAction.RETRY)
        }
        assertEquals(listOf(DownloadAction.CANCEL), presentation.actions)
    }

    @Test
    fun publicTelemetryTypesHaveNoAndroidApiSurface() {
        val types = listOf(
            DownloadGeneration::class.java,
            DownloadSample::class.java,
            DownloadObservation::class.java,
            DownloadTelemetry::class.java,
            DownloadTelemetryCalculator::class.java,
            DownloadTelemetryPresentation::class.java,
            SafeDownloadFailure::class.java,
        )

        types.forEach { type ->
            val exposedTypes = buildList {
                type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
                    add(it.type)
                }
                type.declaredConstructors.forEach { addAll(it.parameterTypes) }
                type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.forEach {
                    add(it.returnType)
                    addAll(it.parameterTypes)
                }
            }
            exposedTypes.forEach { exposed ->
                assertFalse(
                    "${type.simpleName} exposes ${exposed.name}",
                    exposed.name.startsWith("android."),
                )
            }
        }
    }

    @Test
    fun everyGeneratedMessageRejectsSensitiveShapesAndEmDash() {
        val roots = buildList<Any> {
            DownloadPhase.entries.forEach { phase ->
                val presentation = DownloadTelemetryPresentation.from(telemetryFor(phase))
                add(presentation)
                add(telemetryFor(phase))
            }
            DownloadFailureCode.entries.forEach { code ->
                add(SafeDownloadFailure.from(code))
            }
            DownloadBlockReason.entries.forEach { reason ->
                add(reason)
            }
        }
        val text = roots.flatMap(::nestedStrings)
        val forbidden = listOf(
            Regex("(?i)(?:https?|android|file|content)://"),
            Regex("[A-Za-z]:\\\\"),
            Regex("(?i)/(?:data|storage)/"),
            Regex(
                "(?i)(credential|password|secret|token=|bearer\\s|api[_-]?key=|prompt|correction text)",
            ),
        )

        text.forEach { value ->
            assertTrue("blank generated value", value.isNotBlank())
            assertFalse("em dash in $value", value.contains('\u2014'))
            forbidden.forEach { pattern ->
                assertFalse("unsafe generated value: $value", pattern.containsMatchIn(value))
            }
        }

        val canaries = listOf(
            "https://private.example/model",
            "C:\\private\\model.bin",
            "token=private",
            "private prompt",
            "private correction text",
            "android://private/model",
            "file:///private/model",
            "content://private/model",
            "Bearer private-token",
            "api_key=private",
            "/data/user/0/private/model.bin",
            "/storage/emulated/0/private/model.bin",
        )
        canaries.forEach { canary ->
            assertTrue(
                "privacy scan missed canary: $canary",
                forbidden.any { it.containsMatchIn(canary) },
            )
        }
    }

    private fun telemetryFor(
        phase: DownloadPhase,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = if (phase == DownloadPhase.READY) null else 1_000L,
        network: DownloadNetwork = DownloadNetwork.WIFI,
        blockReason: DownloadBlockReason? = when (phase) {
            DownloadPhase.WAITING -> DownloadBlockReason.NETWORK_UNAVAILABLE
            DownloadPhase.PAUSED -> DownloadBlockReason.DEVICE_CONSTRAINT
            else -> null
        },
        failureCode: DownloadFailureCode? = if (phase == DownloadPhase.FAILED) {
            DownloadFailureCode.UNKNOWN
        } else {
            null
        },
    ): DownloadTelemetry = calculator.record(
        observation(
            phase = phase,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            network = network,
            blockReason = blockReason,
            failureCode = failureCode,
            verificationSucceeded = phase == DownloadPhase.READY,
        ),
    )

    private fun observation(
        phase: DownloadPhase = DownloadPhase.DOWNLOADING,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = 1_000L,
        network: DownloadNetwork = DownloadNetwork.WIFI,
        blockReason: DownloadBlockReason? = null,
        failureCode: DownloadFailureCode? = null,
        verificationSucceeded: Boolean = false,
        retryAllowed: Boolean = phase == DownloadPhase.FAILED,
    ) = DownloadObservation(
        generation = DownloadGeneration(2L),
        phase = phase,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        network = network,
        blockReason = blockReason,
        failureCode = failureCode,
        verificationSucceeded = verificationSucceeded,
        cancellationAllowed = true,
        retryAllowed = retryAllowed,
    )

    private fun nestedStrings(root: Any): List<String> {
        val seen = IdentityHashMap<Any, Boolean>()
        val values = mutableListOf<String>()

        fun visit(value: Any?) {
            when (value) {
                null -> Unit
                is String -> values += value
                is CharSequence -> values += value.toString()
                is Enum<*> -> values += value.name
                is Iterable<*> -> value.forEach(::visit)
                is Number, is Boolean, is Char -> Unit
                else -> {
                    if (seen.put(value, true) != null) return
                    value.javaClass.declaredFields
                        .filterNot { it.isIgnoredContractField() }
                        .forEach { field ->
                            field.isAccessible = true
                            visit(field.get(value))
                        }
                }
            }
        }

        visit(root)
        return values
    }

    private fun Field.isIgnoredContractField(): Boolean =
        Modifier.isStatic(modifiers) || isSynthetic

    private fun assertThrowsUnsupported(block: () -> Unit) {
        org.junit.Assert.assertThrows(UnsupportedOperationException::class.java, block)
    }
}
