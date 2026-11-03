package org.futo.voiceinput.shared

import org.junit.Assert.*
import org.junit.Test

class CaptureSamplesTest {
    @Test fun exactZeroOnly() {
        assertTrue(isExactEmptyCapture(floatArrayOf()))
        assertTrue(isExactEmptyCapture(floatArrayOf(0f, -0f, 0f)))
        assertFalse(isExactEmptyCapture(floatArrayOf(1f / Short.MAX_VALUE)))
        assertFalse(isExactEmptyCapture(floatArrayOf(-1f / Short.MAX_VALUE)))
        assertFalse(isExactEmptyCapture(floatArrayOf(Float.MIN_VALUE)))
    }
    @Test fun rmsIgnoresStaleTail() {
        assertEquals(0f, validSampleRms(shortArrayOf(0, 0, Short.MAX_VALUE), 2), 0f)
        assertEquals(1f, validSampleRms(shortArrayOf(Short.MAX_VALUE, 0), 1), 0f)
        assertEquals(0f, validSampleRms(shortArrayOf(Short.MAX_VALUE), 0), 0f)
    }
}
