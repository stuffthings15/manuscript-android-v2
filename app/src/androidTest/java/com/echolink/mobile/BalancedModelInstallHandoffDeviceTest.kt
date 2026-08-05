package com.echolink.mobile

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves a real Install AI tap downloads the exact tier selected by hardware policy. */
@RunWith(AndroidJUnit4::class)
class BalancedModelInstallHandoffDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun installButtonStartsTheBalancedArtifactSelectedForThisEmulator() {
        val context = instrumentation.targetContext
        val selection = AiModelManager.get(context).selectedRuntime()
        assertEquals(AiModelTier.LEGACY_BALANCED, selection.artifact.tier)
        assertTrue(EncryptedEditorDraftStore(context).clear())

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        ) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(800L)
            if (!onMain { activity.hasWindowFocus() }) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
            }
            val install = activity.findViewById<View>(R.id.local_ai_install_action)
            assertTrue("Install AI must be visible before the touch.", onMain { install.isShown })
            tap(install)

            val modelDirectory = File(context.noBackupFilesDir, "qwen3_models")
            val started = waitUntil(15_000L) {
                modelDirectory.listFiles().orEmpty().any {
                    it.name.contains("1.7B") && it.length() > 0L
                }
            }
            assertTrue("The selected balanced download never started after a real tap.", started)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun tap(view: View) {
        val location = IntArray(2)
        onMain { view.getLocationOnScreen(location) }
        val x = location[0] + onMain { view.width } / 2f
        val y = location[1] + onMain { view.height } / 2f
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(now, now + 90L, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        instrumentation.uiAutomation.injectInputEvent(down, true)
        SystemClock.sleep(90L)
        instrumentation.uiAutomation.injectInputEvent(up, true)
        down.recycle()
        up.recycle()
        instrumentation.waitForIdleSync()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(250L)
        }
        return false
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
