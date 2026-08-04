package com.echolink.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Play-safe selected-text integration for editors outside Manuscript. */
class ProcessTextActivity : Activity() {
    private lateinit var suggestionView: TextView
    private lateinit var suggestionNote: TextView
    private lateinit var toneSpinner: Spinner
    private lateinit var replaceButton: Button
    private var sourceText = ""
    private var tone = WritingTone.PROOFREAD
    private var readOnly = false
    private var modelReplacement: String? = null
    private var terminalRetryAvailable = false
    private var modelRequestEpoch = 0
    private var activeModelRequestKey: Pair<String, WritingTone>? = null
    private val activeModelRequest = LatestRewriteRequestSlot()
    private val grammarEngine by lazy { OnDeviceGrammarEngine.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (intent.action != Intent.ACTION_PROCESS_TEXT) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val incomingText = runCatching {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        }.getOrNull()
        sourceText = ExternalWritingPolicy.materializeReviewText(incomingText) ?: run {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        tone = WritingTone.fromLabel(
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getString(TONE_KEY, WritingTone.PROOFREAD.label).orEmpty(),
        )
        buildUi()
        renderSuggestion()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val card = LinearLayout(this).apply {
            id = R.id.process_text_card
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(COLOR_CARD, 18f, COLOR_BORDER)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                accessibilityPaneTitle = getString(R.string.correction_card_title)
            }
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_manuscript_assist)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        heading.addView(TextView(this).apply {
            text = getString(R.string.process_text_heading)
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        })
        card.addView(heading, matchWidth(10))
        card.addView(caption(R.string.original_label))
        card.addView(TextView(this).apply {
            id = R.id.process_text_original
            text = sourceText
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setTextIsSelectable(true)
        }, matchWidth(12))
        card.addView(caption(R.string.suggestion_label))
        suggestionView = TextView(this).apply {
            id = R.id.process_text_suggestion
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setTextIsSelectable(true)
        }
        card.addView(suggestionView, matchWidth(12))
        suggestionNote = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(COLOR_TEXT_MUTED)
        }
        card.addView(suggestionNote, matchWidth(12))
        card.addView(caption(R.string.tone_label))
        toneSpinner = Spinner(this).apply {
            id = R.id.process_text_tone
            minimumHeight = dp(MIN_TOUCH_TARGET_DP)
            background = rounded(COLOR_SURFACE, 12f, COLOR_BORDER)
            adapter = ToneAdapter()
            setSelection(WritingTone.entries.indexOf(tone).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val nextTone = WritingTone.entries.getOrElse(position) { WritingTone.PROOFREAD }
                    if (nextTone == tone) return
                    tone = nextTone
                    getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
                        .putString(TONE_KEY, tone.label).apply()
                    renderSuggestion()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        card.addView(toneSpinner, matchWidth(12))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        replaceButton = actionButton(R.string.writing_replace_action, primary = true) {
            if (
                !LocalAiCorrectionGate.isReady(
                    AiModelManager.get(this).installedFile(),
                )
            ) {
                openModelSetup()
            } else if (terminalRetryAvailable) {
                renderSuggestion()
            } else if (!readOnly) {
                returnReplacement()
            }
        }.apply {
            id = R.id.process_text_replace
            isEnabled = false
        }
        buttons.addView(replaceButton, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { marginEnd = dp(6) })
        buttons.addView(actionButton(R.string.writing_dismiss_action, primary = false) {
            setResult(RESULT_CANCELED)
            finish()
        }.apply { id = R.id.process_text_dismiss }, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { marginStart = dp(6) })
        card.addView(buttons)
        if (readOnly) card.addView(TextView(this).apply {
            text = getString(R.string.process_text_read_only)
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(10), 0, 0)
        })
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(18))
            addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        scroll.addView(shell)
        setContentView(scroll)
    }

    private fun renderSuggestion() {
        if (!::suggestionView.isInitialized || !::replaceButton.isInitialized) return
        val installedAi = AiModelManager.get(this).installedFile()
        if (!LocalAiCorrectionGate.isReady(installedAi)) {
            activeModelRequest.cancel()
            activeModelRequestKey = null
            modelReplacement = null
            terminalRetryAvailable = false
            suggestionView.text = getString(R.string.local_ai_model_required)
            suggestionNote.text = getString(R.string.local_ai_on_device)
            replaceButton.text = getString(R.string.qwen_model_install_action)
            replaceButton.isEnabled = true
            return
        }
        replaceButton.text = getString(R.string.writing_replace_action)
        val toneAtRequest = tone
        val requestKey = sourceText to toneAtRequest
        if (activeModelRequestKey == requestKey) return
        activeModelRequestKey = requestKey
        val epoch = ++modelRequestEpoch
        modelReplacement = null
        terminalRetryAvailable = false
        replaceButton.isEnabled = false
        suggestionView.text = getString(R.string.local_ai_checking)
        suggestionNote.text = getString(R.string.local_ai_on_device)
        activeModelRequest.replace(grammarEngine.polishTerminal(sourceText, toneAtRequest) { terminalResult ->
            if (activeModelRequestKey == requestKey) activeModelRequestKey = null
            if (isFinishing || modelRequestEpoch != epoch || tone != toneAtRequest) {
                return@polishTerminal
            }
            when (terminalResult) {
                is CorrectionTerminalResult.Success -> {
                    modelReplacement = terminalResult.correctedText
                    terminalRetryAvailable = false
                    suggestionView.text = inlineCorrectionText(
                        sourceText,
                        terminalResult.correctedText,
                    )
                    suggestionNote.text = InlineEditDiff.note(
                        sourceText,
                        terminalResult.correctedText,
                        toneAtRequest,
                    )
                    replaceButton.isEnabled = !readOnly
                }
                is CorrectionTerminalResult.RetryableFailure -> {
                    modelReplacement = null
                    terminalRetryAvailable = true
                    suggestionView.text = terminalResult.userMessage
                    suggestionNote.text = getString(R.string.local_ai_on_device)
                    replaceButton.text = getString(R.string.qwen_model_retry_action)
                    replaceButton.isEnabled = true
                }
                CorrectionTerminalResult.ModelRequired -> {
                    modelReplacement = null
                    terminalRetryAvailable = false
                    suggestionView.text = getString(R.string.local_ai_model_required)
                    suggestionNote.text = getString(R.string.local_ai_on_device)
                    replaceButton.text = getString(R.string.qwen_model_install_action)
                    replaceButton.isEnabled = true
                }
            }
        })
    }

    private fun openModelSetup() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_MODEL_SETUP, true)
        })
    }

    override fun onResume() {
        super.onResume()
        if (::suggestionView.isInitialized) renderSuggestion()
    }

    override fun onDestroy() {
        activeModelRequest.cancel()
        sourceText = ""
        modelReplacement = null
        super.onDestroy()
    }

    private fun inlineCorrectionText(original: String, replacement: String): CharSequence {
        val output = SpannableStringBuilder()
        var previousKind: InlineEditKind? = null
        InlineEditDiff.segments(original, replacement).forEach { segment ->
            if (segment.kind == InlineEditKind.INSERTED && previousKind == InlineEditKind.REMOVED) {
                output.append(" → ")
            }
            val start = output.length
            output.append(segment.text)
            val end = output.length
            when (segment.kind) {
                InlineEditKind.UNCHANGED -> Unit
                InlineEditKind.REMOVED -> {
                    output.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(
                        ForegroundColorSpan(COLOR_REMOVED),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                InlineEditKind.INSERTED -> {
                    output.setSpan(
                        BackgroundColorSpan(COLOR_INSERTED_BACKGROUND),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    output.setSpan(
                        ForegroundColorSpan(COLOR_INSERTED),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    output.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            previousKind = segment.kind
        }
        return output
    }

    private fun returnReplacement() {
        val replacement = modelReplacement ?: return
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, replacement))
        finish()
    }

    private fun caption(textRes: Int): TextView = TextView(this).apply {
        text = getString(textRes)
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(COLOR_TEXT_MUTED)
    }

    private fun actionButton(textRes: Int, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = getString(textRes)
        contentDescription = text
        isAllCaps = false
        textSize = 16f
        minHeight = dp(52)
        minimumHeight = dp(52)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(COLOR_TEXT)
        background = rounded(if (primary) COLOR_ACCENT_DARK else COLOR_SURFACE, 12f, if (primary) COLOR_ACCENT else COLOR_BORDER)
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWidth(bottomMarginDp: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(bottomMarginDp) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class ToneAdapter : ArrayAdapter<String>(
        this,
        android.R.layout.simple_spinner_dropdown_item,
        WritingTone.entries.map { it.label },
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getView(position, convertView, parent) as TextView)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getDropDownView(position, convertView, parent) as TextView)

        private fun style(view: TextView): TextView = view.apply {
            setTextColor(COLOR_TEXT)
            setBackgroundColor(COLOR_SURFACE)
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
    }

    private companion object {
        const val EXTRA_OPEN_MODEL_SETUP = "com.remote.Manuscript.OPEN_MODEL_SETUP"
        const val PREFERENCES_NAME = "manuscript_preferences_v1"
        const val TONE_KEY = "writing_tone"
        const val MIN_TOUCH_TARGET_DP = 48
        val COLOR_BACKGROUND = Color.rgb(10, 13, 18)
        val COLOR_SURFACE = Color.rgb(26, 33, 43)
        val COLOR_CARD = Color.rgb(20, 26, 35)
        val COLOR_BORDER = Color.rgb(59, 69, 84)
        val COLOR_ACCENT = Color.rgb(32, 227, 138)
        val COLOR_ACCENT_DARK = Color.rgb(14, 94, 62)
        val COLOR_TEXT = Color.rgb(247, 249, 252)
        val COLOR_TEXT_MUTED = Color.rgb(179, 189, 204)
        val COLOR_REMOVED = Color.rgb(255, 151, 139)
        val COLOR_INSERTED = Color.rgb(13, 28, 22)
        val COLOR_INSERTED_BACKGROUND = Color.rgb(119, 226, 172)
    }
}
