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

## Phase 2 (2026-11-21, this commit)
- JavaFX named-module launch (root cause + fix below); prep (rotate L/R,
  straighten ±10° slider + apply, reset, crop select/apply/clear) over an
  explicit working image (original untouched); selection mapping with
  letterbox inversion + clipping; Recognize Selection (det+rec) and
  Recognize Line (rec-only) into a separate review area (copy/insert-at-caret,
  never auto-spliced); region list + image-overlay review (raw text, det/rec
  scores kept distinct, recognizer-crop thumbnail); verbatim transcript with
  raw-restore confirmation; background jobs with generation+revision stale
  guards and mid-job control locks; sessions reused across jobs.
- Root cause of the startup warning: Gradle's application plugin puts every
  jar on the classpath, so JavaFX's `module-info` never took effect. Fix:
  the 3 `*-linux.jar` platform artifacts (the only jars carrying module-info;
  unclassified JavaFX jars are empty) go on `--module-path` with
  `--add-modules javafx.controls`; app/ONNX/metadata-extractor stay on the
  classpath (app remains non-modular). Applied to `run`, staged unix +
  Windows launchers (asserted surgery with CRLF preserved), installed
  wrapper inherits it. `Main` prints `javafx.graphics (named module)`; the
  old warning is gone (JDK 21 runtime is warning-free; JDK 25 adds its own
  unrelated native-access notes, left alone). CLI/verify stay headless.
- Handwriting sample A/B/C/D (settings + outputs in
  samples/transcriptions/handwriting-test.txt): page = 4 regions
  ("This is how it's done" exact; "LIFE"+"ITSELF IS COMPLLCATED" grouped;
  "y Noaw Bedy" rec 0.545); manual third-line crop [40,1780]+1400x720 =
  "don"/"TSELF IS ComPLICATE"/"My Name is Sebiay" (0.908); straightened
  (-27°) = "don"/"TSELF IS COmpLLATE"/"My Naove is utjiy" (no fix);
  rec-only on multi-line crops = garbage-as-expected ("hi" 0.252, "福" 0.428);
  rec-only on the pipeline auto-crop = "y Noaw Bedy" (0.545), identical to
  page run. Geometry is not the blocker; large cursive misreads persist.
  Nothing hard-coded, no score-based picking, no new padding heuristic.
- Tests/builds: `./gradlew clean build` green (45 tests: +13 prep/mapping/
  stale-guard/verbatim); `verifyOcrSetup` exit 0; CLI smoke on all samples;
  staged launcher runs headless-safe paths; `make build` + user-local
  `make install` verified (`type -a` still user-local first; /usr/local
  untouched — system update command: `sudo make install`).
- GUI verified live: render, open/preview, page extract (regions + overlay +
  transcript + raw + identity), straighten-apply, edit-preserves-raw, copy
  (xclip). Region detail pane observed working (list → raw/scores/crop/
  buttons + overlay highlight). NOT interactively verified here: mouse
  drag/click delivery (xdotool button events don't reach this JavaFX app;
  keyboard does), so selection boxing, crop-apply, region picking,
  selection/line execution, restore/confirm dialogs are code-reviewed and
  unit-covered but need a real mouse. Note: this display is shared — an
  independent live session was observed mid-verification; no files raced
  (worktree verified clean of foreign edits before commit).
- Remaining: real-handwriting breadth (1 sample); rotated-quad sort-key
  caveat stands; CPU-only sequential.

## Commit
Phase 2 commit on `main` (see `git log`). Models stay git-ignored;
no push.

## Phase 2 UI/OCR verification pass (after d85dbd4 was pushed)
Input: `~/Desktop/test.jpg`, sha256 `acc880a8b518c316...`, 4000x1800, EXIF 0 (4 written lines + signature,
text runs top-to-bottom). The reported output reproduces exactly with one **Left** rotation:
`./gradlew recognizeImage -Pimage=$HOME/Desktop/test.jpg -Protate=3 [-PdebugDir=DIR]`
(`-Protate`/`-Pstraighten` use the same `WorkingImage` as the UI).
GUI was driven on a private Xvfb (`:77`) with xdotool + xclip against the installed `~/.local` build (jar sha
compared with staged), because another agent shares display `:0` and closed my window there.

Defects found (real GUI) and fixed:
1. **Channel order**: `ImageOps.decode` returned RGB bytes labelled BGR (`Raster.getDataElements` is band order).
   Preview/region thumbnails had red/blue swapped and the detector saw non-upstream channel order. Now true BGR
   (test `decodeReturnsTrueBgrChannelOrder`). OCR effect: mixed on real handwriting (old sample line 3 closer; new
   image `ITSIELF`/`dehjay` vs `ITSELF`/`debjay`), identical on rendered print, different pseudo-text on the
   synthetic scribble samples. A correctness fix, not an accuracy cure.
2. **Recognize Selection/Line stayed disabled** after drawing a selection (enable state only recomputed after jobs).
   Now `ControlState` (pure, unit-tested) applied by `updateControls()` after every state change.
3. **Layout**: transcript squeezed to ~2 rows, raw pane off-screen -> scroll pane, shorter fixed areas.
4. **"progress." on its own line**: a real newline from grouping (gap 86px vs padded thickness 159/84; source is one
   line). Word-gap rule is now `0.6 x taller neighbor` (`ReadingOrder.WORD_GAP`); column gaps still split.
