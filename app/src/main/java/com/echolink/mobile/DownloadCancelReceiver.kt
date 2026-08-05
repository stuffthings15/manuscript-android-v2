package com.echolink.mobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Collections
import java.util.LinkedHashMap

internal object DownloadCancelPayloadContract {
    const val ACTION_CANCEL = "com.remote.Manuscript.v2.action.CANCEL_DOWNLOAD"
    const val KEY_FAMILY = "manuscript.download.cancel.family.v1"
    const val KEY_TIER = "manuscript.download.cancel.tier.v1"
    const val KEY_BACKEND = "manuscript.download.cancel.backend.v1"
    const val KEY_GENERATION = "manuscript.download.cancel.generation.v1"
    const val KEY_WORK_UUID_MOST = "manuscript.download.cancel.work_uuid_most.v1"
    const val KEY_WORK_UUID_LEAST = "manuscript.download.cancel.work_uuid_least.v1"
    const val KEY_MODEL = "manuscript.download.cancel.model.v1"
    const val KEY_VERSION = "manuscript.download.cancel.version.v1"

    val requiredKeys: Set<String> = Collections.unmodifiableSet(
        linkedSetOf(
            KEY_FAMILY,
            KEY_TIER,
            KEY_BACKEND,
            KEY_GENERATION,
            KEY_WORK_UUID_MOST,
            KEY_WORK_UUID_LEAST,
            KEY_MODEL,
            KEY_VERSION,
        ),
    )

    fun encode(attempt: DownloadAttempt): Map<String, Any> {
        val artifact = DownloadTargetCatalog.artifactFor(attempt.target)
        return Collections.unmodifiableMap(
            LinkedHashMap<String, Any>().apply {
                put(KEY_FAMILY, DownloadTelemetryWire.code(attempt.target.family))
                put(KEY_TIER, DownloadTelemetryWire.code(attempt.target.tier))
                put(KEY_BACKEND, DownloadTelemetryWire.code(attempt.target.backend))
                put(KEY_GENERATION, attempt.generation.value)
                put(KEY_WORK_UUID_MOST, attempt.workIdentity.mostSignificantBits)
                put(KEY_WORK_UUID_LEAST, attempt.workIdentity.leastSignificantBits)
                put(KEY_MODEL, artifact.fileName)
                put(KEY_VERSION, artifact.revision)
            },
        )
    }

    fun decode(extras: Map<String, Any?>): DownloadAttempt? {
        if (extras.keys != requiredKeys) return null

        val familyCode = extras[KEY_FAMILY] as? Int ?: return null
        val tierCode = extras[KEY_TIER] as? Int ?: return null
        val backendCode = extras[KEY_BACKEND] as? Int ?: return null
        val generationValue = extras[KEY_GENERATION] as? Long ?: return null
        val uuidMost = extras[KEY_WORK_UUID_MOST] as? Long ?: return null
        val uuidLeast = extras[KEY_WORK_UUID_LEAST] as? Long ?: return null
        val model = extras[KEY_MODEL] as? String ?: return null
        val version = extras[KEY_VERSION] as? String ?: return null
        if (generationValue <= 0L) return null

        val family = DownloadTelemetryWire.family(familyCode) ?: return null
        val tier = DownloadTelemetryWire.tier(tierCode) ?: return null
        val backend = DownloadTelemetryWire.backend(backendCode) ?: return null
        val target = try {
            DownloadTargetKey(family, tier, backend)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val artifact = try {
            DownloadTargetCatalog.artifactFor(target)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        }
        if (model != artifact.fileName || version != artifact.revision) return null

        return DownloadAttempt(
            target = target,
            generation = DownloadGeneration(generationValue),
            workIdentity = DownloadWorkIdentity(uuidMost, uuidLeast),
        )
    }

    fun filterIdentity(attempt: DownloadAttempt): String = buildString {
        append("manuscript-v2://download-cancel/v1/")
        append(DownloadTelemetryWire.targetToken(attempt.target))
        append("/g")
        append(attempt.generation.value)
        append('/')
        append(attempt.workIdentity.toUuid())
    }
}

internal object DownloadCancelReceiverCore {
    fun handle(
        action: String?,
        filterIdentity: String?,
        extras: Map<String, Any?>,
        cancelExact: (DownloadAttempt) -> Boolean,
    ): Boolean {
        if (action != DownloadCancelPayloadContract.ACTION_CANCEL) return false
        val exactAttempt = DownloadCancelPayloadContract.decode(extras) ?: return false
        if (filterIdentity != DownloadCancelPayloadContract.filterIdentity(exactAttempt)) {
            return false
        }
        return cancelExact(exactAttempt)
    }
}

class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val extras = intent.strictPrimitiveExtras() ?: return
        DownloadCancelReceiverCore.handle(
            action = intent.action,
            filterIdentity = intent.dataString,
            extras = extras,
        ) { exactAttempt ->
            val services = AndroidDownloadServices.get(context.applicationContext)
            services.cancel(exactAttempt)
        }
    }

    companion object {
        internal fun pendingIntent(
            context: Context,
            attempt: DownloadAttempt,
        ): PendingIntent {
            val intent = Intent(context, DownloadCancelReceiver::class.java)
                .setAction(DownloadCancelPayloadContract.ACTION_CANCEL)
                .setData(Uri.parse(DownloadCancelPayloadContract.filterIdentity(attempt)))
            DownloadCancelPayloadContract.encode(attempt).forEach { (key, value) ->
                when (value) {
                    is Int -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                    is String -> intent.putExtra(key, value)
                    else -> error("Cancel payload contains an unsupported value type.")
                }
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val REQUEST_CODE = 0
    }
}

private fun Intent.strictPrimitiveExtras(): Map<String, Any?>? {
    val bundle = try {
        extras
    } catch (_: RuntimeException) {
        return null
    } ?: return emptyMap()
    return try {
        bundle.keySet().associateWith { key -> bundle.get(key) }
    } catch (_: RuntimeException) {
        null
    }
}
