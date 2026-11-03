package org.futo.inputmethod.latin.uix.actions

import org.junit.Assert.*
import org.junit.Test

class CaptureStartSoundGateTest {
    @Test fun firstStartAndRouteUpdatesPlayOnceThenStoppedRestartPlaysOnceMore() {
        val gate = CaptureStartSoundGate()
        var onCalls = 0
        repeat(3) { if (gate.take()) onCalls++ }
        assertEquals(1, onCalls)
        gate.reset()
        repeat(3) { if (gate.take()) onCalls++ }
        assertEquals(2, onCalls)
    }
}
