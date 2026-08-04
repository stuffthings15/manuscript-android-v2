package com.echolink.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class S23ExternalEditorFixtureActivity : Activity() {
    @Volatile
    var resumeCount = 0
        private set

    @Volatile
    var stopCount = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val editor = EditText(this).apply {
            id = View.generateViewId()
            tag = EDITOR_TAG
            minHeight = dp(56)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            hint = "Generated test text"
            when (mode) {
                MODE_EMPTY -> setText("")
                MODE_MULTILINE -> {
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 4
                    setText("Generated first line.\nGenerated second line.")
                }
                MODE_PASSWORD -> {
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                    transformationMethod = PasswordTransformationMethod.getInstance()
                    setText("generated-password-fixture")
                }
                MODE_TRANSIENT -> setText("Generated transient text.")
                else -> setText("This are generated test words.")
            }
            setSelection(text.length)
        }
        val structuralSecondEditor = if (mode == MODE_STRUCTURAL_FIELDS) {
            EditText(this).apply {
                tag = SECOND_EDITOR_TAG
                contentDescription = STRUCTURAL_FIELD_TWO_DESCRIPTION
                minHeight = dp(56)
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
                setText("Second generated field.")
                setSelection(text.length)
            }
        } else {
            null
        }
        if (mode == MODE_STRUCTURAL_FIELDS) {
            editor.id = R.id.review_editor
            editor.contentDescription = STRUCTURAL_FIELD_ONE_DESCRIPTION
            structuralSecondEditor?.id = R.id.review_editor
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(20))
            addView(TextView(this@S23ExternalEditorFixtureActivity).apply {
                text = "Manuscript generated field fixture"
                textSize = 18f
                setTextColor(Color.BLACK)
            })
            if (structuralSecondEditor != null) {
                addView(LinearLayout(this@S23ExternalEditorFixtureActivity).apply {
                    addView(editor, matchWidthWrapHeight())
                })
                addView(LinearLayout(this@S23ExternalEditorFixtureActivity).apply {
                    addView(structuralSecondEditor, matchWidthWrapHeight())
                })
            } else {
                addView(editor, matchWidthWrapHeight())
            }
            if (mode in LIFECYCLE_MODES || structuralSecondEditor != null) {
                addView(Button(this@S23ExternalEditorFixtureActivity).apply {
                    tag = LIFECYCLE_ACTION_TAG
                    text = when (mode) {
                        MODE_DETACHED -> "Detach generated field"
                        MODE_TARGET_LOSS -> "Move focus from generated field"
                        MODE_STRUCTURAL_FIELDS -> "Focus second generated field"
                        else -> "Switch from generated field"
                    }
                    setOnClickListener {
                        when (mode) {
                            MODE_DETACHED -> (editor.parent as? ViewGroup)?.removeView(editor)
                            MODE_TARGET_LOSS -> {
                                editor.clearFocus()
                                requestFocus()
                            }
                            MODE_PACKAGE_SWITCH -> startActivity(Intent(Settings.ACTION_SETTINGS))
                            MODE_STRUCTURAL_FIELDS -> {
                                editor.clearFocus()
                                structuralSecondEditor?.requestFocus()
                            }
                        }
                    }
                })
            }
        })
        editor.post {
            editor.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        if (mode == MODE_TRANSIENT) {
            editor.postDelayed({ editor.text.clear() }, TRANSIENT_CLEAR_DELAY_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount += 1
    }

    override fun onStop() {
        stopCount += 1
        super.onStop()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_EMPTY = "empty"
        const val MODE_NORMAL = "normal"
        const val MODE_MULTILINE = "multiline"
        const val MODE_PASSWORD = "password"
        const val MODE_TRANSIENT = "transient"
        const val MODE_DETACHED = "detached"
        const val MODE_TARGET_LOSS = "target-loss"
        const val MODE_PACKAGE_SWITCH = "package-switch"
        const val MODE_STRUCTURAL_FIELDS = "structural-fields"
        const val EDITOR_TAG = "external-fixture-editor"
        const val SECOND_EDITOR_TAG = "external-fixture-editor-second"
        const val STRUCTURAL_FIELD_ONE_DESCRIPTION = "Generated structural field one"
        const val STRUCTURAL_FIELD_TWO_DESCRIPTION = "Generated structural field two"
        const val LIFECYCLE_ACTION_TAG = "external-fixture-lifecycle-action"
        const val TRANSIENT_CLEAR_DELAY_MS = 900L
        private val LIFECYCLE_MODES = setOf(
            MODE_DETACHED,
            MODE_TARGET_LOSS,
            MODE_PACKAGE_SWITCH,
        )
    }
}
