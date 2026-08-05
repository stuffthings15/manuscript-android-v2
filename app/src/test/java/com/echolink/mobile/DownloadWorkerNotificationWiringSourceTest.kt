package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWorkerNotificationWiringSourceTest {
    private val root = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val workers = listOf(
        File(root, "app/src/main/java/com/echolink/mobile/AiModelDownloadWorker.kt").readText(),
        File(root, "app/src/main/java/com/echolink/mobile/Qwen3ModelDownloadWorker.kt").readText(),
    )

    @Test
    fun bothWorkersRenderOnlyTheExactDurableSnapshotAndRefreshPersistedChanges() {
        workers.forEach { source ->
            assertTrue(source.contains("DownloadWorkerNotificationState.from"))
            assertTrue(source.contains("DownloadForegroundNotificationFactory.build"))
            assertTrue(source.contains("NotifyingExactDownloadServiceOperations"))
            assertTrue(source.contains("DownloadRetryReceiver.pendingIntent"))
            assertTrue(source.contains("DownloadCancelReceiver.pendingIntent"))
            assertFalse(source.contains("R.string.qwen_download_progress"))
        }
    }
}
