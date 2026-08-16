package com.echolink.mobile

/**
 * The four ways the writer can send the floating assistant away, from section
 * 16 of the GUI build manual, and the rule for whether it may appear.
 *
 * They differ in how long they last and how wide they reach, and the manual is
 * specific about it:
 *
 * - [HIDE_UNTIL_APP_CLOSES] is this application, this visit.
 * - [HIDE_FOR_ONE_HOUR] is everywhere, and it ends by itself.
 * - [BLOCK_THIS_APP] is this application, until the writer says otherwise.
 * - [TURN_OFF_EVERYWHERE] is the whole feature, and only the writer turns it
 *   back on.
 *
 * Kept as data and arithmetic so the awkward part, which is time and scope
 * rather than drawing, can be tested without a device or a clock.
 */
enum class AssistantHideChoice {
    HIDE_UNTIL_APP_CLOSES,
    HIDE_FOR_ONE_HOUR,
    BLOCK_THIS_APP,
    TURN_OFF_EVERYWHERE,
}

/**
 * Everything that can stop the assistant appearing, in one place.
 *
 * [hiddenUntilElapsedMs] and [now] are elapsed-realtime milliseconds. Wall
 * clock is the wrong measure for "one hour" because changing the time zone or
 * the clock would end the hour early or strand it, and elapsed time cannot be
 * moved by either.
 */
data class AssistantSuppressionState(
    val turnedOffEverywhere: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val hiddenUntilElapsedMs: Long = 0L,
    val hiddenForAppVisit: String? = null,
) {
    /** Why the assistant is not showing, or null when it may show. */
    fun suppressionFor(packageName: String, now: Long): AssistantSuppressionReason? = when {
        turnedOffEverywhere -> AssistantSuppressionReason.TURNED_OFF
        blockedPackages.contains(packageName) -> AssistantSuppressionReason.BLOCKED_APP
        now < hiddenUntilElapsedMs -> AssistantSuppressionReason.HIDDEN_FOR_NOW
        hiddenForAppVisit == packageName -> AssistantSuppressionReason.HIDDEN_THIS_VISIT
        else -> null
    }

    fun mayShow(packageName: String, now: Long): Boolean =
        suppressionFor(packageName, now) == null

    /** Applies a choice the writer made while looking at [packageName]. */
    fun after(
        choice: AssistantHideChoice,
        packageName: String,
        now: Long,
    ): AssistantSuppressionState = when (choice) {
        AssistantHideChoice.HIDE_UNTIL_APP_CLOSES ->
            copy(hiddenForAppVisit = packageName)

        AssistantHideChoice.HIDE_FOR_ONE_HOUR ->
            copy(hiddenUntilElapsedMs = now + ONE_HOUR_MS)

        AssistantHideChoice.BLOCK_THIS_APP ->
            copy(blockedPackages = blockedPackages + packageName)

        AssistantHideChoice.TURN_OFF_EVERYWHERE ->
            copy(turnedOffEverywhere = true)
    }

    /**
     * Clears the this-visit hide when the writer leaves that application.
     *
     * Without this the hide would outlive the visit it was scoped to and
     * behave like a block, which is a different choice the writer did not make.
     */
    fun onForegroundApplicationChanged(packageName: String): AssistantSuppressionState =
        if (hiddenForAppVisit != null && hiddenForAppVisit != packageName) {
            copy(hiddenForAppVisit = null)
        } else {
            this
        }

    /** Undoes a block. The screen that lists blocked applications uses this. */
    fun unblock(packageName: String): AssistantSuppressionState =
        copy(blockedPackages = blockedPackages - packageName)

    companion object {
        const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}

/** Why the assistant is not on screen. Named so a screen can say which. */
enum class AssistantSuppressionReason {
    TURNED_OFF,
    BLOCKED_APP,
    HIDDEN_FOR_NOW,
    HIDDEN_THIS_VISIT,
}
