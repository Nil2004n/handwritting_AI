# Handoff (Phase 1 — working image → text OCR, 2026-11-21)

## Implemented
- Full pipeline in `ocr/`: EXIF-aware decode → DetResizeForTest(736/min)
  + ImageNet normalize + CHW → detector inference → DB quad postprocess
  (border-follow contours, rotating-calipers minAreaRect, box scoring,
  miter+arc unclip, yml thresholds) → sorted_boxes reading order →
  perspective crops (bicubic/replicate + rot90 rule) → rec resize/normalize
  (3×48×W, zero-pad) → recognizer inference → CTC decode → drop_score 0.5.
- Decode table resolved from upstream evidence to
  `["blank"] + 18,708 dict + [" "]` (`use_space_char=True` default):
  class 18709 is the space character, observed firing at word gaps.
- `OcrService` (sessions opened once, reused across crops/images,
  AutoCloseable) returning regions (polygon/box/text/det+rec scores),
  combined text, and per-stage timings.
- CLI `./gradlew recognizeImage -Pimage=... [-PdebugDir=...]` (exit 0/1/2,
  headless-safe, no network) + optional debug overlay/crop writer
  (outside src tree, off by default).
- JavaFX UI wired to the same service: open/file-picker/preview, background
  extract, editable transcript + read-only raw, copy, re-extract confirm
  dialog, mid-job guards, error/empty states.
- 25 JUnit tests: CTC rules/mapping, dict parsing + full-table layout,
  minAreaRect/ordering/scaling/warp/unclip geometry, preprocessing math,
  assembly, error paths. All pass.

## Upstream sources (all v3.7.0 tag `b03f4642`)
- `ppocr/postprocess/db_postprocess.py`, `rec_postprocess.py`,
  `ppocr/data/imaug/{operators.py,rec_img_aug.py,label_ops.py}`,
  `tools/infer/{predict_system,predict_det,predict_rec,utility}.py`,
  `configs/rec/PP-OCRv6/PP-OCRv6_small_rec.yml`, `ppocr/utils/dict/ppocrv6_dict.txt`
- yml pairing wins over tools-CLI defaults (736/min vs 960/max,
  thresh 0.2/box 0.45/unclip 1.4/drop 0.5). Documented where they differ.

## Documented deviations (no silent approximations)
1. EXIF orientation applied (upstream `cv2.imread` ignores it) —
   deliberate; coordinates refer to the oriented image.
2. Sequential single-crop inference instead of batching — numerically
   identical on valid columns; padding only adds trailing blanks.
3. pyclipper integer quantization not replicated (would need the native
   lib); double-precision miter + 2°-sampled arcs, error < 0.008px,
   absorbed by final round+clip except at exact .5 boundaries.
4. Scanline even-odd fill vs OpenCV fillPoly: possible 1px edge differences.
5. Contour enumeration is top-left scan order; upstream order only matters
   past the 3000-contour cap (never hit here).
6. Out-of-range CTC class raises a clear `OcrException` (upstream would
   IndexError); unreachable with the corrected 18,710-entry table.

## Commands and tests run
- `./gradlew clean build` → SUCCESS (25 tests, 0 failures).
- `./gradlew verifyOcrSetup` → exit 0 (unchanged Phase 0 gate).
- `./gradlew recognizeImage` on all 5 samples + rendered English (exit 0).
- Missing-asset CLI + unsupported-extension paths → exit 1, clear errors.
- UI driven on a real display via screenshots: open → preview → extract →
  edit → copy all verified visually; confirm dialog + guards code-reviewed
  but not interactively triggered (focus gymnastics); clipboard verified
  with `xclip`.

## Sample results (verbatim)
- Rendered print English (900x500, DejaVu Sans): 4/4 regions, all lines
  character-perfect (`Hello world 123`, `Pack my box 456`,
  `Quick brown fox -78.5`, `Price: $12.50 total`), det 0.90–0.93,
  rec 0.95–1.00. Timings: total 769/685ms (decode ~80, detPre ~55,
  detInfer ~300, detPost ~65, crop ~32, rec ~175), sessions reused.
- prose.png (synthetic scribbles): 2 regions, pseudo-text ("SOOm", "w").
- numericals.png: 2 regions, CJK-ish fragments ("と", "m~mv~").
- table.png: 6 regions, pseudo-Latin cell fragments.
- equation.png: 0 regions → "(no text recognized)" path works.
- mixed.png: 7 regions in reading order, pseudo-text.
- Synthetic outputs are meaningless by design (not handwriting); the
  mechanics (boxes, order, timings, empty path) are what they verify.

## UI verification status
- Verified with screenshots: window renders, model identity shown, Open
  Image + file chooser + preview work, Extract fills transcript AND raw
  identically, buttons enable/disable correctly, typing edits transcript
  while raw stays pristine, Copy places the edited transcript on the
  clipboard (`xclip` confirmed), status messages accurate.
- Re-extract confirmation dialog + duplicate/mid-job guards: implemented,
  code-reviewed, NOT interactively triggered (xdotool focus limits).
- No production signing; debug APK/standalone-installer distinction N/A
  (desktop app, runs from Gradle; no installer built).

