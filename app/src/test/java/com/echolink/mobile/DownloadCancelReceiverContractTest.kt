package com.echolink.mobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * RED contract for notification cancellation.
 *
 * The notification must route through Manuscript's typed download lifecycle. A
 * WorkManager-generated cancellation PendingIntent bypasses durable telemetry
 * and can leave the user looking at a download that was cancelled underneath
 * the app.
 */
class DownloadCancelReceiverContractTest {
    @Test
    fun everyDownloadTargetRoundTripsWithItsExactAttemptModelAndPinnedVersion() {
        validTargets.forEachIndexed { index, target ->
            val attempt = DownloadAttempt(
                target = target,
                generation = DownloadGeneration(index + 1L),
                workIdentity = DownloadWorkIdentity(100L + index, 200L + index),
            )
            val artifact = DownloadTargetCatalog.artifactFor(target)

            val payload = DownloadCancelPayloadContract.encode(attempt)

            assertEquals(attempt, DownloadCancelPayloadContract.decode(payload))
            assertEquals(artifact.fileName, payload[DownloadCancelPayloadContract.KEY_MODEL])
            assertEquals(artifact.revision, payload[DownloadCancelPayloadContract.KEY_VERSION])
        }
    }

    @Test
    fun payloadContainsOnlyTheEightFixedIdentityFieldsAndNoPrivateText() {
        val attempt = attempt()
        val artifact = DownloadTargetCatalog.artifactFor(attempt.target)

        val payload = DownloadCancelPayloadContract.encode(attempt)

        assertEquals(
            "com.remote.Manuscript.v2.action.CANCEL_DOWNLOAD",
            DownloadCancelPayloadContract.ACTION_CANCEL,
        )
        assertEquals(literalRequiredKeys, DownloadCancelPayloadContract.requiredKeys)
        assertEquals(literalRequiredKeys, payload.keys)
        assertEquals(8, payload.size)
        assertEquals(
            DownloadTelemetryWire.code(attempt.target.family),
            payload["manuscript.download.cancel.family.v1"],
        )
        assertEquals(
            DownloadTelemetryWire.code(attempt.target.tier),
            payload["manuscript.download.cancel.tier.v1"],
        )
        assertEquals(
            DownloadTelemetryWire.code(attempt.target.backend),
            payload["manuscript.download.cancel.backend.v1"],
        )
        assertEquals(
            attempt.generation.value,
            payload["manuscript.download.cancel.generation.v1"],
        )
        assertEquals(
            attempt.workIdentity.mostSignificantBits,
            payload["manuscript.download.cancel.work_uuid_most.v1"],
        )
        assertEquals(
            attempt.workIdentity.leastSignificantBits,
            payload["manuscript.download.cancel.work_uuid_least.v1"],
        )
        assertEquals(artifact.fileName, payload["manuscript.download.cancel.model.v1"])
        assertEquals(artifact.revision, payload["manuscript.download.cancel.version.v1"])
        assertTrue(payload.values.all { it is Int || it is Long || it is String })
        assertEquals(
            setOf(artifact.fileName, artifact.revision),
            payload.values.filterIsInstance<String>().toSet(),
        )
    }

    @Test
    fun validActionAndPayloadObtainCancellerOnceAndPassTheExactTypedAttempt() {
        val expected = attempt()
        var obtains = 0
        val received = mutableListOf<DownloadAttempt>()

        val handled = DownloadCancelReceiverCore.handle(
            action = DownloadCancelPayloadContract.ACTION_CANCEL,
            filterIdentity = DownloadCancelPayloadContract.filterIdentity(expected),
            extras = DownloadCancelPayloadContract.encode(expected),
        ) { exactAttempt ->
            obtains += 1
            received += exactAttempt
            true
        }

        assertTrue(handled)
        assertEquals(1, obtains)
        assertEquals(listOf(expected), received)
    }

    @Test
    fun everyMissingOrExtraFieldIsIgnoredBeforeAnyServiceCanBeObtained() {
        val encoded = DownloadCancelPayloadContract.encode(attempt())
        val invalidPayloads = DownloadCancelPayloadContract.requiredKeys.map { missing ->
            encoded - missing
        } + listOf(
            encoded + ("prompt" to "private user writing"),
            encoded + ("future.identity" to 1L),
        )

        invalidPayloads.forEach { invalid ->
            assertIgnoredBeforeService(invalid)
            assertNull(DownloadCancelPayloadContract.decode(invalid))
        }
    }

