package org.futo.inputmethod.latin.uix.actions.clipboard

/** One delivered pointer sample (a MotionEvent historical batch entry or the event's own point). */
internal data class DragSample(val x: Float, val y: Float, val uptime: Long)

/**
 * Accumulates drag travel across pointer events, counting the batched HISTORICAL samples each
 * event carries. Under emulator/system load, move samples are coalesced into fewer delivered
 * events; accumulating only event-level deltas under-counts a fast swipe's travel (cl04 on
 * run 41569556946: UP processed at -154px of a 600px swipe, delivered over ~2.3s).
 *
 * Each sample is counted at most once and in time order: a sample is only applied when its
 * uptime is newer than the newest already-applied sample, so replays/overlaps between a previous
 * event's current position and the next event's history cannot double count. With no historical
 * samples this reduces to per-event delta accumulation, identical to positionChange() summing.
 */
internal class DragAccum {
    var dx = 0f; private set
    var dy = 0f; private set
    private var lastX = 0f
    private var lastY = 0f
    private var lastUptime = Long.MIN_VALUE

    fun reset(x: Float, y: Float, uptime: Long) {
        dx = 0f; dy = 0f
        lastX = x; lastY = y; lastUptime = uptime
    }

    fun advance(samples: List<DragSample>) {
        for (s in samples) {
            if (s.uptime > lastUptime) {
                dx += s.x - lastX
                dy += s.y - lastY
                lastX = s.x; lastY = s.y; lastUptime = s.uptime
            }
        }
    }
}
