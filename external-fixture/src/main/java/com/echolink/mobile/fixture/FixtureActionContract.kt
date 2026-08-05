package com.echolink.mobile.fixture

object FixtureActionContract {
    const val TRANSIENT_CLEAR_DESCRIPTION = "Clear generated transient field"

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
}
