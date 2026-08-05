package com.echolink.mobile

import android.content.Context
import androidx.work.WorkManager
import java.io.File
import java.util.UUID

internal enum class DownloadSchedulingNetworkPolicy {
    WIFI_ONLY,
    ALLOW_MOBILE_DATA,
}

internal fun interface DownloadTargetSchedulingPolicy {
    fun policyFor(target: DownloadTargetKey): DownloadSchedulingNetworkPolicy
}

internal class ProcessSingleton<ContextType, ValueType : Any>(
    private val applicationContext: (ContextType) -> ContextType,
    private val create: (ContextType) -> ValueType,
) {
    @Volatile
    private var instance: ValueType? = null

    fun get(context: ContextType): ValueType {
        val normalized = applicationContext(context)
        return instance ?: synchronized(this) {
            instance ?: create(normalized).also { created -> instance = created }
        }
    }
}

internal class ExactFamilyDownloadTargetPolicy(
    private val gemmaPolicy: () -> DownloadSchedulingNetworkPolicy,
    private val legacyPolicy: () -> DownloadSchedulingNetworkPolicy,
) : DownloadTargetSchedulingPolicy {
    override fun policyFor(target: DownloadTargetKey): DownloadSchedulingNetworkPolicy =
        when (target.family) {
            AiModelFamily.GEMMA -> gemmaPolicy()
            AiModelFamily.LEGACY_QWEN -> legacyPolicy()
        }
}

internal fun interface DownloadTargetPartialBytes {
    fun bytesFor(target: DownloadTargetKey): Long
}

internal class ExactDownloadPartialBytes(
    private val gemmaPartialBytes: (AiRuntimeSelection) -> Long,
    private val legacyPartialBytes: (Qwen3Tier) -> Long,
) : DownloadTargetPartialBytes {
    override fun bytesFor(target: DownloadTargetKey): Long = when (target.family) {
        AiModelFamily.GEMMA -> gemmaPartialBytes(DownloadTargetCatalog.selectionFor(target))
        AiModelFamily.LEGACY_QWEN -> legacyPartialBytes(
            DownloadTargetCatalog.qwenTierFor(target),
        )
    }
}

internal class ExactDownloadReadyReconciler(
    private val gemmaReady: (AiRuntimeSelection) -> Boolean,
    private val legacyReady: (Qwen3Tier) -> Boolean,
) : DownloadReadyReconciler {
    @Suppress("UNUSED_PARAMETER")
    override fun isActiveAndSelfTested(
        target: DownloadTargetKey,
        generation: DownloadGeneration,
    ): Boolean = when (target.family) {
        AiModelFamily.GEMMA -> gemmaReady(DownloadTargetCatalog.selectionFor(target))
        AiModelFamily.LEGACY_QWEN -> legacyReady(DownloadTargetCatalog.qwenTierFor(target))
    }
}

internal class CanonicalDownloadPartialSnapshotProvider(
    private val networkReader: DownloadNetworkReader,
    private val partialBytes: DownloadTargetPartialBytes,
) : DownloadPartialSnapshotProvider {
    override fun snapshot(target: DownloadTargetKey): DownloadPartialSnapshot {
        val artifact = DownloadTargetCatalog.artifactFor(target)
        return DownloadPartialSnapshot(
            downloadedBytes = partialBytes.bytesFor(target),
            totalBytes = artifact.sizeBytes,
            network = networkReader.current(),
        )
    }
}

internal data class DownloadNetworkDecision(
    val currentNetwork: DownloadNetwork,
    val evaluation: DownloadNetworkPolicyEvaluation,
)

internal sealed interface DownloadPublishResult {
    data object Persisted : DownloadPublishResult
    data object Throttled : DownloadPublishResult
    data object Stale : DownloadPublishResult
    data object Rejected : DownloadPublishResult
}

