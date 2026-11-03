package org.futo.inputmethod.latin.uix.actions

/** A capture's initial start and later route reports share a single ON cue. */
internal class CaptureStartSoundGate {
    private var played = false
    fun take(): Boolean {
        if (played) return false
        played = true
        return true
    }
    fun reset() { played = false }
}
