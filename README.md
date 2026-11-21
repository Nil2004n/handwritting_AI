# NotebookOCR (Java desktop, Phase 1 — working OCR)

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

## Supported input
JPG/JPEG and PNG. EXIF orientation is applied (phone photos come out
upright); coordinates refer to the oriented image. Single-column reading
order. No table/formula/numericals handling.

## Current limitations
- Rendered print English transcribes near-perfectly; the bundled synthetic
  scribbles are not real text (regions found, text meaningless by design).
  Real handwriting accuracy is unverified — no handwriting samples exist.
- CPU only, sequential single-image inference, no batching optimization.
- Debug overlays/crops are opt-in and never written into the source tree.

## Docs
`MODEL_MANIFEST.md` (sources, files, checksums, verified processing) ·
`HANDOFF.md` (this phase: work, results, blockers, next task). The previous
React Native/Android implementation is preserved under the
`archive/android-rn-phase0` tag.
