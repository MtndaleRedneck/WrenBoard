# WrenBoard

## Typing

- Layout and look follow SwiftKey: a number row, letters, two symbols pages and a bottom row of arrow keys.
- Shift once for one capital. Double-tap shift for caps lock. With shift tapped on a finished word, the word cycles through Capitalized, UPPERCASE and lowercase.
- Hold backspace to delete whole words. It speeds up the longer you hold.
- Long-press 123 to open the clipboard.
- The suggestion strip learns words as you type them.
- Toolbar: voice, emoji, undo, redo, clipboard, theme and settings. You can reorder, hide and re-add actions in Settings.

## Dictation

Voice input uses an English speech model that is bundled with the app, so dictation does not need an internet connection. Choosing a different voice-input app asks for confirmation first.

## Themes

WrenBoard ships with a SwiftKey-inspired default theme. All of FUTO Keyboard's theme choices are still available.

## Your data

- Learned words, the personal dictionary and clipboard history are stored on the phone.
- Crash-report emailing from FUTO Keyboard has been removed from this build. The app makes a network request to check for updates; dictation does not need one.
- Settings can export a backup file with your settings, themes, personal dictionary and learned words. A restore option reads that file back and replaces what is on the phone, so export first if you are unsure.
- Backup and recovery on a new or replacement phone is still being worked on. Treat an exported file as a manual copy for now.

## Updates

The app checks this repository's releases for a newer version. When one is found, it opens the download page so you can install it.

## Building from source

Clone with submodules, then build with Gradle:

```
git clone --recursive https://github.com/MtndaleRedneck/WrenBoard.git
./gradlew assembleStableRelease
```

Keyboard layouts live in this repository under `java/assets/layouts/`.

## License

FUTO Source First License 1.1-kb. See [LICENSE.md](../LICENSE.md). Payment-related functionality and FUTO's notices are kept as the license requires.
