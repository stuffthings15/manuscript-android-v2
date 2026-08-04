package com.echolink.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

data class BuildIdentity(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val gitCommit: String,
    val contractVersion: Int,
    val signerSha256: String,
    val localAiReady: Boolean,
    val accessibilityEnabled: Boolean,
) {
    fun summary(): String = buildString {
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Package: $packageName")
        appendLine("Build: ${gitCommit.take(12)}")
        appendLine("Contract: $contractVersion")
        appendLine("Signer: ${signerSha256.takeLast(6)}")
        appendLine("Local AI: ${if (localAiReady) "Ready" else "Model Required"}")
        append("Across-app writing: ${if (accessibilityEnabled) "On" else "Off"}")
    }

    companion object {
        @Suppress("DEPRECATION")
        fun current(
            context: Context,
            localAiReady: Boolean,
            accessibilityEnabled: Boolean,
        ): BuildIdentity {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                )
            }
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                packageInfo.signatures?.firstOrNull()
            }
            val signer = signature?.toByteArray()?.let { bytes ->
                MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { byte -> "%02X".format(byte) }
            }.orEmpty()
            return BuildIdentity(
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                },
                packageName = context.packageName,
                gitCommit = BuildConfig.MANUSCRIPT_GIT_COMMIT,
                contractVersion = BuildConfig.MANUSCRIPT_CONTRACT_VERSION,
                signerSha256 = signer,
                localAiReady = localAiReady,
                accessibilityEnabled = accessibilityEnabled,
            )
        }
    }
}
