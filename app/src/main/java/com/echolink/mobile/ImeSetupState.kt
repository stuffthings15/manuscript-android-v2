package com.echolink.mobile

enum class ImeSetupState {
    DISABLED,
    ENABLED,
    SELECTED;

    companion object {
        fun from(enabled: Boolean, selected: Boolean): ImeSetupState = when {
            enabled && selected -> SELECTED
            enabled -> ENABLED
            else -> DISABLED
        }
    }
}
