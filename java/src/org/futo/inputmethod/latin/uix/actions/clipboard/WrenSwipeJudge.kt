package org.futo.inputmethod.latin.uix.actions.clipboard

import kotlin.math.abs

/**
 * WrenBoard 1.2: decides what a sideways drag on a clipboard card does when it stops.
 * Only a drag the finger actually finished (lifted) past the threshold deletes the clip (with Undo).
 * A drag the system interrupted or cancelled (another touch, the panel closing, the gesture being taken over)
 * never deletes, however far it had moved: the card snaps back. Pure Kotlin, no Android, so it is unit tested.
 */
object WrenSwipeJudge {
    const val DELETE_THRESHOLD_PX = 180f

    enum class Outcome { Delete, SnapBack }

    fun judge(dragPx: Float, completed: Boolean): Outcome =
        if (completed && abs(dragPx) > DELETE_THRESHOLD_PX) Outcome.Delete else Outcome.SnapBack
}