## Remaining issues
1. Real handwriting accuracy MEASURED once (2026-11-21,
   `samples/handwriting-test.jpg`, now the standard sample — original at
   `/home/kali/Desktop/test.jpg`): 2/3 lines recovered
   ("This is how it's done" exact at rec 0.994; "LIFE" split from
   "E ITSELF IS COMPLICATED"; cursive "My Name is Debjoy." missed).
   Total 4053 ms on 1800x4000 (detInfer 3030). Broader handwriting coverage
   still needed; no tuning applied.
2. Reading order follows upstream `sorted_boxes` on quad point 0; on
   strongly rotated quads the displayed `box` y0 can differ from the sort
   key — same as upstream, by design.
3. CPU-only, sequential; no performance optimization attempted.

## Next task (Phase 2)
Image review + numericals: region review UI with source-image context,
per-region correction, and worked-calculation transcription using the
`samples/transcriptions/*.txt` references. Do NOT claim handwriting
accuracy until measured on real handwriting.

## Rename note (2026-11-21)
Product renamed `NotebookOCR` → `notebook-ai` (rename-only commit, no
behavior/model/dependency changes): window title + heading, Gradle root
project `notebook-ai` (dist `build/install/notebook-ai`, jar
`notebook-ai-0.1.0.jar`, group `ai.notebookai`), Java package
`notebookocr.*` → `notebookai.*` (hyphen-free), class `NotebookApp` →
`NotebookAiApp` (`Main` unchanged), README title. Verified: clean build,
25/25 tests, `verifyOcrSetup` pass, CLI smoke on the handwriting sample
identical, window title `notebook-ai` confirmed by screenshot.
Intentionally untouched: PaddleOCR/ONNX names, model files/checksums/dicts,
third-party license, `archive/android-rn-phase0` tag, historical commits
(which still say NotebookOCR).

## Global install note (2026-11-21)
`Makefile` added (`build/test/verify/recognize/run/clean/install/uninstall/system-deps/help`).
`ModelConfig.modelsDir` now honors `NOTEBOOK_AI_MODELS` (else CWD/models,
unchanged). Installed to `~/.local` (no root): `notebook-ai` resolves on
PATH from any directory; OcrSetupCheck via installed jars from /tmp loads
both sessions (exit 0); GUI launches with `notebook-ai` title, screenshot
verified. `make uninstall` verified (command gone), then reinstalled.
System-wide equivalent: `sudo make install` (needs the user's password;
no passwordless sudo here). `make system-deps` opt-in apt-installs
openjdk-21-jre. No runtime downloads, no version changes.

## sudo build fix (2026-11-21)
`sudo make install` failed with `Unsupported class file major version 69`:
as root, Gradle lost the user's `~/.gradle/gradle.properties` JDK pin and
ran on Java 25. Fixed in the Makefile two ways: (1) every Gradle call now
passes `-Dorg.gradle.java.home=<jdk21>` when that path exists (verified
with an empty HOME: daemon JVM is JDK 21); (2) `install` no longer builds
as root — it verifies the staged dist + checksums and only copies files, so
the documented flow is `make build models` (user) then `sudo make install`.
No root-owned files were left in the repo (the failed run died at startup).

## Commit
Phase 1 commit on `main` (see `git log`). Models stay git-ignored;
no push.

## Phase 1 correction: tilted handwriting (3 evidence-based deviations)
Real sample `samples/handwriting-test.jpg` (1800x4000, text tilted ~33 degrees) lost its
third line, split line 2 into "LIFE" + "E ITSELF...", and mis-ordered lines.
Root causes (traced with `--debug-dir`, which now also writes `<stem>_trace.txt`,
`_probmap*.png`, `_regions.png`, `_oriented.png`, and one `_crop_R<cand>.png` per region,
including regions dropped by drop_score):
1. Line 3 was detected (candidate with slow score 0.838) but rejected at box_thresh because
   `score_mode=fast` averages over the whole min-area rectangle (0.341). Now `slow`
   (upstream-supported mode, contour mean); box_thresh stays 0.45.
2. The detector split line 2 at a word gap; unclip padding (~0.7x line thickness) of the
   right region reached over the last letter of the left one (recognizer on the pre-unclip core
   read no "E"). End padding is now cut back only where it enters a collinear neighbor's core.
3. `sorted_boxes` (first-corner y) misorders tilted lines. `ReadingOrder` projects regions into
   the dominant text frame, orders rows/regions there, and joins regions with a space only when
   the gap is under half a line thickness; region results are never merged or edited.
Diagnostics: `./gradlew recognizeImage -Pimage=<line-crop.png> -PrecOnly=1` runs the recognizer alone.
Known limit: the padded crop for the large cursive third line reads poorly (model/crop-context
limit, not a pipeline defect); the unpadded core crop reads noticeably better.

## Stale-install incident (after fa654ec)
Symptom: user still saw the pre-fix transcript after `make build && sudo make install`.
Cause: not the code. `~/.local/bin` precedes `/usr/local/bin` on PATH, and an older user-local
install (11:33, jar had no `ReadingOrder`/`DebugTrace` classes) shadowed the freshly installed
system copy. Source CLI, staged dist and `/usr/local` all gave the fixed transcript; only
`~/.local` gave the old one (proved by jar hashes, bytecode listing and running each copy).
Changes: `make install` now deletes only the app-owned `<prefix>/lib/notebook-ai/{bin,lib}`
(path-validated) before copying, so stale jars cannot survive, and warns when another
`notebook-ai` earlier on PATH shadows the one just installed. The CLI prints, and the UI status
line shows, a build identity (jar path, models dir, detector score mode).
