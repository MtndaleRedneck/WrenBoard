package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class WrenDragAccumTest {
    private fun samples(vararg s: Triple<Float, Float, Long>) =
        s.map { DragSample(it.first, it.second, it.third) }

    @Test
    fun noHistoricalSamples_matchesPlainDeltaSumming() {
        // Same stream as positionChange() summing: -600px over 6 events.
        val a = DragAccum()
        a.reset(810f, 1290f, 1000)
        var x = 810f
        for (i in 1..6) {
            val nx = x - 100f
            a.advance(samples(Triple(nx, 1290f, 1000L + i * 16)))
            x = nx
        }
        assertEquals(-600f, a.dx, 0.001f)
    }

    @Test
    fun coalescedBatch_countsFullTravel() {
        // A 600px swipe delivered as 2 events, the second carrying 4 historical samples.
        val a = DragAccum()
        a.reset(810f, 1290f, 1000)
        a.advance(samples(Triple(710f, 1290f, 1016))) // first event: -100
        a.advance(samples( // second event: 4 batched samples + its own point
            Triple(610f, 1290f, 1032), Triple(510f, 1290f, 1048),
            Triple(410f, 1290f, 1064), Triple(310f, 1290f, 1080),
            Triple(210f, 1290f, 1096)))
        assertEquals(-600f, a.dx, 0.001f)
    }

    @Test
    fun replayedOrOverlappingSample_neverDoubleCounts() {
        // Next event's history repeats the previous event's current point (common in batching).
        val a = DragAccum()
        a.reset(810f, 1290f, 1000)
        a.advance(samples(Triple(610f, 1290f, 1016), Triple(510f, 1290f, 1032)))
        a.advance(samples(Triple(510f, 1290f, 1032), Triple(410f, 1290f, 1048)))
        assertEquals(-400f, a.dx, 0.001f)
    }

    @Test
    fun outOfOrderOlderSample_isIgnored() {
        val a = DragAccum()
        a.reset(810f, 1290f, 1000)
        a.advance(samples(Triple(610f, 1290f, 1016), Triple(510f, 1290f, 1032)))
        a.advance(samples(Triple(700f, 1290f, 1008), Triple(410f, 1290f, 1048)))
        assertEquals(-400f, a.dx, 0.001f)
    }

    @Test
    fun verticalTravel_accumulatesAlongsideHorizontal() {
        val a = DragAccum()
        a.reset(810f, 1290f, 1000)
        a.advance(samples(Triple(760f, 1240f, 1016), Triple(710f, 1190f, 1032)))
        assertEquals(-100f, a.dx, 0.001f)
        assertEquals(-100f, a.dy, 0.001f)
    }

    @Test
    fun reset_startsANewGestureClean() {
        val a = DragAccum()
        a.reset(810f, 1290f, 1000)
        a.advance(samples(Triple(610f, 1290f, 1016)))
        a.reset(300f, 400f, 2000)
        assertEquals(0f, a.dx, 0.001f)
        a.advance(samples(Triple(250f, 400f, 2016)))
        assertEquals(-50f, a.dx, 0.001f)
    }

    @Test
    fun upEventTail_countsFinalTravelOnce() {
        // The cl04 root cause (run 41585370039 trace): a 600px swipe whose tail was delivered
        // ONLY in the UP event - last MOVE at x=44, UP at x=-336 with one batched sample.
        // Judging must see the full -600, and the overlapping replay must not double count.
        val a = DragAccum()
        a.reset(264f, 68f, 150445)
        a.advance(samples(Triple(252f, 68f, 150451), Triple(200f, 68f, 150477))) // -64
        a.advance(samples(Triple(165f, 68f, 150494)))                            // -99
        a.advance(samples(Triple(132f, 68f, 150511), Triple(44f, 68f, 150555)))  // -220
        // UP event: history replays x=44 (same uptime as the last applied sample) then jumps to -336
        a.advance(samples(Triple(44f, 68f, 150555), Triple(-336f, 68f, 150781)))
        assertEquals(-600f, a.dx, 0.001f) // full travel visible at judgment -> Delete, not SnapBack
        assertEquals(WrenSwipeJudge.Outcome.Delete, WrenSwipeJudge.judge(a.dx, completed = true))
    }
}