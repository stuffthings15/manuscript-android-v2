package com.echolink.mobile.fixture

object FixtureActionContract {
    const val TRANSIENT_CLEAR_DESCRIPTION = "Clear generated transient field"
    const val ACTION_COMPLETION_GATE_MS = 1_000L

    fun isAvailable(mode: String): Boolean = mode in setOf(
        "transient",
        "detached",
        "target-loss",
        "package-switch",
    )

    fun description(mode: String): String = when (mode) {
        "transient" -> TRANSIENT_CLEAR_DESCRIPTION
        "detached" -> "Detach generated field"
        "target-loss" -> "Move focus from generated field"
        else -> "Switch from generated field"
    }

    fun clearsEditor(mode: String): Boolean = mode == "transient"

    fun isFocusedEmptySnapshot(
        focused: Boolean,
        text: String?,
        hintText: String?,
        isShowingHintText: Boolean,
    ): Boolean = focused && (
        text.isNullOrEmpty() ||
            (isShowingHintText && hintText != null && text == hintText)
        )
}