5. **Selection OCR used its own newline join**; now uses `OcrResult.combinedText()` like page extraction.

Verified in the real GUI: open, Left/Right, straighten, crop draft/apply/clear, reset, Extract Page (+ edit-confirm
Cancel/OK), region select by list (overlay/detail/thumbnail), Copy Text / Copy Region / Copy Selection (clipboard
contents read with xclip and compared), Insert at Caret (region + selection), Restore Raw Cancel/OK,
Recognize Line, Recognize Selection.
NOT verified interactively: job-failure state, mid-job stale-result discard (unit-covered only), region pick by
clicking on the image, combined crop+rotate+reset, the system-wide `/usr/local` copy (sudo needs a password).

Character errors (model limits, not pipeline): `COMPLLOATED` is read identically from every manual crop of line 2
(tight, +10/25/50/80px pad, word-only); `Debjoy` is unstable with padding (`Debjay`, `debjay`, `dehjay`, `Sebjon`).
Auto crops were clean (no neighbor leakage). No replacement rules were added.

## Phase 2 closure (acceptance pass)
**Status: Accepted** for the Phase 2 scope below, with the OCR-accuracy limitation and gaps listed here.

Scope delivered: named-module JavaFX window; working-image preparation (rotate 90 deg, straighten +-10 deg, rectangular
crop, Reset) on an immutable oriented source; Extract Page; Recognize Selection (detector+recognizer) and Recognize Line
(recognizer only); region list/overlay/detail/thumbnail with click-to-select; editable transcript with raw output kept
read-only and Restore Raw; Copy Text / Copy Region / Copy Selection and Insert at Caret; build identity line.

Acceptance method: real GUI (xdotool + xclip) on a private Xvfb `:77` driving the installed `~/.local` launcher
(jar sha256 prefix compared with the staged `build/install` jar before every session; last: `927f63ef60fc`).
Shared display `:0` and other windows were not touched. `/usr/local` (system) copy was NOT updated (sudo needs a password).

| Check | Method | Outcome |
|---|---|---|
| A. Click a region on the image | GUI | Pass: overlay, list entry, raw text, det/rec scores (match CLI) and thumbnail stay in sync; also on a cropped/rescaled image; click in letterbox deselects cleanly |
| B. crop -> rotate -> reset | GUI | Pass after fix: crop applies to working image only; original file hash unchanged; Reset restores original frame and clears results/overlay; Extract on the reset image is byte-identical to CLI `recognizeImage` rotate 0. **Defect found/fixed**: rotating after a crop reused the crop rect in the new frame; crop is now cleared with a status note |
| C. Job failure + recovery | GUI, throwaway copy of the models via `NOTEBOOK_AI_MODELS` renamed away and back (installed models untouched) | Pass: visible "Failed: OCR setup incomplete..." message, all controls re-enabled, next Extract in the same app succeeded |
| D. Stale-result protection | GUI + automated | GUI: every state-changing control is disabled while a job runs, attempted clicks mid-job had no effect and the result was not overwritten (the scenario is unreachable from the UI). Guard itself: automated only (`WorkingImageTest`: each real mutator invalidates an in-flight job; `isCurrentJob`), not exercised through the JavaFX job thread |

Builds/tests: `./gradlew clean build` -> 53 tests, 0 failures; `./gradlew verifyOcrSetup` -> setup valid; `make build`;
`make install PREFIX=$HOME/.local` (no shadowing warning). CLI regressions unchanged vs previous pass: rendered print
4/4 lines exact; `samples/handwriting-test.jpg` -> `This is how it's done | LIFE ITSELF IS COMPLLCATED | My Nowve leiy`;
synthetic scribble samples give pseudo-text (not meaningful).

### Known OCR accuracy limitation (not solved)
Image `~/Desktop/test.jpg` (sha256 prefix `acc880a8b518c316`, 4000x1800), one Left rotation:
`./gradlew recognizeImage -Pimage=$HOME/Desktop/test.jpg -Protate=3`
```
This is how it's done            (correct)
LIFE ITSIELF IS COMPLLOATED      (expected: LIFE ITSELF IS COMPLICATED)
My Name is dehjay                (expected: My Name is debjoy)
The work is on progress.         (correct text; structure now one line)
```
Errors: extra "I" in ITSELF, COMPLICATED and debjoy misread. Earlier manual-crop experiments showed `LLOATED` is stable across
crops and `Debjoy` varies with padding (`Debjay`, `debjay`, `dehjay`, `Sebjon`); this does not prove the cause is
model-only. The true-BGR decode fix was a correctness fix and did NOT demonstrably improve accuracy on this image
(some words better on other samples, some worse here). No replacement rules, dictionaries or expected-text logic exist.
A tighter crop of the same page (GUI) read different text (e.g. `Debjay.`), i.e. results are input-sensitive.

### Remaining gaps
- System-wide `/usr/local` install not refreshed (run `sudo make install`).
- Recognize Selection/Line failure path and the JavaFX job-thread stale-result path were not driven in the GUI.
- Single real handwriting page for accuracy; no accuracy benchmark exists.
