## WrenBoard 1.3

Install over 1.2 directly, no uninstall needed (same signing key, versionCode 4).

Fixes and polish across the keyboard, Settings and the update check.

**Typing**
- Backspace hold deletes word by word, stopping on word boundaries, with acceleration; undo-autocorrect on backspace is available as an option (default off)
- Long-press on period is on by default and its default set is `! , . ?` with `.` preselected
- Autocorrect verified against the behavior-comparison list (teh / brwon / i cases)

**Settings**
- Settings pages leave room at the bottom so the floating try-keyboard button never covers the last row
- Defaults text matches runtime behavior (backspace mode, undo-autocorrect)
- Paid-version notice moved from the Wren home page to the FUTO page

**Panels and look**
- Voice prompt pill stays clear of the mic-source toast
- Floating try-keyboard button restored; the try-typing bar stays removed

**Under the hood**
- Merge of upstream main: site, README and CI hardening (pinned actions, least-privilege permissions, env pass-through)
- ime-verify harness hardened: FAIL lines now fail the job

Testing limits: verification ran on Android emulators (1080x2400 and 1440x2560 profiles), not on a real phone.

APK sha256: 1f739368556caf8451c47e67b6b0b65cf45548e9bc19099bc9d6c4c13793f6de (129,312,668 bytes)
