package com.echolink.mobile

/**
 * The five states the home screen's Assistant Status card can show, from
 * section 6 of the GUI build manual.
 *
 * Pure data on purpose, so the mapping can be tested without a device. The
 * view that draws it is [ManuscriptComponents.statusPill].
 *
 * Two rules from the manual are carried here rather than left to each caller:
 *
 * - Colour is never the only signal. Every state has a label, and the pill
 *   draws that label beside the colour.
 * - A failure links to its repair. [repairActionRes] is non-null exactly when
 *   the user can do something about the state, so a screen cannot show a
 *   broken status with no way out.
 */
enum class AssistantStatus(
    val labelRes: Int,
    val detailRes: Int,
    val color: Int,
    val repairActionRes: Int?,
) {
    /** Working, across apps included. */
    ACTIVE(
        labelRes = R.string.assistant_status_active,
        detailRes = R.string.assistant_status_active_detail,
        color = ManuscriptPalette.SUCCESS,
        repairActionRes = null,
    ),

    /** The user turned it off. Their choice, so this is not an error colour. */
    PAUSED(
        labelRes = R.string.assistant_status_paused,
        detailRes = R.string.assistant_status_paused_detail,
        color = ManuscriptPalette.INFORMATION,
        repairActionRes = R.string.assistant_status_resume,
    ),

    /** Across-apps was enabled once and the permission is no longer granted. */
    PERMISSION_MISSING(
        labelRes = R.string.assistant_status_permission_missing,
        detailRes = R.string.assistant_status_permission_missing_detail,
        color = ManuscriptPalette.WARNING,
        repairActionRes = R.string.assistant_status_restore_access,
    ),

    /** The correction service is not answering. */
    SERVICE_ERROR(
        labelRes = R.string.assistant_status_service_error,
        detailRes = R.string.assistant_status_service_error_detail,
        color = ManuscriptPalette.ERROR,
        repairActionRes = R.string.assistant_status_reconnect,
    ),

    /**
     * Working inside Manuscript's own editor only.
     *
     * Not a fault. The manual asks for an editor-only fallback so a user who
     * never grants the across-apps permission still has a complete product.
     */
    EDITOR_ONLY(
        labelRes = R.string.assistant_status_editor_only,
        detailRes = R.string.assistant_status_editor_only_detail,
        color = ManuscriptPalette.ACCENT,
        repairActionRes = R.string.assistant_status_enable_across_apps,
    );

    /** True when the state is one the user should act on. */
    val needsAttention: Boolean
        get() = this == PERMISSION_MISSING || this == SERVICE_ERROR
}
