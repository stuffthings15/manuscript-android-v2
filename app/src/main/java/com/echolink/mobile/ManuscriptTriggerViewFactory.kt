package com.echolink.mobile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageButton

object ManuscriptTriggerViewFactory {
    fun create(
        context: Context,
        contentDescription: String,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        val padding = context.dp(ManuscriptTriggerPresentation.PADDING_DP)
        setImageResource(R.drawable.ic_manuscript_assist)
        setPadding(padding, padding, padding, padding)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(24).toFloat()
            setColor(ManuscriptTriggerPresentation.FRAME_COLOR)
            setStroke(
                context.dp(1),
                ManuscriptTriggerPresentation.BORDER_COLOR,
            )
        }
        this.contentDescription = contentDescription
        minimumWidth = context.dp(ManuscriptTriggerPresentation.TOUCH_TARGET_DP)
        minimumHeight = context.dp(ManuscriptTriggerPresentation.TOUCH_TARGET_DP)
        visibility = View.GONE
        setOnClickListener { onClick() }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                TriggerPulseController.stop(view)
            }
        })
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
