# WrenBoard

## Typing

- Layout and look follow SwiftKey: a number row, letters, two symbols pages and a bottom row of arrow keys.
- Shift: each tap moves lowercase → one capital → caps lock → lowercase, with no double-tap timing. One capital lasts for one letter; caps lock stays on and its key is marked differently. In the middle of a word, shift changes only the next letter (y, shift, ou types yOu); it never re-cases a finished word. Settings can switch to FUTO's style instead (double-tap or hold shift for caps lock). Shift on a selected word still changes its case.
- Hold backspace to delete whole words. It speeds up the longer you hold.
- Long-press 123 to open the clipboard. The 123 key shows a small clipboard mark, the way the comma shows the mic.
- Long-press the period for ! , . ? in a box above the key; slide to one and let go to type it.
- The suggestion strip learns words as you type them. In an empty field it shows starter words from your own typing (can be turned off).
- Toolbar: voice, emoji, undo, redo, clipboard, theme and settings. You can reorder, hide and re-add actions in Settings.

## Settings

Settings open straight away, even before WrenBoard is turned on. Until it is your active keyboard, a small banner at the top says so, with a button that opens Android's keyboard switch.

## Dictation

Voice input uses an English speech model that is bundled with the app, so dictation does not need an internet connection. Choosing a different voice-input app asks for confirmation first.

## Themes

WrenBoard ships with a SwiftKey-inspired default theme. All of FUTO Keyboard's theme choices are still available.

## Your data

- Learned words, the personal dictionary and clipboard history are stored on the phone.
- Crash-report emailing from FUTO Keyboard has been removed from this build. The app makes a network request to check for updates; dictation does not need one.
- Settings can export a backup file with your settings, themes, personal dictionary and learned words. A restore option reads that file back and replaces what is on the phone, so export first if you are unsure.
- Backup and recovery on a new or replacement phone is still being worked on. Treat an exported file as a manual copy for now. Automatic cloud backup is not part of the current build.

## Updates

The app checks this repository's releases for a newer version. When one is found, it opens the download page so you can install it. Until the first release is published, the check reports that it could not find one.

## Building from source

Clone with submodules, then build with Gradle:

```
git clone --recursive https://github.com/MtndaleRedneck/WrenBoard.git
./gradlew assembleStableRelease
```

Keyboard layouts live in this repository under `java/assets/layouts/`.

## License

FUTO Source First License 1.1-kb. See [LICENSE.md](../LICENSE.md). Payment-related functionality and FUTO's notices are kept as the license requires.
