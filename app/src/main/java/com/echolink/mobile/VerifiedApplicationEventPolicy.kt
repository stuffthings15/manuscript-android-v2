package com.echolink.mobile

data class VerifiedApplicationEventMetadata(
    val eventPackage: String,
    val eventWindowId: Int,
    val rootPackage: String,
    val rootWindowId: Int,
    val applicationWindow: Boolean,
    val matchingApplicationWindowCount: Int = 1,
)

data class VerifiedApplicationFocusMetadata(
    val packageName: String,
    val windowId: Int,
    val focused: Boolean,
    val editable: Boolean,
    val password: Boolean,
)

object VerifiedApplicationEventPolicy {
    fun acceptsEvent(metadata: VerifiedApplicationEventMetadata): Boolean =
        metadata.applicationWindow &&
            metadata.matchingApplicationWindowCount == 1 &&
            metadata.eventPackage.isNotBlank() &&
            metadata.eventPackage == metadata.rootPackage &&
            metadata.eventWindowId >= 0 &&
            metadata.rootWindowId >= 0 &&
            metadata.eventWindowId == metadata.rootWindowId

    fun acceptsFocus(
        event: VerifiedApplicationEventMetadata,
        focus: VerifiedApplicationFocusMetadata,
    ): Boolean =
        acceptsEvent(event) &&
            focus.focused &&
            focus.editable &&
            focus.packageName == event.eventPackage &&
            focus.windowId >= 0 &&
            focus.windowId == event.eventWindowId
}
