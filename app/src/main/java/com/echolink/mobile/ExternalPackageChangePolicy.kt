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
}
