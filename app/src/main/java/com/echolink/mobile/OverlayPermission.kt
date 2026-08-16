package com.echolink.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Whether Manuscript may draw the floating assistant over other applications.
 *
 * This is SYSTEM_ALERT_WINDOW, and until the GUI build manual was adopted this
 * product deliberately did not have it. That reversal is recorded in the commit
 * that added it and in the README, rather than left for someone to discover in
 * the manifest.
 *
 * The permission is never assumed. Android grants it only through a settings
 * screen the user has to visit, it can be revoked at any time, and a floating
 * window added without it throws. Every caller asks first, and the across-app
 * panel keeps working through the accessibility overlay when the answer is no,
 * so refusing this leaves a complete product rather than a broken one.
 */
object OverlayPermission {
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * The system screen where the user grants it.
     *
     * Scoped to this package so the user lands on Manuscript's own switch
     * rather than a list of every application on the phone.
     */
    fun settingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
