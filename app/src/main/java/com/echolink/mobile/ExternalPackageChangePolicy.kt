package com.echolink.mobile

object ExternalPackageChangePolicy {
    fun confirmed(
        retainedPackage: String,
        activeWindowPackage: String,
        eventPackage: String,
        verifiedApplicationWindowTransition: Boolean,
    ): Boolean {
        if (retainedPackage.isBlank() || !verifiedApplicationWindowTransition) return false
        val confirmedPackage = activeWindowPackage.ifBlank { eventPackage }
        return confirmedPackage.isNotBlank() && confirmedPackage != retainedPackage
    }
}
