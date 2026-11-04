package org.futo.inputmethod.latin.uix.actions.clipboard

/**
 * WrenBoard (owner-approved item 4): clipboard history is on by default, so the rule for what it
 * must never keep lives here where it can be tested: anything the source app marks sensitive
 * (unless the user opted in), anything copied while a password field is focused, and clips whose
 * label says they are a password or one-time code (password managers label them that way).
 */
object WrenClipPolicy {
    private val secretLabel = Regex("(?i)\\b(password|passcode|passwd|pin|otp|one[- ]time|2fa|verification code|secret)\\b")

    @JvmStatic
    fun shouldKeep(
        isSensitive: Boolean,
        canSaveSensitive: Boolean,
        copiedInPasswordField: Boolean,
        clipLabel: String?
    ): Boolean {
        if (copiedInPasswordField) return false
        if (isSensitive && !canSaveSensitive) return false
        if (!clipLabel.isNullOrBlank() && secretLabel.containsMatchIn(clipLabel)) return false
        return true
    }
}
