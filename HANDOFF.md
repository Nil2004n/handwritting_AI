# Handoff (Java migration + OCR setup, 2026-11-20)

## Completed
- Archived the React Native/Android tree as tag `archive/android-rn-phase0`
  (verified resolvable: `7ce7c84`); removed it from the active worktree with
  `git rm` (no history rewrite, no push). `node_modules` deleted from disk
  (was untracked/ignored). `.git` and `LICENSE` kept.
- Preserved: `samples/images/*.png` (5 synthetic pages) and
  `samples/transcriptions/*.txt` (exact reference texts incl. the blank
  cells, ambiguity flag, and LaTeX seeds).
- New Gradle project (root): wrapper 8.14.3, Java 21 toolchain (system
  default still Java 25), packages `ui` / `ocr` / `model`, non-modular.
  Pinned deps: ONNX Runtime 1.22.0, JavaFX 21.0.7 (linux classifiers),
  JUnit Jupiter 5.12.2 (+ platform-launcher 1.12.2 to match Gradle 8.14.3).
- Models: official `PaddlePaddle/PP-OCRv6_small_{det,rec}_onnx` (Apache-2.0;
  upstream license in `third-party/PaddleOCR-LICENSE`). PP-OCRv6_small was
  verifiable, so no fallback model was needed. Files: `det/rec.onnx` +
  pairing `det/rec.yml` (rec yml embeds the 18,708-entry character dict).
  Pinned commit SHAs + `main`-ref fallback (host intermittently answers
  SHA resolves with "Invalid rev id"); bytes pinned by committed
  `models/checksums.sha256`. Reproducible via `tools/download-models.sh`
  (rerun-safe, temp-file downloads, retry + pacing).
- `./gradlew clean build` PASS; `./gradlew verifyOcrSetup` PASS (exit 0,
  exact I/O in MODEL_MANIFEST.md); missing-assets run FAILS cleanly
  (exit 1, actionable message). No Python/server/network in the check.
- JavaFX shell compiles and starts (process alive, toolkit init, no crash);
  stage mapping unverified — see blockers.

## Files added/changed (staged for commit)
- New: `settings.gradle`, `build.gradle`, `gradle/wrapper/*`, `gradlew*`,
  `.gitignore`, `src/main/...` (5 classes), `src/test/...` (1 test class),
  `tools/download-models.sh`, `models/checksums.sha256`,
  `third-party/PaddleOCR-LICENSE`, `samples/`, `README.md`,
  `MODEL_MANIFEST.md`, this HANDOFF.
- Removed from worktree: all RN/Android sources, configs, docs, ios/,
  android/ (recoverable via the archive tag).
- Never committed: `models/*.onnx`, `models/*.yml` (git-ignored binaries),
  `build/`, `.gradle/`, `~/.gradle` caches.

## Commands actually run
- `git tag -a archive/android-rn-phase0`; `git rm -r <old tree>`; `rm -rf node_modules android`
- `gradle wrapper --gradle-version 8.14.3` (from official 8.14.3 dist)
- `sh tools/download-models.sh` → 4 files, `sha256sum -c` OK
- `./gradlew clean build` → BUILD SUCCESSFUL
- `./gradlew verifyOcrSetup` → exit 0 (I/O metadata printed)
- `java ... OcrSetupCheck` from bare dir → exit 1 with clear error
- `./gradlew run` → starts, no crash (window mapping pending, see below)

## Tests
- 3 JUnit tests pass: missing-assets error, checksum-mismatch error,
  dictionary counting on a synthetic snippet.
- Not run: on-device-style UI interaction (no mappable display session).

## Blockers / limits
1. **UI window unverified.** A display exists (`:0.0`) but the session is
   screensaver-locked; the app process starts and the FX toolkit inits, but
   the stage stayed unmapped for 4+ minutes with no errors. Interactive
   verification is PENDING — retry on an unlocked desktop. (Also fixed
   along the way: JavaFX classpath launcher needs a separate `Main` class.)
2. **CDN flakiness.** Hugging Face intermittently 404s pinned-SHA resolves
   ("Invalid rev id") while `main` works; the script falls back and always
   enforces SHA-256 bytes. If downloads fail, wait and rerun the script.
3. OpenCV deliberately absent (no preprocessing in Phase 0).

## Next exact task
Next phase: real image → text OCR — implement DB postprocess + CTC decode
in `ocr/`, run the two sessions over `samples/images/*.png`, compare with
`samples/transcriptions/*.txt`. Do NOT claim OCR works until transcribed
output matches references.
