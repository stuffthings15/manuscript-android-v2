package com.echolink.mobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

internal object DownloadRetryPayloadContract {
    const val ACTION_RETRY = "com.remote.Manuscript.v2.action.RETRY_DOWNLOAD"

    fun filterIdentity(attempt: DownloadAttempt): String = buildString {
        append("manuscript-v2://download-retry/v1/")
        append(DownloadTelemetryWire.targetToken(attempt.target))
        append("/g")
        append(attempt.generation.value)
        append('/')
        append(attempt.workIdentity.toUuid())
    }
}

internal object DownloadRetryReceiverCore {
    fun handle(
        action: String?,
        filterIdentity: String?,
        extras: Map<String, Any?>,
        retryExact: (DownloadAttempt) -> Boolean,
    ): Boolean {
        if (action != DownloadRetryPayloadContract.ACTION_RETRY) return false
        val exactAttempt = DownloadCancelPayloadContract.decode(extras) ?: return false
        if (filterIdentity != DownloadRetryPayloadContract.filterIdentity(exactAttempt)) {
            return false
        }
        return retryExact(exactAttempt)
    }
}

class DownloadRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val extras = intent.strictRetryPrimitiveExtras() ?: return
        DownloadRetryReceiverCore.handle(
            action = intent.action,
            filterIdentity = intent.dataString,
            extras = extras,
        ) { exactAttempt ->
            AndroidDownloadServices.get(context.applicationContext).retry(exactAttempt) != null
        }
    }

    companion object {
        internal fun pendingIntent(
            context: Context,
            attempt: DownloadAttempt,
        ): PendingIntent {
            val intent = Intent(context, DownloadRetryReceiver::class.java)
                .setAction(DownloadRetryPayloadContract.ACTION_RETRY)
                .setData(Uri.parse(DownloadRetryPayloadContract.filterIdentity(attempt)))
            DownloadCancelPayloadContract.encode(attempt).forEach { (key, value) ->
                when (value) {
                    is Int -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                    is String -> intent.putExtra(key, value)
                    else -> error("Retry payload contains an unsupported value type.")
                }
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val REQUEST_CODE = 1
    }
}

private fun Intent.strictRetryPrimitiveExtras(): Map<String, Any?>? {
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
