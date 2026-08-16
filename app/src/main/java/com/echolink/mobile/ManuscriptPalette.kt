package com.echolink.mobile

/** Locked warm palette copied from the canonical Manuscript artwork. */
object ManuscriptPalette {
    const val BACKGROUND = 0xff17120d.toInt()
    const val SURFACE = 0xff211a12.toInt()
    const val SURFACE_RAISED = 0xff2e2418.toInt()
    const val SURFACE_FOCUSED = 0xff43321c.toInt()
    const val BORDER = 0xffd6a85f.toInt()
    const val ACCENT = 0xffe2b86f.toInt()
    const val ACCENT_DARK = 0xff8b5b2b.toInt()
    const val TEXT = 0xfff7e7c6.toInt()
    const val MUTED_TEXT = 0xfff2d6a2.toInt()
    const val ERROR = 0xffff8378.toInt()
    const val REMOVED = 0xffff978b.toInt()
    const val INSERTED = 0xff0d1c16.toInt()
    const val INSERTED_BACKGROUND = 0xff77e2ac.toInt()

    // The GUI build manual names ten semantic roles. The thirteen constants
    // above are locked to the artwork and already cover Primary, Surface,
    // Background, Outline and Error. These five are the rest, drawn from the
    // same warm family so nothing here is a new colour decision.
    //
    // The manual's rule that colour is never the only signal still holds: each
    // of these is paired with a label or icon at every call site.

    /** Something needs attention but nothing is broken. Warm amber, not red. */
    const val WARNING = 0xffe0a23c.toInt()

    /** A correction landed. The same green the inserted-text highlight uses. */
    const val SUCCESS = INSERTED_BACKGROUND

    /** Neutral explanation, quieter than the accent so it cannot read as a control. */
    const val INFORMATION = 0xffc9b48c.toInt()

    /** A control that is present but cannot be used. Must fail contrast on purpose. */
    const val DISABLED = 0xff6b5a41.toInt()

    /** Dimming behind a sheet or dialog. Black at 60 percent. */
    const val SCRIM = 0x99000000.toInt()
}
