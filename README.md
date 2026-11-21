# notebook-ai (Java desktop, Phase 1 — working OCR)

Offline handwritten-text OCR: open an image → preview → extract real text
→ edit → copy. Pure Java 21, JavaFX UI, ONNX Runtime CPU, bundled-local
PP-OCRv6_small models. No Python, no server, no network at runtime.

## What works
- `./gradlew recognizeImage -Pimage=/path/to.jpg` — CLI: regions with
  boxes + detector/recognition scores, combined text, stage timings.
  Optional `-PdebugDir=/tmp/ocr-debug` writes overlay + crop images there.
- `./gradlew run` — JavaFX window: open image (JPG/PNG), preview, extract
  in background, editable transcript + read-only raw output, copy button,
  re-extract confirmation when edits exist.
- `./gradlew verifyOcrSetup` — Phase 0 gate: sessions load, I/O printed.
- `./gradlew clean build` — compiles + runs the JUnit suite.

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