    @Test
    fun invalidTypesCodesGenerationTargetModelAndVersionAreIgnoredBeforeService() {
        val encoded = DownloadCancelPayloadContract.encode(attempt())
        val invalidPayloads = buildList {
            DownloadCancelPayloadContract.requiredKeys.forEach { key ->
                add(encoded + (key to listOf("wrong type")))
            }
            add(encoded + (DownloadCancelPayloadContract.KEY_FAMILY to Int.MAX_VALUE))
            add(encoded + (DownloadCancelPayloadContract.KEY_TIER to Int.MAX_VALUE))
            add(encoded + (DownloadCancelPayloadContract.KEY_BACKEND to Int.MAX_VALUE))
            add(encoded + (DownloadCancelPayloadContract.KEY_GENERATION to 0L))
            add(encoded + (DownloadCancelPayloadContract.KEY_GENERATION to -1L))
            add(
                encoded + (
                    DownloadCancelPayloadContract.KEY_TIER to
                        DownloadTelemetryWire.TIER_LEGACY_COMPACT
                ),
            )
            add(encoded + (DownloadCancelPayloadContract.KEY_MODEL to "different-model.bin"))
            add(encoded + (DownloadCancelPayloadContract.KEY_VERSION to "unpinned-version"))
        }

        invalidPayloads.forEach { invalid ->
            assertIgnoredBeforeService(invalid)
            assertNull(DownloadCancelPayloadContract.decode(invalid))
        }
    }

    @Test
    fun nullOrWrongActionIsIgnoredBeforeAnyServiceCanBeObtained() {
        val encoded = DownloadCancelPayloadContract.encode(attempt())

        listOf<String?>(null, "", "android.intent.action.CANCEL", "future.action").forEach { action ->
            var obtains = 0
            val handled = DownloadCancelReceiverCore.handle(
                action,
                DownloadCancelPayloadContract.filterIdentity(attempt()),
                encoded,
            ) {
                obtains += 1
                true
            }

            assertFalse(handled)
            assertEquals(0, obtains)
        }
    }

    @Test
    fun aWellTypedDifferentAttemptCannotBeSubstitutedForTheCurrentAttempt() {
        val current = attempt()
        val forged = current.copy(
            workIdentity = current.workIdentity.copy(
                leastSignificantBits = current.workIdentity.leastSignificantBits + 1L,
            ),
        )
        val cancelled = mutableListOf<DownloadAttempt>()

        val handled = DownloadCancelReceiverCore.handle(
            DownloadCancelPayloadContract.ACTION_CANCEL,
            DownloadCancelPayloadContract.filterIdentity(forged),
            DownloadCancelPayloadContract.encode(forged),
        ) { received ->
            if (received != current) return@handle false
            cancelled += received
            true
        }

        assertFalse(handled)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun pendingIntentFilterIdentityCannotAliasAcrossGenerationOrWorkUuid() {
        val original = attempt()
        val nextGeneration = original.copy(
            generation = DownloadGeneration(original.generation.value + 1L),
        )
        val nextWork = original.copy(
            workIdentity = original.workIdentity.copy(
                leastSignificantBits = original.workIdentity.leastSignificantBits + 1L,
            ),
        )
        val identities = listOf(original, nextGeneration, nextWork)
            .map(DownloadCancelPayloadContract::filterIdentity)

        assertEquals(identities.size, identities.toSet().size)
        assertEquals(
            "manuscript-v2://download-cancel/v1/f1-t1-b1/g7/" +
                "10203040-5060-7080-0102-030405060708",
            identities.first(),
        )
        assertFalse(identities.first().contains(DownloadTargetCatalog.artifactFor(original.target).fileName))
    }

    @Test
    fun wrongOrMissingFilterIdentityIsIgnoredBeforeAnyServiceCanBeObtained() {
        val encoded = DownloadCancelPayloadContract.encode(attempt())

        listOf<String?>(null, "", "manuscript-v2://download-cancel/v1/forged").forEach { data ->
            var obtains = 0
            val handled = DownloadCancelReceiverCore.handle(
                DownloadCancelPayloadContract.ACTION_CANCEL,
                data,
                encoded,
            ) {
                obtains += 1
                true
            }

            assertFalse(handled)
            assertEquals(0, obtains)
        }
    }

    @Test
    fun manifestDeclaresOneExplicitNonExportedReceiverWithoutAnIntentFilter() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile)
        val receiverNodes = document.getElementsByTagName("receiver")
        val matching = (0 until receiverNodes.length)
            .map { receiverNodes.item(it) as Element }
            .filter { it.androidAttribute("name") == ".DownloadCancelReceiver" }

