# notebook-ai (Java desktop, Phase 2 — image review + OCR)

Offline handwritten-text OCR: open an image → prepare (rotate/straighten/crop)
→ extract real text → review regions → edit → copy. Pure Java 21, JavaFX UI,
ONNX Runtime CPU, bundled-local PP-OCRv6_small models. No Python, no server,
no network at runtime.

## What works
- `./gradlew recognizeImage -Pimage=/path/to.jpg` — CLI: regions with
  boxes + detector/recognition scores, combined text, stage timings.
  Optional `-PdebugDir=/tmp/ocr-debug` writes overlay + crop images there.
- `./gradlew run` — JavaFX window: image prep, page extract, selection
  OCR, single-line OCR, region review with overlay, verbatim transcript
  editing, copy, raw restore. JavaFX loads as named modules (no startup
  warning on the pinned JDK 21 runtime).
- `./gradlew verifyOcrSetup` — Phase 0 gate: sessions load, I/O printed.
- `./gradlew clean build` — compiles + runs the JUnit suite.

## Image preparation and coordinates
- Rotate left/right (90° steps), straighten slider (±10°) + Apply, reset
  to original; rectangular crop selection with apply/clear. The source file
  is never modified; an original + working image is kept (no edit history).
- Working-image pixels, origin top-left. Preview uses contain fit; mouse
  selection inverts it (letterbox excluded) and clips to bounds; tiny
  drags are treated as clicks, not regions.
- Changing the working image clears obsolete results (asks first if the
  transcript has unsaved edits).

## Region review
- Extract Page lists regions in reading order and draws their outlines on
  the preview. Clicking a list row (or a region on the image) shows its raw
  text, detector score (box quality) and recognition score (mean kept-char
  confidence — not a correctness probability), plus the exact recognizer crop.
- Recognize Selection runs detector + recognizer on the dragged crop;
  Recognize Line runs the recognizer directly on it (for single lines).
  Selection results appear in a separate area — never spliced into the
  transcript. Copy or insert-at-caret are explicit actions.
- Transcript stays the primary editor; raw OCR output is immutable and can
  be restored with confirmation. Digits, signs, units, exponents and line
  breaks pass through verbatim — nothing is solved, rewritten or guessed.

## Setup
```sh
sh tools/download-models.sh   # models + sha256sum verification
./gradlew clean build
```

## Install globally (Makefile)
```sh
make build models             # as your normal user (compiles, fetches models)
make install                  # user-local if PREFIX is writable, else rerun:
sudo make install             # system-wide to /usr/local (copies files only;
                              # never compiles or downloads as root)
make install PREFIX=$HOME/.local   # user-local install, no root ($HOME/.local/bin must be on PATH)
make uninstall [PREFIX=...]  # remove installed files
make system-deps              # apt-install openjdk-21-jre (prompts for sudo)
make run | make clean | make help
```
The Makefile pins the Gradle daemon to JDK 21 when present, so a Java 25
system default (or a sudo environment without your Gradle config) cannot
break the build. Install layout: `<prefix>/lib/notebook-ai/{bin,lib,models}`,
launcher at `<prefix>/bin/notebook-ai`. After
`make install PREFIX=$HOME/.local` the `notebook-ai` command works from any
directory (models resolve from the install dir; `NOTEBOOK_AI_MODELS`
overrides).

## Supported input
JPG/JPEG and PNG. EXIF orientation is applied (phone photos come out
upright); coordinates refer to the oriented image. Single-column reading
order. No table/formula/numericals handling.

## Current limitations
- Rendered print English transcribes near-perfectly; the bundled synthetic
  scribbles are not real text (regions found, text meaningless by design).
- First real-handwriting measurement (2026-11-21, `samples/handwriting-test.jpg`,
  phone photo of notebook page): 2 of 3 lines recovered, line 1
  character-perfect; large cursive missed, one line split. See
  `samples/transcriptions/handwriting-test.txt`. This file is now the
  standard real-handwriting test sample — use it for future verification.
- CPU only, sequential single-image inference, no batching optimization.
- Debug overlays/crops are opt-in and never written into the source tree.

## Docs
`MODEL_MANIFEST.md` (sources, files, checksums, verified processing) ·
`HANDOFF.md` (this phase: work, results, blockers, next task). The previous
React Native/Android implementation is preserved under the
`archive/android-rn-phase0` tag.
