package com.echolink.mobile

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainToneMenuDeviceTest {
    @Test
    fun missingLocalModelShowsInstallButtonAndKeepsAcceptUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // This test deliberately wipes every installed model. Preserve them
        // first so a suite run does not need a 1.3 GB re-download afterwards.
        val modelGuard = InstalledModelGuard(context)
        modelGuard.preserve()
        AiModelManager.get(context).removeAllModels()
        context.getSharedPreferences(
            "manuscript_preferences_v1",
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity

        try {
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            val installButtonId = activity.resources.getIdentifier(
                "local_ai_install_action",
                "id",
                activity.packageName,
            )
            assertNotEquals("Missing local AI must expose a real install button.", 0, installButtonId)

            instrumentation.runOnMainSync {
                val installButton = activity.findViewById<View>(installButtonId)
                assertTrue("Install local AI action must be a button.", installButton is Button)
                assertEquals(View.VISIBLE, installButton.visibility)
                assertTrue(installButton.isEnabled)
                assertTrue(installButton.isClickable)

                activity.findViewById<EditText>(R.id.review_editor).apply {
                    setText("we was going there yesterday and it dont make sense")
                    requestFocus()
                }
                activity.findViewById<View>(R.id.writing_assist_icon).performClick()
                val accept = activity.findViewById<Button>(R.id.writing_replace_action)
                assertEquals(
                    context.getString(R.string.qwen_model_install_action),
                    accept.text.toString(),
                )
                assertTrue(
                    "The review action must stay usable so it can install local AI.",
                    accept.isEnabled && accept.isClickable,
                )
            }
            instrumentation.waitForIdleSync()
            val clickRoot = waitForAccessibilityRoot(instrumentation)
            val acceptNode = clickRoot
                ?.findAccessibilityNodeInfosByViewId(
                    "${activity.packageName}:id/writing_replace_action",
                )
                ?.firstOrNull()
            assertTrue(
                "The review install action must respond to a real accessibility click. " +
                    "node=${acceptNode != null}, visible=${acceptNode?.isVisibleToUser}, " +
                    "enabled=${acceptNode?.isEnabled}, clickable=${acceptNode?.isClickable}, " +
                    "actions=${acceptNode?.actionList?.map { it.id }}, " +
                    "rootPackage=${clickRoot?.packageName}, text=${accessibilityText(clickRoot)}",
                acceptNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true,
            )
            instrumentation.waitForIdleSync()
            val promptTitle = context.getString(R.string.qwen_model_install_title)
            val activeRoot = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(
                "The review action must open local AI installation when the required model is missing. " +
                    "Active text: ${accessibilityText(activeRoot)}",
                activeRoot
                    ?.findAccessibilityNodeInfosByText(promptTitle)
                    ?.any { it.text?.toString() == promptTitle } == true,
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            modelGuard.restore()
        }
    }

    @Test
    fun pastedTextKeepsEveryToneInAVisibleSwipeableMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences(
            "manuscript_preferences_v1",
            android.content.Context.MODE_PRIVATE,
        )
        preferences.edit().putString("writing_tone", WritingTone.PROOFREAD.label).commit()

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity

        try {
            instrumentation.waitForIdleSync()
            val menuId = activity.resources.getIdentifier(
                "main_tone_menu",
                "id",
                activity.packageName,
            )
            assertNotEquals("Main paste screen must expose a tone menu.", 0, menuId)

            lateinit var menu: HorizontalScrollView
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.review_editor).setText(
                    "we was going there yesterday and it dont make sense",
                )
                val found = activity.findViewById<View>(menuId)
                assertNotNull(found)
                assertTrue(found is HorizontalScrollView)
                menu = found as HorizontalScrollView
                assertEquals(View.VISIBLE, menu.visibility)
                val visibleBounds = Rect()
                assertTrue(
                    "Tone menu must be on-screen while the paste editor is active.",
                    menu.getGlobalVisibleRect(visibleBounds) && visibleBounds.height() > 0,
                )

                val row = menu.getChildAt(0) as ViewGroup
                val buttons = (0 until row.childCount).map { row.getChildAt(it) as Button }
                assertEquals(WritingTone.entries.map { it.label }, buttons.map { it.text.toString() })
                buttons.single { it.text.toString() == WritingTone.FORMAL.label }.performClick()
                menu.scrollTo(row.width, 0)
            }
            instrumentation.waitForIdleSync()

            assertEquals(
                WritingTone.FORMAL.label,
                preferences.getString("writing_tone", null),
            )
            assertTrue("Tone menu must move horizontally.", menu.scrollX > 0)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun reviewCardUsesSwipeableToneMenuAndStaysSyncedWithMainMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences(
            "manuscript_preferences_v1",
            android.content.Context.MODE_PRIVATE,
        )
        preferences.edit().putString("writing_tone", WritingTone.PROOFREAD.label).commit()

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity

        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.review_editor).apply {
                    setText("we was going there yesterday and it dont make sense")
                    requestFocus()
                }
                activity.findViewById<View>(R.id.writing_assist_icon).performClick()
            }
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                val reviewMenu = activity.findViewById<View>(R.id.writing_tone)
                assertTrue(
                    "Review card must use a swipeable tone menu instead of a dropdown.",
                    reviewMenu is HorizontalScrollView,
                )
                reviewMenu as HorizontalScrollView
                assertEquals(View.VISIBLE, reviewMenu.visibility)

                val reviewButtons = toneButtons(reviewMenu)
                assertEquals(
                    WritingTone.entries.map { it.label },
                    reviewButtons.map { it.text.toString() },
                )
                reviewButtons.single {
                    it.text.toString() == WritingTone.FRIENDLY.label
                }.performClick()

                val mainMenu = activity.findViewById<HorizontalScrollView>(R.id.main_tone_menu)
                val mainFriendly = toneButtons(mainMenu).single {
                    it.text.toString() == WritingTone.FRIENDLY.label
                }
                assertTrue("Main tone menu must follow review-card selection.", mainFriendly.isSelected)
            }
            instrumentation.waitForIdleSync()

            assertEquals(
                WritingTone.FRIENDLY.label,
                preferences.getString("writing_tone", null),
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun acceptStaysVisibleAboveLongCorrectionText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity

        try {
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()

            lateinit var accept: Button
            lateinit var suggestion: TextView
            instrumentation.runOnMainSync {
                val card = activity.findViewById<View>(R.id.writing_suggestion_card)
                suggestion = activity.findViewById(R.id.writing_suggestion)
                suggestion.text = List(80) {
                    "Long correction line ${it + 1} must not hide the approval controls."
                }.joinToString("\n")
                card.visibility = View.VISIBLE
                accept = activity.findViewById(R.id.writing_replace_action)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                accept.requestRectangleOnScreen(
                    Rect(0, 0, accept.width, accept.height),
                    true,
                )
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val actionRow = accept.parent as View
                assertTrue(
                    "Accept must be placed above correction text so long pages cannot hide it.",
                    actionRow.top < suggestion.top,
                )
                val visibleBounds = Rect()
                assertTrue(
                    "Accept must be visible after the review opens.",
                    accept.getGlobalVisibleRect(visibleBounds) && visibleBounds.height() > 0,
                )
                assertEquals(context.getString(R.string.writing_replace_action), accept.text.toString())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun toneButtons(menu: HorizontalScrollView): List<Button> {
        val row = menu.getChildAt(0) as ViewGroup
        return (0 until row.childCount).map { row.getChildAt(it) as Button }
    }

    private fun waitForAccessibilityRoot(
        instrumentation: android.app.Instrumentation,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 3_000L
        do {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.rootInActiveWindow?.let { return it }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun accessibilityText(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val text = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(text::add)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }
        visit(root)
        return text
    }
}
