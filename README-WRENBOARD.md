# WrenBoard (working build)

WrenBoard is a modified fork of FUTO Keyboard (https://github.com/futo-org/android-keyboard), pinned at upstream commit 31e720ddcc2df6259069b2ae7cf0f1defaf2cb74.

This copy has been modified. Modifications are marked in git history; original FUTO copyright and license notices are preserved throughout.

License: FUTO Source First License 1.1-kb (see LICENSE.md). Distribution is free of charge and non-commercial. Payment-related functionality and all licensor notices are retained as required. This fork is not affiliated with or endorsed by FUTO.

## Bundled voice model provenance (frozen at pin)

The bundled English dictation model is `tiny_en_acft_q8_0.bin.not.tflite`
(43,550,795 bytes, SHA-256 `4b5480aa1b14a7efc5b578ef176510970a898049671c3cd237285b3e3f6bfbfc`),
from submodule `voiceinput-shared/src/main/ml` pinned at `7692bc6096c51c44beba716a3c429ff09dbfffd9`
(https://gitlab.futo.org/keyboard/voice-input-models). It is byte-identical to FUTO's public
"English-39" download (https://keyboard.futo.org/voice-input-english-39.bin) and to the
converted whisper.cpp model linked from FUTO's whisper-acft README
(https://voiceinput.futo.org/VoiceInput/tiny_en_acft_q8_0.bin).

Provenance chain: OpenAI `whisper-tiny.en` (MIT license, https://github.com/openai/whisper)
fine-tuned by FUTO (`futo-org/acft-whisper-tiny.en`, Apache-2.0,
https://huggingface.co/futo-org/acft-whisper-tiny.en) and converted by FUTO with the ACFT
method (https://github.com/futo-org/whisper-acft). The conversion is supplied by FUTO and
bundled here unchanged; Apache-2.0 covers the converted Object form. No conversion was
performed by WrenBoard.
