package com.echolink.mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadTelemetryControlsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var target: DownloadTargetKey
    private lateinit var store: DownloadTelemetryStore
    private lateinit var gateway: RecordingNoWorkGateway
    private lateinit var mainPreferencesSnapshot: PreferenceSnapshot
    private lateinit var legacyPreferencesSnapshot: PreferenceSnapshot
    private lateinit var telemetrySnapshots: List<FileSnapshot>
    private var restoreGateway: (() -> Unit)? = null
    private var activity: MainActivity? = null

    @Before
    fun setUp() {
        context = instrumentation.targetContext
        mainPreferencesSnapshot = PreferenceSnapshot.capture(
            context.getSharedPreferences(MAIN_AI_PREFERENCES, Context.MODE_PRIVATE),
        )
        legacyPreferencesSnapshot = PreferenceSnapshot.capture(
            context.getSharedPreferences(LEGACY_AI_PREFERENCES, Context.MODE_PRIVATE),
        )
        configureStableLegacyTarget(context)
        target = DownloadTargetKey(
            family = AiModelFamily.LEGACY_QWEN,
            tier = AiModelTier.LEGACY_COMPACT,
            backend = BackendKind.LEGACY_LLAMA_CPU,
        )
        assertEquals(
            target,
            DownloadTargetCatalog.targetFor(AiModelManager.get(context).selectedRuntime()),
        )

        store = DownloadTelemetryStore(
            directory = File(context.noBackupFilesDir, DOWNLOAD_TELEMETRY_DIRECTORY),
            clock = System::currentTimeMillis,
            readyReconciler = DownloadReadyReconciler { _, _ -> false },
        )
        telemetrySnapshots = listOf(
            DownloadTelemetryStore.recordFile(storeDirectory(), target),
            DownloadTelemetryStore.generationFile(storeDirectory(), target),
        ).map(FileSnapshot::capture)
        store.clearRecord(target)

        gateway = RecordingNoWorkGateway()
        restoreGateway = replaceDownloadGateway(
            services = AndroidDownloadServices.get(context),
            replacement = gateway,
        )
    }

    @After
    fun tearDown() {
        activity?.let { current ->
            if (!current.isFinishing) {
                instrumentation.runOnMainSync { current.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        restoreGateway?.invoke()
        if (::telemetrySnapshots.isInitialized) {
            telemetrySnapshots.forEach(FileSnapshot::restore)
        }
        if (::mainPreferencesSnapshot.isInitialized) {
            mainPreferencesSnapshot.restore(
                context.getSharedPreferences(MAIN_AI_PREFERENCES, Context.MODE_PRIVATE),
            )
        }
        if (::legacyPreferencesSnapshot.isInitialized) {
            legacyPreferencesSnapshot.restore(
                context.getSharedPreferences(LEGACY_AI_PREFERENCES, Context.MODE_PRIVATE),
            )
        }
    }

    @Test
    fun retryTapCreatesExactNextPreparingGenerationWithoutStartingDownload() {
        val failed = seedFailedAttempt()
        launchMainActivityAndDismissStartupDialog()

        onView(withId(R.id.local_ai_retry_action))
            .check(matches(isDisplayed()))
            .perform(click())

        val retried = checkNotNull(store.read(target))
        assertEquals(DownloadPhase.PREPARING, retried.telemetry.phase)
        assertEquals(failed.telemetry.generation.value + 1L, retried.telemetry.generation.value)
        assertNotEquals(failed.workIdentity, retried.workIdentity)
        assertTrue(failed.workIdentity.toUuid() in gateway.cancelledWorkIds)
        val request = gateway.enqueuedRequests.single()
        assertEquals(retried.workIdentity.toUuid(), request.id)
        assertEquals(
            DownloadAttempt.from(retried),
            DownloadWorkContract.decode(request.workSpec.input, request.id),
        )

        onView(withId(R.id.local_ai_cancel_action)).check(matches(isDisplayed()))
    }

    @Test
    fun cancelTapPausesExactGenerationWithoutStartingDownload() {
        val downloading = seedDownloadingAttempt()
        launchMainActivityAndDismissStartupDialog()
        val scheduledWorkBeforeCancel = gateway.enqueuedRequests.map { request ->
            request.id to DownloadWorkContract.decode(request.workSpec.input, request.id)
        }
        assertTrue(scheduledWorkBeforeCancel.isNotEmpty())
        assertTrue(
            scheduledWorkBeforeCancel.all { (workId, attempt) ->
                workId == downloading.workIdentity.toUuid() &&
                    attempt == DownloadAttempt.from(downloading)
            },
        )

        onView(withId(R.id.local_ai_cancel_action))
            .check(matches(isDisplayed()))
            .perform(click())

        val paused = checkNotNull(store.read(target))
        assertEquals(DownloadPhase.PAUSED, paused.telemetry.phase)
        assertEquals(downloading.telemetry.generation, paused.telemetry.generation)
        assertEquals(downloading.workIdentity, paused.workIdentity)
        assertEquals(DownloadBlockReason.USER_PAUSED, paused.telemetry.blockReason)
        assertTrue(downloading.workIdentity.toUuid() in gateway.cancelledWorkIds)
        assertEquals(
            scheduledWorkBeforeCancel,
            gateway.enqueuedRequests.map { request ->
                request.id to DownloadWorkContract.decode(request.workSpec.input, request.id)
            },
        )

        onView(withId(R.id.local_ai_retry_action)).check(matches(isDisplayed()))
    }

    private fun seedFailedAttempt(): DurableDownloadRecord {
        val reserved = reserveAttempt(downloadedBytes = 4_096L)
        val failed = DownloadTelemetryCalculator(System::currentTimeMillis).record(
            observation = DownloadObservation(
                generation = reserved.telemetry.generation,
                phase = DownloadPhase.FAILED,
                downloadedBytes = reserved.telemetry.downloadedBytes,
                totalBytes = reserved.telemetry.totalBytes,
                network = DownloadNetwork.WIFI,
                failureCode = DownloadFailureCode.DOWNLOAD_INTERRUPTED,
                cancellationAllowed = false,
                retryAllowed = true,
            ),
            previous = reserved.telemetry,
        )
        val record = reserved.copy(telemetry = failed)
        check(store.write(record)) { "Failed telemetry was not seeded." }
        return record
    }

    private fun seedDownloadingAttempt(): DurableDownloadRecord {
        val reserved = reserveAttempt(downloadedBytes = 8_192L)
        val downloading = DownloadTelemetryCalculator(System::currentTimeMillis).record(
            observation = DownloadObservation(
                generation = reserved.telemetry.generation,
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = reserved.telemetry.downloadedBytes,
                totalBytes = reserved.telemetry.totalBytes,
                network = DownloadNetwork.WIFI,
                cancellationAllowed = true,
                retryAllowed = false,
            ),
            previous = reserved.telemetry,
        )
        val record = reserved.copy(telemetry = downloading)
        check(store.write(record)) { "Downloading telemetry was not seeded." }
        return record
    }

    private fun reserveAttempt(downloadedBytes: Long): DurableDownloadRecord =
        store.reserveNextGeneration(
            target = target,
            workIdentity = DownloadWorkIdentity.from(UUID.randomUUID()),
            network = DownloadNetwork.WIFI,
            downloadedBytes = downloadedBytes,
            totalBytes = DownloadTargetCatalog.artifactFor(target).sizeBytes,
        )

    private fun storeDirectory(): File =
        File(context.noBackupFilesDir, DOWNLOAD_TELEMETRY_DIRECTORY)

    private fun launchMainActivityAndDismissStartupDialog() {
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        instrumentation.waitForIdleSync()
        onView(withText(R.string.qwen_model_not_now))
            .inRoot(isDialog())
            .perform(click())
        instrumentation.waitForIdleSync()
        assertTrue(activity?.isFinishing == false)
    }

    private fun configureStableLegacyTarget(context: Context) {
        check(
            context.getSharedPreferences(MAIN_AI_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("download_consent", true)
                .putBoolean("download_prompt_seen", true)
                .putBoolean("force_legacy_backend_debug", true)
                .putString("download_network_policy", AiDownloadNetworkPolicy.WIFI_ONLY.name)
                .putString("user_mode", AiUserMode.FASTER.name)
                .commit(),
        )
        check(
            context.getSharedPreferences(LEGACY_AI_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("download_consent", true)
                .putBoolean("download_prompt_seen", true)
                .putString(
                    "download_network_policy",
                    Qwen3DownloadNetworkPolicy.WIFI_ONLY.name,
                )
                .putString("user_mode", Qwen3UserMode.FASTER.name)
                .commit(),
        )
    }

    private fun replaceDownloadGateway(
        services: AndroidDownloadServices,
        replacement: DownloadWorkManagerGateway,
    ): () -> Unit {
        val schedulerField = services.javaClass.getDeclaredField("scheduler").apply {
            isAccessible = true
        }
        val scheduler = schedulerField.get(services) as WorkManagerDownloadAttemptScheduler
        val gatewayField = scheduler.javaClass.getDeclaredField("gateway").apply {
            isAccessible = true
        }
        val original = gatewayField.get(scheduler)
        gatewayField.set(scheduler, replacement)
        return { gatewayField.set(scheduler, original) }
    }

    private class RecordingNoWorkGateway : DownloadWorkManagerGateway {
        val enqueuedRequests = mutableListOf<OneTimeWorkRequest>()
        val cancelledWorkIds = mutableListOf<UUID>()

        override fun enqueueUniqueWork(
            uniqueName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            enqueuedRequests += request
        }

        override fun cancelWorkById(id: UUID) {
            cancelledWorkIds += id
        }

        override fun cancelUniqueWork(uniqueName: String) = Unit
    }

    private data class FileSnapshot(
        val file: File,
        val bytes: ByteArray?,
    ) {
        fun restore() {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.delete()
            if (bytes == null) {
                file.delete()
            } else {
                check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
                file.writeBytes(bytes)
            }
        }

        companion object {
            fun capture(file: File): FileSnapshot = FileSnapshot(
                file = file,
                bytes = file.takeIf(File::isFile)?.readBytes(),
            )
        }
    }

    private data class PreferenceSnapshot(
        val values: Map<String, *>,
    ) {
        fun restore(preferences: SharedPreferences) {
            val editor = preferences.edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit())
        }

        companion object {
            fun capture(preferences: SharedPreferences): PreferenceSnapshot =
                PreferenceSnapshot(HashMap(preferences.all))
        }
    }

    private companion object {
        const val DOWNLOAD_TELEMETRY_DIRECTORY = "download_telemetry_v1"
        const val MAIN_AI_PREFERENCES = "manuscript_ai"
        const val LEGACY_AI_PREFERENCES = "manuscript_qwen3"
    }
}
