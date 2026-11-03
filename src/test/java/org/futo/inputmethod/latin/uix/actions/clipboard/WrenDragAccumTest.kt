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
}
