# NotebookOCR (Java desktop, Phase 0 — OCR setup)

Offline handwritten-text OCR groundwork as a plain Java 21 desktop app.
**Phase 0 loads and validates PaddleOCR PP-OCRv6_small ONNX models through
ONNX Runtime. No image-to-text extraction exists yet.**

## What works
- `./gradlew verifyOcrSetup` — validates model files + checksums, loads the
  detector and recognizer sessions (CPU), prints their I/O metadata, reads
  the recognizer dictionary, closes native resources. Exit 0 on success,
  exit 1 with a clear error otherwise.
- `./gradlew run` — JavaFX window named NotebookOCR with setup status,
  model identity, a "Verify OCR Setup" button (runs in background), and a
  results area. No extraction, camera, tables, or persistence.
- `./gradlew clean build` — compiles, runs JUnit tests.

## Prerequisites
- JDK 21 (project toolchain; system-wide default untouched).
- Network once for Gradle/Maven dependencies and `tools/download-models.sh`.

## Setup & run
```sh
sh tools/download-models.sh   # models + checksums.sha256 verification
./gradlew clean build          # compile + unit tests
./gradlew verifyOcrSetup       # load sessions, print I/O metadata
./gradlew run                  # JavaFX window (needs a display)
```
The `models/` binaries (`*.onnx`, pairing `*.yml`) are git-ignored and
reproduced by the download script; `models/checksums.sha256` is committed.

## Verification status
- `verifyOcrSetup`: PASS (exit 0) — see MODEL_MANIFEST.md for observed I/O.
- Missing/corrupt assets: clear error, exit 1 (unit-tested + manually run).
- UI: process starts, JavaFX toolkit initializes, no crash — but on this
  host the stage never mapped (locked screensaver session), so interactive
  window verification is PENDING. See HANDOFF.md.

## Docs
`MODEL_MANIFEST.md` (sources, files, checksums, license, verified-vs-untested) ·
`HANDOFF.md` (this phase: work, results, blockers, next task). The previous
React Native/Android implementation is preserved under the
`archive/android-rn-phase0` tag.

## Boundaries
No real OCR extraction, no OpenCV, no Python/Spring/server/cloud, no
camera, no Android work. Session loading is not proof of working OCR.