        assertEquals(1, matching.size)
        val receiver = matching.single()
        assertEquals("false", receiver.androidAttribute("exported"))
        assertEquals(0, receiver.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun receiverDecodesThroughThePureCoreBeforeObtainingServicesAndCancelsNoOtherWay() {
        assertTrue(receiverFile.isFile)
        val source = receiverFile.readText()
        val decodeIndex = source.indexOf("DownloadCancelReceiverCore.handle(")
        val serviceIndex = source.indexOf("AndroidDownloadServices.get(")

        assertTrue(decodeIndex >= 0)
        assertTrue(serviceIndex > decodeIndex)
        assertTrue(source.contains("services.cancel(exactAttempt)"))
        assertFalse(source.contains("WorkManager"))
        assertFalse(source.contains("cancelWorkById"))
        assertFalse(source.contains("createCancelPendingIntent"))
    }

    @Test
    fun receiverPendingIntentIsExplicitImmutableAndCarriesTheExactPayload() {
        assertTrue(receiverFile.isFile)
        val source = receiverFile.readText()

        assertTrue(source.contains("Intent(context, DownloadCancelReceiver::class.java)"))
        assertTrue(source.contains("DownloadCancelPayloadContract.ACTION_CANCEL"))
        assertTrue(source.contains("DownloadCancelPayloadContract.encode(attempt)"))
        assertTrue(source.contains("DownloadCancelPayloadContract.filterIdentity(attempt)"))
        assertTrue(source.contains("setData(Uri.parse("))
        assertTrue(source.contains("PendingIntent.getBroadcast("))
        assertTrue(source.contains("PendingIntent.FLAG_IMMUTABLE"))
    }

    @Test
    fun bothWorkersUseTheTypedReceiverPendingIntentAndNeverGenericWorkManagerCancellation() {
        workerFiles.forEach { worker ->
            val source = worker.readText()
            assertTrue(worker.name, source.contains("DownloadCancelReceiver.pendingIntent("))
            assertTrue(worker.name, source.contains("attempt"))
            assertFalse(worker.name, source.contains("createCancelPendingIntent"))
            assertFalse(worker.name, source.contains("cancelWorkById"))
        }
    }

    private fun assertIgnoredBeforeService(extras: Map<String, Any?>) {
        var obtains = 0
        val handled = DownloadCancelReceiverCore.handle(
            DownloadCancelPayloadContract.ACTION_CANCEL,
            DownloadCancelPayloadContract.filterIdentity(attempt()),
            extras,
        ) {
            obtains += 1
            true
        }

        assertFalse(handled)
        assertEquals(0, obtains)
    }

    private fun attempt(): DownloadAttempt = DownloadAttempt(
        target = DownloadTargetKey(
            AiModelFamily.GEMMA,
            AiModelTier.FAST,
            BackendKind.LITERT_CPU,
        ),
        generation = DownloadGeneration(7L),
        workIdentity = DownloadWorkIdentity(0x1020304050607080L, 0x0102030405060708L),
    )

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private val literalRequiredKeys = setOf(
        "manuscript.download.cancel.family.v1",
        "manuscript.download.cancel.tier.v1",
        "manuscript.download.cancel.backend.v1",
        "manuscript.download.cancel.generation.v1",
        "manuscript.download.cancel.work_uuid_most.v1",
        "manuscript.download.cancel.work_uuid_least.v1",
        "manuscript.download.cancel.model.v1",
        "manuscript.download.cancel.version.v1",
    )

    private val root = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
    private val receiverFile = File(
        root,
        "app/src/main/java/com/echolink/mobile/DownloadCancelReceiver.kt",
    )
    private val workerFiles = listOf(
        File(root, "app/src/main/java/com/echolink/mobile/AiModelDownloadWorker.kt"),
        File(root, "app/src/main/java/com/echolink/mobile/Qwen3ModelDownloadWorker.kt"),
    )

    private val validTargets = listOf(
        DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.FAST, BackendKind.LITERT_CPU),
        DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.FAST, BackendKind.LITERT_GPU),
        DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.THOROUGH, BackendKind.LITERT_CPU),
        DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.THOROUGH, BackendKind.LITERT_GPU),
        DownloadTargetKey(
            AiModelFamily.LEGACY_QWEN,
            AiModelTier.LEGACY_COMPACT,
            BackendKind.LEGACY_LLAMA_CPU,
        ),
        DownloadTargetKey(
            AiModelFamily.LEGACY_QWEN,
            AiModelTier.LEGACY_BALANCED,
            BackendKind.LEGACY_LLAMA_CPU,
        ),
        DownloadTargetKey(
            AiModelFamily.LEGACY_QWEN,
            AiModelTier.LEGACY_ADVANCED,
            BackendKind.LEGACY_LLAMA_CPU,
        ),
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
