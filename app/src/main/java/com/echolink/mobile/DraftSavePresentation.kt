package com.echolink.mobile

/**
 * What the draft editor says about saving.
 *
 * The GUI build manual asks for the autosave state to be surfaced rather than
 * left silent. Saving was silent here: the writer had no way to tell a saved
 * draft from one that failed to save, and the failure case matters most,
 * because the store encrypts through the Android keystore and a failure there
 * means the words are not on disk.
 *
 * Pure data, like [AssistantStatus], so the mapping can be tested without a
 * device. [IDLE] carries no label because an editor nobody has typed in yet
 * has nothing to report.
 */
enum class DraftSaveStatus(
    val labelRes: Int?,
    val color: Int,
) {
    /** Nothing typed yet, or the draft was cleared. */
    IDLE(
        labelRes = null,
        color = ManuscriptPalette.MUTED_TEXT,
    ),

    /** Edited, with the debounced write still pending. */
    SAVING(
        labelRes = R.string.draft_status_saving,
        color = ManuscriptPalette.MUTED_TEXT,
    ),

    /** On disk, encrypted. */
    SAVED(
        labelRes = R.string.draft_status_saved,
        color = ManuscriptPalette.SUCCESS,
    ),

    /**
     * The write was refused.
     *
     * An error colour AND an error word: the manual's rule that colour is
     * never the only signal matters more here than anywhere else on this
     * screen, because the consequence is lost writing.
     */
    FAILED(
        labelRes = R.string.draft_status_failed,
        color = ManuscriptPalette.ERROR,
    );

    /** True when the writer needs to know now. */
    val needsAttention: Boolean
        get() = this == FAILED

    companion object {
        /**
         * What to show after an attempted write.
         *
         * [saved] is the store's own return value, which is false both when
         * encryption fails and when the preferences write is refused. Empty
         * text is not a failure: the store clears the draft and returns
         * whether the clear worked, so an empty editor reports [IDLE] rather
         * than accusing the device of losing writing that was never there.
         */
        fun afterWrite(textIsEmpty: Boolean, saved: Boolean): DraftSaveStatus = when {
            textIsEmpty && saved -> IDLE
            saved -> SAVED
            else -> FAILED
        }
    }
}