internal class AndroidDownloadServicesCore(
    private val coordinator: DownloadLifecycleCoordinator,
    private val progressPublisher: DownloadProgressPublisher,
    private val networkReader: DownloadNetworkReader,
    private val targetPolicy: DownloadTargetSchedulingPolicy,
    private val clock: () -> Long,
) {
    private val operationLock = Any()

    fun ensure(target: DownloadTargetKey): DownloadAttempt = synchronized(operationLock) {
        coordinator.ensure(target)
    }

    fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry? =
        synchronized(operationLock) {
            coordinator.restoreTelemetry(target)
        }

    fun uiSnapshot(target: DownloadTargetKey): DownloadUiSnapshot? =
        synchronized(operationLock) {
            coordinator.uiSnapshot(target)
        }

    fun clearRecords(targets: List<DownloadTargetKey>) = synchronized(operationLock) {
        targets.forEach(coordinator::clearRecord)
    }

    fun terminalAttempt(target: DownloadTargetKey): DownloadAttempt? =
        synchronized(operationLock) {
            coordinator.terminalAttempt(target)
        }

    fun activeAttempt(target: DownloadTargetKey): DownloadAttempt? =
        synchronized(operationLock) {
            coordinator.activeAttempt(target)
        }

    fun isCurrent(attempt: DownloadAttempt): Boolean = synchronized(operationLock) {
        coordinator.isCurrent(attempt)
    }

    fun networkDecision(target: DownloadTargetKey): DownloadNetworkDecision {
        val currentNetwork = networkReader.current()
        val policy = targetPolicy.policyFor(target)
        return DownloadNetworkDecision(
            currentNetwork = currentNetwork,
            evaluation = DownloadNetworkPolicyEvaluator.evaluate(
                network = currentNetwork,
                allowMobileData =
                    policy == DownloadSchedulingNetworkPolicy.ALLOW_MOBILE_DATA,
            ),
        )
    }

    fun publish(
        attempt: DownloadAttempt,
        observation: DownloadObservation,
        finalByteCount: Boolean,
    ): DownloadPublishResult = synchronized(operationLock) {
        if (!coordinator.isCurrent(attempt)) {
            return@synchronized DownloadPublishResult.Stale
        }
        val publication = progressPublisher.offer(
            attempt = attempt,
            observation = observation,
            timestampMillis = clock(),
            isFinalByteCount = finalByteCount,
        ) ?: return@synchronized DownloadPublishResult.Throttled
        check(publication.attempt == attempt) {
            "The progress publication must retain the exact download identity."
        }
        val persisted = try {
            coordinator.publish(attempt, observation)
        } catch (failure: Throwable) {
            progressPublisher.reset(attempt)
            throw failure
        }
        if (persisted) {
            DownloadPublishResult.Persisted
        } else {
            progressPublisher.reset(attempt)
            DownloadPublishResult.Rejected
        }
    }

    fun cancel(attempt: DownloadAttempt): Boolean = synchronized(operationLock) {
        coordinator.cancel(attempt)
    }

    fun retry(attempt: DownloadAttempt): DownloadAttempt? = synchronized(operationLock) {
        coordinator.retry(attempt)
    }

    fun retryOrEnsure(target: DownloadTargetKey): DownloadAttempt? = synchronized(operationLock) {
        val terminal = coordinator.terminalAttempt(target)
        if (terminal == null) coordinator.ensure(target) else coordinator.retry(terminal)
    }

    fun failCurrent(
        attempt: DownloadAttempt,
        failureCode: DownloadFailureCode,
    ): Boolean = synchronized(operationLock) {
        coordinator.failCurrent(attempt, failureCode)
    }

    fun commitReadyIfCurrent(
        attempt: DownloadAttempt,
        readyObservation: DownloadObservation,
        action: () -> Unit,
    ): Boolean = synchronized(operationLock) {
        coordinator.commitReadyIfCurrent(attempt, readyObservation, action)
    }
}

