package com.echolink.mobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class DownloadRetryReceiverContractTest {
    @Test
    fun retryActionPassesTheExactTerminalAttemptToOneFreshRetryOperation() {
        val expected = DownloadAttempt(
            target = DownloadTargetKey(
                AiModelFamily.GEMMA,
                AiModelTier.FAST,
                BackendKind.LITERT_CPU,
            ),
            generation = DownloadGeneration(7L),
            workIdentity = DownloadWorkIdentity(11L, 12L),
        )
        val received = mutableListOf<DownloadAttempt>()

        val handled = DownloadRetryReceiverCore.handle(
            action = DownloadRetryPayloadContract.ACTION_RETRY,
            filterIdentity = DownloadRetryPayloadContract.filterIdentity(expected),
            extras = DownloadCancelPayloadContract.encode(expected),
        ) { exactAttempt ->
            received += exactAttempt
            true
        }

        assertTrue(handled)
        assertEquals(listOf(expected), received)
    }

    @Test
    fun retryReceiverIsExplicitNonExportedAndHasNoImplicitIntentFilter() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(
            File(
                checkNotNull(System.getProperty("echoLinkAndroidDir")),
                "app/src/main/AndroidManifest.xml",
            ),
        )
        val receivers = document.getElementsByTagName("receiver")
        val matching = (0 until receivers.length)
            .map { receivers.item(it) as Element }
            .filter {
                it.getAttributeNS(ANDROID_NAMESPACE, "name") == ".DownloadRetryReceiver"
            }

        assertEquals(1, matching.size)
        assertEquals("false", matching.single().getAttributeNS(ANDROID_NAMESPACE, "exported"))
        assertEquals(0, matching.single().getElementsByTagName("intent-filter").length)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
