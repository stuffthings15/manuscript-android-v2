package com.echolink.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Decides once per device whether the Vulkan backend is safe to use.
 *
 * The probe runs in [VulkanProbeService], which lives in its own process. A
 * driver that aborts kills only that process; this class sees the connection
 * die, records CPU-only, and never asks again. A clean result is remembered
 * the same way, so the cost is paid once.
 */
object VulkanProbe {
    private const val PREFERENCES = "manuscript_vulkan_probe"
    private const val KEY_RESULT = "vulkan_usable"
    private const val KEY_PROBED_MODEL = "probed_model"
    private const val KEY_ATTEMPT_STARTED = "attempt_started"
    private const val PROBE_TIMEOUT_SECONDS = 90L

    enum class Decision { USABLE, CPU_ONLY, UNKNOWN }

    fun decision(context: Context, model: File): Decision {
        val preferences = preferences(context)
        val probedModel = preferences.getString(KEY_PROBED_MODEL, null)
        if (probedModel != model.name) return Decision.UNKNOWN
        if (!preferences.contains(KEY_RESULT)) return Decision.UNKNOWN
        return if (preferences.getBoolean(KEY_RESULT, false)) {
            Decision.USABLE
        } else {
            Decision.CPU_ONLY
        }
    }

    /**
     * Runs the probe if this model has never been probed. Blocking: callers
     * use it from a worker thread before the first GPU-backed generation.
     */
    /**
     * Hides every Vulkan device from llama.cpp in this process until a probe
     * proves the driver safe. Called before the first native init so a bad
     * driver never reaches pipeline creation in the hosting process.
     */
    fun suppressUntilProven(context: Context, model: File?) {
        val allow = model != null && decision(context, model) == Decision.USABLE
        runCatching {
            if (allow) {
                android.system.Os.unsetenv("GGML_VK_VISIBLE_DEVICES")
            } else {
                android.system.Os.setenv("GGML_VK_VISIBLE_DEVICES", "", true)
            }
        }
    }

    fun ensureProbed(context: Context, model: File): Decision {
        decision(context, model).takeIf { it != Decision.UNKNOWN }?.let { return it }
        val preferences = preferences(context)
        // A probe that started but never recorded a result means the service
        // process died: that is exactly the abort case, so treat it as
        // CPU-only rather than retrying the crash on every launch.
        if (preferences.getString(KEY_ATTEMPT_STARTED, null) == model.name) {
            record(context, model, usable = false)
            return Decision.CPU_ONLY
        }
        preferences.edit().putString(KEY_ATTEMPT_STARTED, model.name).commit()
        val usable = runCatching { runProbe(context, model) }.getOrDefault(false)
        record(context, model, usable)
        return if (usable) Decision.USABLE else Decision.CPU_ONLY
    }

    private fun record(context: Context, model: File, usable: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_RESULT, usable)
            .putString(KEY_PROBED_MODEL, model.name)
            .remove(KEY_ATTEMPT_STARTED)
            .commit()
    }

    private fun runProbe(context: Context, model: File): Boolean {
        val latch = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = HandlerThread("manuscript-vulkan-probe").apply { start() }
        val replyMessenger = Messenger(
            object : Handler(thread.looper) {
                override fun handleMessage(message: Message) {
                    if (message.what == VulkanProbeService.MESSAGE_RESULT) {
                        result.set(message.arg1 == 1)
                        latch.countDown()
                    }
                }
            },
        )
        var connection: ServiceConnection? = null
        try {
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val outgoing = Message.obtain(null, VulkanProbeService.MESSAGE_PROBE).apply {
                        data = Bundle().apply {
                            putString(VulkanProbeService.KEY_MODEL_PATH, model.absolutePath)
                        }
                        replyTo = replyMessenger
                    }
                    runCatching { Messenger(binder).send(outgoing) }
                        .onFailure { latch.countDown() }
                }

                // The service process died, which is the driver-abort case.
                override fun onServiceDisconnected(name: ComponentName?) = latch.countDown()
                override fun onBindingDied(name: ComponentName?) = latch.countDown()
                override fun onNullBinding(name: ComponentName?) = latch.countDown()
            }
            val bound = context.applicationContext.bindService(
                Intent(context, VulkanProbeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) return false
            latch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return result.get()
        } finally {
            connection?.let { runCatching { context.applicationContext.unbindService(it) } }
            thread.quitSafely()
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
