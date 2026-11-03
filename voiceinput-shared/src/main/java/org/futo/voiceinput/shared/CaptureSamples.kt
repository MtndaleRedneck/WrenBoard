package org.futo.voiceinput.shared

import kotlin.math.sqrt

/** RMS of exactly the valid prefix, never the stale buffer tail. No speech threshold. */
internal fun validSampleRms(samples: ShortArray, nRead: Int): Float {
    require(nRead in 0..samples.size)
    if (nRead == 0) return 0f
    var sum = 0.0
    for (i in 0 until nRead) {
        val value = samples[i].toDouble() / Short.MAX_VALUE
        sum += value * value
    }
    return sqrt(sum / nRead).toFloat()
}

/** Exact zero only: retain every nonzero sample, however short or quiet. */
internal fun isExactEmptyCapture(samples: FloatArray): Boolean = samples.all { it == 0f }
