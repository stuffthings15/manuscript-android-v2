package com.echolink.mobile

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.File

/**
 * Runs the Vulkan backend in a SEPARATE process so a driver abort cannot take
 * the app down with it.
 *
 * The Adreno 740 driver aborts inside `vk::Device::createComputePipeline`.
 * That is a native C++ abort: it kills the hosting process outright and cannot
 * be caught by Kotlin. Doing the trial load here means the worst case is this
 * throwaway process dying, which [VulkanProbe] observes as a failure and
 * records as "this device is CPU only".
 */
class VulkanProbeService : Service() {
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != MESSAGE_PROBE) {
                super.handleMessage(message)
                return
            }
            val reply = message.replyTo
            val modelPath = message.data?.getString(KEY_MODEL_PATH)
            val succeeded = runCatching { runProbe(modelPath) }.getOrDefault(false)
            runCatching {
                reply?.send(
                    Message.obtain(null, MESSAGE_RESULT, if (succeeded) 1 else 0, 0),
                )
            }
            stopSelf()
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /**
     * Loads the model with the Vulkan backend available and generates a tiny
     * response. Reaching the end without the process dying is the proof that
     * this device's driver can run our pipelines.
     */
    private fun runProbe(modelPath: String?): Boolean {
        val model = modelPath?.let(::File)?.takeIf { it.isFile } ?: return false
        val runtime = Qwen3GrammarRuntime(applicationContext)
        return try {
            runtime.probeGeneration(model)
        } finally {
            runCatching { runtime.releaseResources() }
        }
    }

    companion object {
        const val MESSAGE_PROBE = 1
        const val MESSAGE_RESULT = 2
        const val KEY_MODEL_PATH = "model_path"
    }
}
