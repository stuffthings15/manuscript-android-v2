package com.echolink.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class S23ExternalEditorFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val editor = EditText(this).apply {
            id = View.generateViewId()
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
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(20))
            addView(TextView(this@S23ExternalEditorFixtureActivity).apply {
                text = "Manuscript generated field fixture"
                textSize = 18f
                setTextColor(Color.BLACK)
            })
            addView(
                editor,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_EMPTY = "empty"
        const val MODE_NORMAL = "normal"
        const val MODE_MULTILINE = "multiline"
        const val MODE_PASSWORD = "password"
        const val MODE_TRANSIENT = "transient"
        const val TRANSIENT_CLEAR_DELAY_MS = 900L
    }
}
