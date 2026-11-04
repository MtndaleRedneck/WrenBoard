package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WrenClipPolicyTest {
    @Test fun keepsOrdinaryCopies() {
        assertTrue(WrenClipPolicy.shouldKeep(false, false, false, null))
        assertTrue(WrenClipPolicy.shouldKeep(false, false, false, "Copied text"))
        assertTrue(WrenClipPolicy.shouldKeep(false, false, false, "Shipping info"))
    }
    @Test fun skipsPasswordFieldCopiesEvenWhenSensitiveSavingIsOn() {
        assertFalse(WrenClipPolicy.shouldKeep(false, true, true, null))
    }
    @Test fun skipsSensitiveUnlessOptedIn() {
        assertFalse(WrenClipPolicy.shouldKeep(true, false, false, null))
        assertTrue(WrenClipPolicy.shouldKeep(true, true, false, null))
    }
    @Test fun skipsPasswordAndCodeLabels() {
        assertFalse(WrenClipPolicy.shouldKeep(false, false, false, "Password"))
        assertFalse(WrenClipPolicy.shouldKeep(false, false, false, "One-time code"))
        assertFalse(WrenClipPolicy.shouldKeep(false, false, false, "OTP"))
    }
}