internal class AndroidDownloadServices private constructor(context: Context) :
    ModelDownloadCommandServices {
    private val applicationContext = context.applicationContext
    private val clock: () -> Long = { System.currentTimeMillis() }
    private val networkReader = AndroidDownloadNetworkReader(applicationContext)
    private val targetPolicy = ExactFamilyDownloadTargetPolicy(
        gemmaPolicy = {
            when (AiModelManager.get(applicationContext).downloadNetworkPolicy()) {
                AiDownloadNetworkPolicy.WIFI_ONLY ->
                    DownloadSchedulingNetworkPolicy.WIFI_ONLY
                AiDownloadNetworkPolicy.ALLOW_MOBILE_DATA ->
                    DownloadSchedulingNetworkPolicy.ALLOW_MOBILE_DATA
            }
        },
        legacyPolicy = {
            when (Qwen3ModelManager.get(applicationContext).downloadNetworkPolicy()) {
                Qwen3DownloadNetworkPolicy.WIFI_ONLY ->
                    DownloadSchedulingNetworkPolicy.WIFI_ONLY
                Qwen3DownloadNetworkPolicy.ALLOW_MOBILE_DATA ->
                    DownloadSchedulingNetworkPolicy.ALLOW_MOBILE_DATA
            }
        },
    )
    private val partialBytes = ExactDownloadPartialBytes(
        gemmaPartialBytes = { selection ->
            AiModelManager.get(applicationContext).partialBytes(selection)
        },
        legacyPartialBytes = { tier ->
            Qwen3ModelManager.get(applicationContext).partialBytes(tier)
        },
    )
    private val readyReconciler = ExactDownloadReadyReconciler(
        gemmaReady = { selection ->
            AiModelManager.get(applicationContext).exactInstalledModel(selection) != null
        },
        legacyReady = { tier ->
            Qwen3ModelManager.get(applicationContext).installedModel(tier) != null
        },
    )
    private val telemetryStore = DownloadTelemetryStore(
        directory = File(
            applicationContext.noBackupFilesDir,
            DOWNLOAD_TELEMETRY_DIRECTORY,
        ),
        clock = clock,
        readyReconciler = readyReconciler,
    )
    private val progressPublisher = DownloadProgressPublisher()
    private val scheduler = WorkManagerDownloadAttemptScheduler(
        gateway = AndroidDownloadWorkManagerGateway(
            WorkManager.getInstance(applicationContext),
        ),
        localWorkAvailable = { target ->
            partialBytes.bytesFor(target) >= DownloadTargetCatalog.artifactFor(target).sizeBytes
        },
    )
    private val partialSnapshotProvider = CanonicalDownloadPartialSnapshotProvider(
        networkReader = networkReader,
        partialBytes = partialBytes,
    )
    private val coordinator = DownloadLifecycleCoordinator(
        store = telemetryStore,
        scheduler = scheduler,
        workIdentitySource = DownloadWorkIdentitySource {
            DownloadWorkIdentity.from(UUID.randomUUID())
        },
        partialSnapshotProvider = partialSnapshotProvider,
        telemetryCalculator = DownloadTelemetryCalculator(clock = clock),
    )
    private val core = AndroidDownloadServicesCore(
        coordinator = coordinator,
        progressPublisher = progressPublisher,
        networkReader = networkReader,
        targetPolicy = targetPolicy,
        clock = clock,
    )

    init {
        scheduler.migrateLegacyUniqueWorkOnce()
    }

    override fun ensure(target: DownloadTargetKey): DownloadAttempt = core.ensure(target)

    fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry? =
        core.restoreTelemetry(target)

    fun uiSnapshot(target: DownloadTargetKey): DownloadUiSnapshot? = core.uiSnapshot(target)

    fun clearRecords(targets: List<DownloadTargetKey>) = core.clearRecords(targets)

    override fun terminalAttempt(target: DownloadTargetKey): DownloadAttempt? =
        core.terminalAttempt(target)

    override fun activeAttempt(target: DownloadTargetKey): DownloadAttempt? =
        core.activeAttempt(target)

    fun isCurrent(attempt: DownloadAttempt): Boolean = core.isCurrent(attempt)

    fun networkDecision(target: DownloadTargetKey): DownloadNetworkDecision =
        core.networkDecision(target)

    fun publish(
        attempt: DownloadAttempt,
        observation: DownloadObservation,
        finalByteCount: Boolean,
    ): DownloadPublishResult = core.publish(attempt, observation, finalByteCount)

    override fun cancel(attempt: DownloadAttempt): Boolean = core.cancel(attempt)

    override fun retry(attempt: DownloadAttempt): DownloadAttempt? = core.retry(attempt)

    fun retryOrEnsure(target: DownloadTargetKey): DownloadAttempt? = core.retryOrEnsure(target)

    fun failCurrent(
        attempt: DownloadAttempt,
        failureCode: DownloadFailureCode,
    ): Boolean = core.failCurrent(attempt, failureCode)

    fun commitReadyIfCurrent(
        attempt: DownloadAttempt,
        readyObservation: DownloadObservation,
        action: () -> Unit,
    ): Boolean = core.commitReadyIfCurrent(attempt, readyObservation, action)

    companion object {
        private const val DOWNLOAD_TELEMETRY_DIRECTORY = "download_telemetry_v1"

        private val processSingleton = ProcessSingleton<Context, AndroidDownloadServices>(
            applicationContext = { context -> context.applicationContext },
            create = ::AndroidDownloadServices,
        )

        fun get(context: Context): AndroidDownloadServices = processSingleton.get(context)
    }
}
