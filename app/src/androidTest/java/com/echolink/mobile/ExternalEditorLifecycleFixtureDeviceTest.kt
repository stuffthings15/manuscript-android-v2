package com.echolink.mobile

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalEditorLifecycleFixtureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun normalMultilineEmptyAndPasswordModesExposeRealFocusedEditors() {
        val modes = listOf(
            S23ExternalEditorFixtureActivity.MODE_NORMAL,
            S23ExternalEditorFixtureActivity.MODE_MULTILINE,
            S23ExternalEditorFixtureActivity.MODE_EMPTY,
            S23ExternalEditorFixtureActivity.MODE_PASSWORD,
        )

        modes.forEach { mode ->
            val activity = launch(mode)
            val editor = activity.window.decorView
                .findViewWithTag<EditText>(S23ExternalEditorFixtureActivity.EDITOR_TAG)
            tap(editor)
            assertTrue(editor.isAttachedToWindow)
            assertTrue(editor.hasFocus())
            if (mode == S23ExternalEditorFixtureActivity.MODE_PASSWORD) {
                assertTrue(editor.isPasswordInputType())
            }
            activity.finish()
        }
    }

    @Test
    fun detachedAndTargetLossModesUseRealTouchEvents() {
        listOf(
            S23ExternalEditorFixtureActivity.MODE_DETACHED,
            S23ExternalEditorFixtureActivity.MODE_TARGET_LOSS,
        ).forEach { mode ->
            val activity = launch(mode)
            val editor = activity.window.decorView
                .findViewWithTag<EditText>(S23ExternalEditorFixtureActivity.EDITOR_TAG)
            val action = activity.window.decorView
                .findViewWithTag<View>(S23ExternalEditorFixtureActivity.LIFECYCLE_ACTION_TAG)
            tap(action)
            instrumentation.waitForIdleSync()
            assertFalse(editor.isAttachedToWindow && editor.hasFocus())
            activity.finish()
        }
    }

    @Test
    fun packageSwitchModeLeavesTheFixtureUsingARealTouchEvent() {
        val activity = launch(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH)
        val action = activity.window.decorView
            .findViewWithTag<View>(S23ExternalEditorFixtureActivity.LIFECYCLE_ACTION_TAG)

        tap(action)
        instrumentation.waitForIdleSync()

        val activePackage = instrumentation.uiAutomation.rootInActiveWindow
            ?.packageName?.toString().orEmpty()
        assertNotEquals(instrumentation.context.packageName, activePackage)
        activity.finish()
    }

    private fun launch(mode: String): Activity = instrumentation.startActivitySync(
        Intent(instrumentation.context, S23ExternalEditorFixtureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(S23ExternalEditorFixtureActivity.EXTRA_MODE, mode)
        },
    ).also { instrumentation.waitForIdleSync() }

    private fun tap(view: View) {
        val location = IntArray(2).also(view::getLocationOnScreen)
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        val downAt = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0),
        )
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0),
        )
    }

    private fun EditText.isPasswordInputType(): Boolean =
        transformationMethod is android.text.method.PasswordTransformationMethod
}
