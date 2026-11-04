# WrenBoard: fork notes

WrenBoard is a modified fork of FUTO Keyboard (https://github.com/futo-org/android-keyboard), pinned at upstream commit 31e720ddcc2df6259069b2ae7cf0f1defaf2cb74.

This copy has been modified. Modifications are marked in git history; original FUTO copyright and license notices are preserved throughout.

License: FUTO Source First License 1.1-kb (see LICENSE.md). Distribution is free of charge and non-commercial. Payment-related functionality and all licensor notices are retained as required. This fork is not affiliated with or endorsed by FUTO.

## Repository layout

Single repository. Keyboard layouts are vendored at `java/assets/layouts/` (git subtree),
carrying the full upstream latinime-layouts history (https://github.com/futo-org/futo-keyboard-layouts)
through pin `fb4dad270790d980c32417b60359104bd0c32c1c` plus WrenBoard's SwiftKey symbols edits.
The layouts keep their upstream LICENSE in that directory.

To bring in future upstream layout updates:
  git subtree pull --prefix=java/assets/layouts https://github.com/futo-org/futo-keyboard-layouts.git main

Remaining external submodules (libs, themes, res-large, translations, voice model) still point
to FUTO's upstreams and are fetched at build time.

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

## Bundled swipe model provenance (frozen at pin)

Swipe typing uses FUTO Swipe weights from submodule `java/assets/futo-swipe`, pinned at
commit `93931877c0083d0de7f51fad82325dcbe10421bf` of https://huggingface.co/futo-org/futo-swipe
(fetched at build time with Git LFS; the demo animation and model card are not packaged).
License: FUTO Model Weights License 1.0 (`java/assets/futo-swipe/LICENSE.md`, SHA-256
`ef6b4f6437efa0a2929de351b10c16a8c870b32e1c23054ac76af61531f5db21`). As that license requires, the app
shows "Swipe typing powered by FUTO Swipe" with the full license text in Settings > Credits. The weights
are bundled unchanged; no derivative model is made.

| File | Bytes | SHA-256 |
|---|---:|---|
| honorable_sturgeon/model_fp32.pte (encoder, required) | 2,649,856 | `725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf` |
| magic_macaw/model_fp32.pte (English decoder) | 1,247,468 | `01eaf16ac4bc0f1ed0698c240807f0e95e6d427bcf6de04983ffc50736744d85` |
| hungry_jellyfish/context_lm.pte (English context LM) | 6,247,616 | `74d29f56a513c0c60abcd43df3b16a6b68925cdf4e97e51b094a5275ec2810d7` |
| hungry_jellyfish/vocab.txt | 284,965 | `a7db66376783b5a23ee3d4a2aaa8f2499fd9b35f975e92bcb248664c2cf6ebd1` |
| scoring.json | 1,085 | `8377ba6932882667da1e87717609f4ccd6bb78709b6b6ea898d3d4325c9c37f6` |

If any required file is missing, empty, or a Git LFS pointer, swipe quietly falls back and the keyboard
does not crash (`SwipeModelGate`, unit test `SwipeModelGateTest`).
