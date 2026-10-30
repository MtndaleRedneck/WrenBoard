package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** WrenBoard 1.2 fix1b: a cancelled drag never deletes a clip; a finished swipe past the threshold does. */
class WrenSwipeJudgeTest {
    @Test fun cancelledDragFarPastThresholdSnapsBack() {
        assertEquals(WrenSwipeJudge.Outcome.SnapBack, WrenSwipeJudge.judge(400f, completed = false))
        assertEquals(WrenSwipeJudge.Outcome.SnapBack, WrenSwipeJudge.judge(-900f, completed = false))
        assertEquals(WrenSwipeJudge.Outcome.SnapBack, WrenSwipeJudge.judge(181f, completed = false))
    }

    @Test fun completedSwipePastThresholdDeletesEitherDirection() {
        assertEquals(WrenSwipeJudge.Outcome.Delete, WrenSwipeJudge.judge(181f, completed = true))
        assertEquals(WrenSwipeJudge.Outcome.Delete, WrenSwipeJudge.judge(-600f, completed = true))
    }

    @Test fun completedShortSwipeSnapsBack() {
        assertEquals(WrenSwipeJudge.Outcome.SnapBack, WrenSwipeJudge.judge(180f, completed = true))
        assertEquals(WrenSwipeJudge.Outcome.SnapBack, WrenSwipeJudge.judge(-50f, completed = true))
        assertEquals(WrenSwipeJudge.Outcome.SnapBack, WrenSwipeJudge.judge(0f, completed = true))
    }
}
