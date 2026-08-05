package com.echolink.mobile

object ExternalPackageChangePolicy {
    fun confirmed(
        retainedPackage: String,
        verifiedApplicationWindowPackage: String?,
    ): Boolean {
        if (retainedPackage.isBlank()) return false
        val confirmedPackage = verifiedApplicationWindowPackage?.takeIf(String::isNotBlank)
            ?: return false
        return confirmedPackage != retainedPackage
    }

    fun confirmedEarlyApplicationWindowStateChange(
        retainedPackage: String,
        eventPackage: String,
        eventWindowId: Int,
        matchingApplicationWindowCount: Int,
        isWindowStateChanged: Boolean,
    ): Boolean =
        isWindowStateChanged &&
            retainedPackage.isNotBlank() &&
            eventPackage.isNotBlank() &&
            eventPackage != retainedPackage &&
            eventWindowId >= 0 &&
            matchingApplicationWindowCount == 1

    fun provisionalTransitionHint(
        retainedPackage: String,
        eventPackage: String,
        eventWindowId: Int,
        isApplicationLifecycleEvent: Boolean,
        isInputMethodWindow: Boolean,
        matchingApplicationWindowCount: Int,
    ): Boolean =
        isApplicationLifecycleEvent &&
            !isInputMethodWindow &&
            retainedPackage.isNotBlank() &&
            eventPackage.isNotBlank() &&
            eventPackage != retainedPackage &&
            eventWindowId >= 0 &&
            matchingApplicationWindowCount == 1

}
