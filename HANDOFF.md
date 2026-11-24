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

## Phase 3: accuracy + evaluation (2026-11-22, this commit)
- Starting state verified: main at 9d8637b (= origin/main), tree clean. No
  roadmap doc defines Phase 3 differently; scope recorded here.
- Baseline preserved: `~/Desktop/test.jpg` present, SHA-256
  `acc880a8b518c3162dadbd05c0bfa6954f38f4fc7ecf21a27a6974dabee491d8`,
  4000x1800, reproduced via `./gradlew recognizeImage
  -Pimage=$HOME/Desktop/test.jpg -Protate=3` (exit 0). The repo 3-line
  sample (`a52576ac…`) is a different, older photo — kept as a separate
  regression case with its own ground truth (Debjoy vs debjoy kept as-is).
- Harness: `notebookai.eval` (EditDistance CER/WER on code points, whitespace
  tokenization, positional line reporting, no normalization), `Evaluate`
  runner (manifest TSV: image/rotate/straighten/expected, one shared session,
  init timed separately, optional per-case debug dirs), `./gradlew evaluate
  -PevalManifest=...`. Committed `eval/cases.tsv` (repo 3-line sample);
  private `eval-local/cases.tsv` (git-ignored, 4-line sample). 7 new metric
  tests (empty/insert/delete/substitute, kitten/sitting, manifest parsing).
- Dataset: 1 real page per case (2 photos total, same notebook). Labeled
  limited. Synthetic scribbles excluded from accuracy (no ground truth).
  No numerical/equation ground truth exists. Private image + transcription
  NOT committed. Request: more user pages with exact transcriptions —
  cursive + print, mixed case, names, numbers/units/equations, varied
  angles — via `eval-local/cases.tsv`.
- Results (verbatim primary): 3-line sample CER 0.179 (12/67), WER 0.308
  (4/13), lines 3->3; 4-line sample CER 0.055 (5/91), WER 0.167 (3/18),
  lines 4->4. Init ~370ms; page totals 2.6-4.3s.
- Experiments (all reported): tight line-3 crop [220,1470]+1500x385 →
  "My Name is Debjay/work e" (D error persists without neighbors);
  ±3° straightened rec-only → "" (0.000) and "y   Bay" (0.323) — worse,
  crop already level; multi-line rec-only → "hi"/"福" (correctly
  meaningless); auto-crop rec-only → "y Noaw Bedy" (0.545), identical to
  page run. Surrounding line fragments noted where present.
- Audit: full boundary check vs v3.7.0 sources (see MODEL_MANIFEST); no
  defect found. Rejected: BGR revert (fix verified correct), width caps,
  straightening as a fix, model replacement (needs approval + evidence).
- Fixes: NONE to production recognition — no change beat the baseline
  without inventing mappings. Kept the correct baseline + tooling.
- Verification: `clean build` green (60 tests), `verifyOcrSetup` exit 0,
  both eval manifests exit 0, CLI regressions (numericals/equation still
  return regions; RealSampleRegressionTest + DetectionRegressionTest pass).
  No production recognition change → no reinstall (installed GUI predates
  Phase 3 but is behavior-identical; user-local prefix untouched;
  /usr/local untouched — system update command: `sudo make install`).
  GUI not re-driven (unchanged since Phase 2 verification).
- Remaining: broader handwriting coverage; the 5 known glyph confusions
  (b/h, o/a, I/L, C/O + 1 insertion) are model-level at high confidence —
  confidence must not be read as correctness.

## Commit
Phase 3 commit on `main` (see `git log`). Models stay git-ignored;
private eval cases stay out of git; no push.

## Phase 3b: recognition-boundary investigation (2026-11-22) — PRE-REGISTRATION (written before any experiment code)
Baseline reproduced from a clean build (60 tests green; `verifyOcrSetup` ok): repo photo CER 12/67 WER 4/13; private photo
CER 5/91 WER 3/18; identical transcripts to the Phase 3 report. `./gradlew ctcProbe -PevalManifest=...` (new, diagnostic only)
shows the recognizer output is a per-frame normalized distribution (row sums 1.0), 8 px/frame, and that decoding the probed
logits reproduces production text for every region (decode-consistent=true).
Observed (see results section when completed): in the private photo the expected char is rank 2 (I, C, o) or 3 (b) at the
erroneous frame; two errors are LOW margin (ITSIELF insertion 0.420 vs blank 0.364; b->h 0.414 vs d 0.251), so the earlier
"all high confidence" statement was wrong. In the repo photo line 3 the padded crop is 1416x438 -> 156 px wide -> ~20 valid
frames for 18 expected chars (frame starvation) and 11 edits.

Experiments (opt-in only, default OFF, never selected by ground truth/confidence; every variant reported):
- E-A  crop padding fraction. Hypothesis: unclip padding inflates the crop height so text occupies a small part of the 48 px
  input and a tall crop leaves few frames per character; reducing padding for the RECOGNIZER crop only should help lines like
  repo-photo line 3 and may hurt lines that need padding at stroke ends. Single factor: recognizer crop corners =
  core + lambda*(unclipped-core); detector, boxes, reading order unchanged. Variants: lambda=0.5 and lambda=0 (baseline 1.0).
  Plausible for this model: PP-OCRv6 rec is trained on tight 48 px-high line crops. Samples: both handwriting photos
  (per-case), rendered print fixture (regression). Improvement = fewer char edits on a case without more edits on the others.
  Caveat: earlier ad-hoc tight crops gave mixed results on one page; this is the first controlled comparison.
- E-B  decoder alternatives. Hypothesis: CTC prefix beam search (width 8, no LM) can fix low-margin errors that greedy
  argmax commits to (e.g. the single-frame ITSIELF insertion) because it sums over alignments. Expected small effect since
  most correct chars are far from top-1. Single factor: decoder; recognition score kept from greedy for the drop_score filter.
  Same samples/criteria.
Not run (no new hypothesis): channel reversal, straighten sweeps, width caps, model swap (needs approval).

### Phase 3b results (all completed variants reported; exploratory, 2 handwriting pages)
Identity: jar `99b87fb06190…` (staged == `~/.local`), models det `d73e0058b7a8…` rec `5435fd747c9e…`
(rec.yml `ab078671…`, det.yml `193f4352…`), detector score mode SLOW, Evaluate path = production `OcrService`.
Images: repo photo `a52576ac7177…` 1800x4000 rotate 0; private photo `acc880a8b518…` 4000x1800 rotate 3 (git-ignored manifest
`eval-local/cases.tsv`, expected text exactly as supplied: `debjoy` lowercase — the handwriting's first letter looks capital, so
case ground truth for that word is ambiguous and was NOT guessed; it costs at most 1 edit). CER/WER: raw code points / whitespace
tokens, no normalization, per case (no pooled aggregate across pages: 2 pages are not independent samples of handwriting).

Baseline (reproduced, clean build, 65 tests green, verifyOcrSetup ok):
repo CER 12/67 (0.179) WER 4/13; private CER 5/91 (0.055) WER 3/18; synthetic print CER 0/… (separate, not handwriting).

Recognition-boundary diagnostics (`./gradlew ctcProbe -PevalManifest=...`; probe decode == production text for every region):
- Output tensor [steps x 18710], row sums 1.0 (per-frame normalized; NOT calibrated), 8 px/frame, steps = padded width/8.
- Private photo error classification (character alignment): ITSELF: 1 insertion (I at frame 20: top1 0.420 vs blank 0.364 — low
  margin); COMPLICATED: 2 substitutions (I->L top1 0.738, expected I 0.202 rank 2; C->O 0.889, expected C 0.097 rank 2);
  debjoy: 2 substitutions (b->h 0.414 vs d 0.251, expected b 0.147 rank 3; o->a 0.990, expected o 0.008 rank 2). Crops are
  well-formed; all errors arise at the recognition output (not detector, not assembly). Line 1 and 4 exact.
- Repo photo: line 2: 1 substitution (I->L 0.697, expected I 0.166 rank 2). Line 3 (11 edits): the recognizer input is the
  1416x438 padded crop resized to 156 px wide = ~20 valid frames for 18 expected chars; output has 5 deletions and many
  low-margin substitutions (top1 0.23-0.38). This is consistent with a starved frame budget but not proof of it.
- Correction: the Phase 3 statement "all at high model confidence" was wrong (see above) and is fixed in MODEL_MANIFEST.

Experiments (opt-in JVM flags via `-PexpJvm=...`, default OFF, shown in `identity()`):
| Variant | repo photo (edits CER/WER) | private photo (edits CER/WER) | print |
|---|---|---|---|
| control | 12 (0.179) / 4 | 5 (0.055) / 3 | 0 |
| E-B beam8 (`-Dnotebookai.exp.beam=8`) | 11 (0.164) / 4: `My Nowet leiy` | 4 (0.044) / 2: ITSELF fixed, rest same | 0 |
| E-A cropPad 0.5 | 8 (0.119) / 4: `COmMpLICATED`, `My Nave is. dedjey` | 8 (0.088) / 4: `My`->`M`, `pvogvess.`, COmpLLOATED | 0 |
| E-A cropPad 0 | 7 (0.104) / 2: `COmpLLCATED`, `My Name is dehjay` | 19 (0.209) / 7: lost/garbled lines | 11 (0.151) |
Judgement: E-B improved both photos by one edit each (ITSELF fixed; no print regression) — too small, 2 pages, same notebook:
NOT promoted, stays experimental. E-A helps the repo photo but hurts the private photo and (at 0) print: padding trade-off is
input-dependent (tighter crops clip stroke ends); NOT promoted, hypothesis only partly supported (frame-budget story fits repo
line 3, not the private photo). Greedy score is kept under beam, so drop_score filtering is unchanged. No ground truth,
confidence or filename selects any output. Not run: antialiased downscale (hypothesis recorded, no evidence it matters),
straighten/width-cap/channel experiments (no new hypothesis), any model swap.
Production changes: none to behavior. Added diagnostics only: `OcrService.recognizeLogits` (decode path unchanged),
`CtcProbe`, `Alignment`, `Experiments` (off by default; tested), `CtcDecoder.decodeBeam` (experimental, tested).
Installed-GUI smoke (private Xvfb, `~/.local` build): page Extract == CLI baseline; Restore Raw after an edit returned the exact
raw text; Recognize Line `LIFE ISENF IS COMPLLOATED` and Recognize Selection (3 lines) unchanged vs the Phase 2 check.

Reproduce: `./gradlew clean build verifyOcrSetup`; `./gradlew evaluate -PevalManifest=eval/cases.tsv` (and
`eval-local/cases.tsv` if present); `./gradlew ctcProbe -PevalManifest=eval-local/cases.tsv -PdebugDir=/tmp/probe`;
variants: `./gradlew evaluate -PevalManifest=eval-local/cases.tsv -PexpJvm="-Dnotebookai.exp.beam=8"` or
`-PexpJvm="-Dnotebookai.exp.cropPad=0.5"`.

### Dataset inventory and limits
Approved real handwriting with transcription: exactly 2 pages (same notebook/pen/writer, same session) => generalization cannot be
assessed; crops of a page are not independent. Synthetic scribbles have no ground truth; rendered print is a regression check only.
No numerals/equations/units ground truth exists, so no numeric accuracy is claimed.
REQUEST for more pages (keep private; add to `eval-local/cases.tsv`, nothing is uploaded anywhere): several writers and styles
(print, cursive, mixed, UPPERCASE), names and unfamiliar words, numbers/decimals/signs/units, simple equations, different
lighting/angles/ruled and plain paper; each with the exact verbatim transcription, intended line breaks, and case as written.

### Unproven / needs approval or evidence
- Whether the remaining glyph confusions are model-only is NOT established (not disproven either).
- A numeric cross-check of our hand-ported resize/warp/normalization against a reference stack (cv2 + onnxruntime in a throwaway
  venv, roughly 100 MB of PyPI downloads, no project dependency) was not done: needs approval.
- Recognizer comparison: no candidate was researched or run; any download/model change needs approval and a specific candidate.
- Beam search and crop padding need many more pages before any production decision.

## Phase 3c: independent numerical cross-check (reference = upstream PaddleOCR v3.7.0 rules + cv2 + onnxruntime)
Reference stack (disposable venv OUTSIDE the repo, not committed): onnxruntime 1.24.1 (Java uses 1.22.0; the oldest wheel for
Python 3.14 is 1.24.1), opencv-python-headless 5.1.0, numpy 2.5.5, pyyaml. Upstream rules were re-implemented in
`tools/xcheck/xcheck.py` from the fetched upstream v3.7.0 files `tools/infer/utility.py` (get_rotate_crop_image),
`tools/infer/predict_rec.py` (resize_norm_img, batching), `ppocr/data/imaug/operators.py` (DetResizeForTest/NormalizeImage/
ToCHWImage). cv2 is the reference only where upstream itself uses cv2 (resize, getPerspectiveTransform, warpPerspective).
Method: `./gradlew crossCheckDump -PevalManifest=M -PoutDir=D` exports every stage's data from the production code; the script
runs each reference stage on IDENTICAL input, so a mismatch is localized. Dumps (private image data) were deleted afterwards.
Reproduce: `python3 -m venv V; V/bin/pip install -r tools/xcheck/requirements.txt;
XC_MODELS=models V/bin/python tools/xcheck/xcheck.py DUMP/case0 /path/to/original.jpg`.
Tolerances were fixed before looking at results (see the script docstring): decode mean<=1 level; rotate exact; det input
mean<=0.01 max<=0.10; det inference max<=5e-3; warp mean<=1.5 p99.9<=10; rec input pad exactly 0, mean<=0.004, max<=0.06;
rec inference max<=1e-3 and identical text.

Stage results (both pages; "before" = commit a6749ee, "after" = d2e7c3a):
| Stage | Before | After |
|---|---|---|
| JPEG decode (Java ImageIO vs cv2.imread, BGR) | identical (diff 0) | identical |
| Rotation | exact | exact |
| Detector input tensor (resize+normalize, 1x3x4000x1792) | PASS (mean 7e-7, max 0.0175 = 1 level) | PASS |
| Detector inference, same tensor (ORT 1.22 vs 1.24) | PASS (max 6e-8, binarized disagreement 0) | PASS |
| **Perspective warp of tilted crops** | **FAIL: Java darker by ~16 levels (mean 14-17, max 28) on every tilted crop; axis-aligned crop exact** | PASS (mean 0.000, max 1 level) |
| Recognizer input (resize, pad, normalize, layout) on identical crop | PASS (pad exactly 0, max 1 level) | PASS |
| Recognizer inference, same tensor | PASS (logits max diff <=1.4e-6, identical text) | PASS |
| End-to-end Python path vs Java | text differed on 1 region (R2) | identical text on all regions of the private page |

Confirmed defects (fixed, tests in `ReferenceParityTest`):
1. `ImageOps.cubic()` mixed Keys a=-0.5 inner coefficients (1.5/-2.5) with the a=-0.75 outer branch; the four tap weights
   summed to 0.9375..0.98 at fractional offsets, so every non-integer-aligned interpolation (tilted crops, straighten) was
   darkened by up to ~12% in 2D. Now 1.25/-2.25 (weights sum to 1). Cause found by the cross-check; verified numerically.
2. `DetPreprocess` rounded detector size with `Math.round` (half up) while upstream uses Python `round()` (half to even):
   e.g. 1808 -> 1792 (upstream) vs 1824. Now `Math.rint`. Neither photo hits an exact .5.
Neither defect explains the reported wrong words: after the fix edit counts are UNCHANGED (repo photo 12/67 CER, WER 4->5 words;
private photo 5/91, WER 3/18; print 0). Specific wrong characters moved (`dehjay` -> `dedjay`; repo line 3 `My Nowve leiy`
-> `Sty Nove to leiy`), i.e. the output is sensitive to tiny input changes. Private-page output now:
`LIFE ITSIELF IS COMPLLOATED` / `My Name is dedjay`.

Other findings (not defects): (a) padding width matters for this model: with upstream-style batching (6/batch, padded to the
batch max width) repo-photo line 3 decodes differently (`Sy Nove leiy`) than our batch=1 (`Sty Nove to leiy`) and than the
Python batch=1 path (`Sty Nove o leiy`); the private page is unaffected. So upstream output depends on batch composition;
our batch=1 is deterministic. The earlier Phase 1 note "padding only adds trailing blanks" is not generally true for this
attention-based recognizer. (b) The remaining errors reproduce in an independent reference stack on identical tensors, which
makes a pipeline cause less likely; it does NOT prove no other issue exists (DB postprocess/contours/unclip and the
detector-box -> crop quads were not cross-checked: pyclipper/shapely were not installed).
Verification: clean build 69 tests green; verifyOcrSetup ok; both manifests + print evaluated; installed `~/.local` jar ==
staged (`35889d79…`); installed-GUI smoke (private Xvfb): page Extract == CLI, Apply Straighten renders normally.

### Next recognizer: research summary (sources via web search; NOTHING downloaded or run)
Verified: `microsoft/trocr-base-handwritten` / `trocr-small-handwritten` — MIT licence (model card), BEiT encoder + RoBERTa decoder
fine-tuned on IAM; ONNX conversions exist (Xenova, for Transformers.js; export via Optimum). NOT verified from the sources:
parameter counts, IAM CER of the Microsoft checkpoints, ONNX file sizes, CPU latency. `PP-OCRv5_server_rec_onnx` (official
PaddlePaddle HF repo): third-party listings give ~81-84 MB; same pipeline family (drop-in preprocessing, different dictionary);
handwriting gains NOT verified. docTR/OnnxTR: Apache-2.0 library, mostly printed-document focus; weights' licence not confirmed.
Recommendation for an isolated evaluation (needs approval before any download): TrOCR small handwritten ONNX, evaluated in the
disposable venv on the production detector's line crops of the two existing pages, per-case CER/WER. Trade-offs to weigh first:
autoregressive decoder (latency, tokenizer + generation loop would have to be reimplemented in Java), English/IAM domain, and a
language-model decoder can silently "repair" or invent words, which conflicts with verbatim names. No promise it fixes `debjoy`.
Cheaper first step if preferred: PP-OCRv5_server_rec_onnx (same code path). Neither has been downloaded.

## Phase 3d: detector post-processing cross-check, padding study, model-trial proposal (2026-11-22)
Unpushed history verified with git: 7 commits ahead of origin/main = cd780a9, 940bbc3 (Phase 3 harness/docs) + 1c57b7c, a6749ee,
d2e7c3a, 8654c98, 5fd4bc5 (+ the commits listed at the end of this section). An earlier "five commits" statement was a miscount.
Env: onnxruntime 1.24.1 (Java 1.22.0), opencv 5.1.0, numpy 2.5.5, pyyaml, pyclipper 1.4.0, shapely 2.2.0 (disposable venv, not in
repo). Reference = upstream v3.7.0 `db_postprocess.py` (quad path, re-implemented from the source: it imports paddle so it cannot be
run as-is), `predict_det.py` (order_points_clockwise/clip/filter), `predict_system.py` (sorted_boxes). Params = det.yml (0.2/0.45/1.4,
min_size 3), score mode SLOW (Java) and FAST both run on the reference. Reproduce: `./gradlew crossCheckDump -PevalManifest=M
-PoutDir=D` then `python tools/xcheck/xcheck_det.py D/case0 tag` and `python tools/xcheck/xcheck_pad.py D/case0`
(XC_MODELS=models; dumps contain private pixels: keep outside git, delete after use).

Detector stage results on the IDENTICAL probability map (both pages):
| Stage | Result |
|---|---|
| Threshold mask | exact pixel count (179912 / 213783) |
| Contours | every Java outer contour matched by bbox (13/13, 30/30). RETR_LIST also returns hole contours (119 / 445) which Java does not trace (they score ~0 and are filtered). Polygon area equal except 3 contours differing by 1-2 px^2 (2 tiny rejected, 1 accepted: 285 vs 286 vertices) |
| Candidate retention | identical sets (private 6/6, repo 3/3, no mismatches). Reference FAST scoring rejects the long cursive line-3 box on the private page (5 vs 6): the documented SLOW deviation |
| minAreaRect (accepted) | equal to cv2 within 0.01 px. Pre-set tolerance 0.51 was exceeded only on tiny rejected contours (max 1.0 px) |
| Box score (SLOW) | Java ~0.006-0.013 higher (fillPoly vs scanline mask rasterization); exceeds the pre-set 0.005 tolerance, retention margins are >=0.11 so no effect |
| Unclip (min box after offset) | 0.5-1.8 px apart (pre-set 1.0 exceeded): pyclipper works on integer-truncated inputs, Java uses exact offsets (documented deviation #3, magnitude larger than the earlier "<0.008 px" estimate) |
| Final quads (scaled, clipped) | differ by 0-2 px per corner (exactly equal 1/6 and 0/3); same corner order; clip to W-1/H-1 changed nothing here |
| order_points_clockwise | does not reorder these boxes (same order as get_mini_boxes) |
Effect of the ~1 px box difference: crop sizes differ by 1-2 px and low-margin lines change in BOTH directions (private line 2 with
the upstream box decodes `LIFE ITSELF IS COMPLLOATED` instead of `ITSIELF`; repo line 3 `My Nowve  leiy` vs `Sty Nove o leiy`).
So recognition of these lines is sensitive to +-1 px crop geometry. Not a defect: no change made (documented deviation; replicating
pyclipper quantization was NOT done and is not justified by this evidence).
Reading order: upstream `sorted_boxes` would emit `y Name is dehjay` BEFORE `My` on the private page; our tilt-aware ReadingOrder
keeps `My Name is ...` together. Evidence supports the documented deviation. (The upstream-box crop also leaks the `y` of `My`, which
our neighbor-padding trim removes.)
New general fix: coordinate scaling now uses half-to-even (`Math.rint`, like `np.round`) instead of `Math.round`
(`DbPostprocess.scaleCoord`, tested). No effect on the two pages.

Confirmed defects fixed (earlier, retained): bicubic kernel weights (Phase 3c) and detector size half-to-even rounding. Regression
tests now: weights sum to 1; uniform image preserved through a tilted warp, resize and straighten rotation; textured tilted crop mean
within 1.5% (the old kernel fails 4 of 6 parity tests when restored); half-to-even size and coordinate cases. These fixes did NOT
reduce edit counts on the two photos (12/67 and 5/91 before and after).

Padding study (deterministic, same crop bytes; widths = own / upstream-batched / 320,480,640,960; no expected text or confidence
used). The model input width is dynamic ([-1,3,48,-1]); the pad is exactly 0.0 after normalization (source gray 127.5, not white).
Changing only the pad width changes valid-frame logits (max |d| up to ~1.0, i.e. probabilities) and sometimes the text, e.g. private
line 2: own `LIFE ITSIELF IS COMPLLOATED`, pad 480 `ITSELF ... COMPLLOATED`, 640 `COMpLLOATED`; repo line 3: own `Sty Nove o leiy`,
batched(420) `Sy Nove leiy`, 960 ` N y`; short crops usually unchanged. Rows with width < resized width are NOT padding (the crop is
squeezed) and are excluded from this conclusion. Interpretation: this attention-based recognizer is not invariant to pad width;
upstream's default `rec_batch_num=6` pads each crop to its batch's widest crop so upstream results depend on batch composition,
while upstream also supports `--rec_batch_num 1`, which is what the app does (verified numerically equal: same tensors, same text).
So one-crop inference is a supported path and this is a compatibility/sensitivity finding, NOT a proven implementation defect.
No production batching/padding change was made.

Final transcripts/metrics (unchanged by this phase's code): private photo `This is how it's done / LIFE ITSIELF IS COMPLLOATED /
My Name is dedjay / The work is on progress.` CER 5/91 (0.055) WER 3/18 (0.167); repo photo `This is how it's done / LIFE ITSELF IS
COMPLLCATED / Sty Nove to leiy` CER 12/67 (0.179) WER 5/13 (0.385); print CER 0. Build: clean build 71 tests green, verifyOcrSetup ok.

Cross-check completeness: DONE on identical data: decode, rotation, detector input, detector inference, mask, contours, candidates,
minAreaRect, score, unclip, final quads, ordering, warp, recognizer input, recognizer inference, padding. NOT done: numerical
comparison with the real PaddleOCR Python package end-to-end (paddle not installed; sources were re-implemented), other ORT
providers, other images. The remaining wrong characters reproduce in the reference stack, which makes a pipeline cause less likely but
does not exclude a model-only or an input-sensitivity cause.

### Model-trial proposal (facts read directly from the official pages by two page readers; nothing downloaded)
Corrections to earlier search snippets: the Microsoft TrOCR small card says DeiT encoder / UniLM decoder (not BEiT/RoBERTa) and shows
NO license field; the Xenova ONNX repo shows "License: mit" (that is Xenova's tag, not Microsoft's).
1. `microsoft/trocr-small-handwritten` + ONNX `Xenova/trocr-small-handwritten`. Verified: single-text-line OCR fine-tuned on IAM,
   image-Transformer encoder + text-Transformer decoder, TrOCRProcessor, sentencepiece tokenizer file present; PyTorch weights
   246 MB (pickle .bin). ONNX folder (Xenova) totals 826 MB listing fp32/fp16/quantized variants (e.g. encoder 87.5 MB,
   decoder_model_merged 159 MB, fp16 79.9 MB, quantized 40.5 MB; encoder_model_quantized 23.1 MB). Converter tool/quantization method
   NOT STATED; "temporary" separate ONNX repo per its card. UNKNOWN: Microsoft licence text/redistribution terms, language scope,
   input size/normalization, generation settings, parameter count, any CER, CPU latency. Integration work (estimate, not measured):
   ViT-style preprocessing, sentencepiece tokenizer + autoregressive generation loop (merged decoder with KV cache) in Java, line
   crops from our detector; decoder is a language model and may substitute words.
2. `PaddlePaddle/PP-OCRv5_server_rec_onnx` (official org repo). Verified: license tag apache-2.0 (no license text on page), task
   text-line recognition, English/Chinese/Japanese, card claims handwriting support with a table showing "Handwritten English"
   0.5806 (metric and dataset NOT named; whole-line exact match noted), files: inference.onnx 84.5 MB, inference.yml 148 kB (no
   separate dictionary file listed), converter NOT STATED (uploaded by an individual account). UNKNOWN: input shape/normalization,
   dictionary location, decoding, parameter count, latency, whether it is a drop-in for our preprocessing (same family suggests CTC
   but that is NOT stated). Integration: likely small (same pipeline) if CTC + similar preprocessing; measured unknown.
Recommendation (needs your approval before any download or runtime dependency): evaluate #2 first as a cheap same-pipeline check
(one 84.5 MB file, run in the disposable venv on the production detector's crops of the two existing pages, per-case CER/WER), and
only if it shows promise consider #1 (large, new generation stack). Neither is promised to fix `debjoy`; the PP-OCRv5 handwriting
figure above is a vendor table with an unnamed metric.

## Phase 3e: isolated PP-OCRv5 server recognizer trial (2026-11-22) — evaluation only, nothing integrated
Candidate identity (verified from the HF API/files before download): `PaddlePaddle/PP-OCRv5_server_rec_onnx`, revision
`b70df217f4fd99d14f970bad092cebe7d74cc4d1`, license tag apache-2.0 (no license text on the page). It IS a different recognizer
from the bundled `PP-OCRv6_small_rec` (bundled rec.onnx 21,159,378 B sha256 `5435fd74…`; candidate 84,503,027 B).
Downloaded to `~/.cache/ppocrv5-eval/` (outside the repo, untracked): inference.onnx sha256
`d9dc333c9c7b042c6dffb8e33d72b6f65c9c1d463d0a3c2f78174fea55e94752`, inference.yml `2c719dba044c4e2228aef8ff92f5f575394d75d24c16de096a33b7cfd902f66d`
(148,345 B, contains the character dictionary), README.md `806a481f…`. No other downloads.
Contract (from the artifact + its yml, nothing assumed from v6): input `x[N,3,48,W]` float, N and W dynamic; BGR; `RecResizeImg
image_shape [3,48,320]` (same rule as the bundled model, so our preprocessing applies); `CTCLabelDecode`; dictionary 18,383 entries
(no space entry) and 18,385 output classes = blank@0 + dictionary + trailing space (same convention as the bundled model). The
Java-parsed decode table equals PyYAML's (18,385 entries, identical SHA-256 of the code-point listing).
Method: the unchanged Java pipeline was run with `NOTEBOOK_AI_MODELS=~/.cache/ppocrv5-eval/models_v5` (a scratch dir with the
bundled detector + the candidate rec.onnx/rec.yml + generated checksums), so detector, crops (byte-identical: same sizes at every
region), drop_score 0.5 (applied to each model's own scores), reading order and grouping were identical; only recognition differed.
Timing protocol (both models, one JVM per case, `-Xmx1g`, `evaluate --warmup 1 --repeat 5`): init timed separately, first page run
cold and reported separately, then 1 warm-up and 5 timed runs; peak RSS = getrusage of the child JVM (includes ORT native memory;
dominated by the 4000x1792 detector pass; the machine was shared so treat as +-10%). Reproduce:
`NOTEBOOK_AI_MODELS=$HOME/.cache/ppocrv5-eval/models_v5 ./gradlew evaluate -PevalManifest=M -Pwarmup=1 -Prepeat=5 -PexpJvm=-Xmx1g`
(a 3-case JVM was OOM-killed on this machine; run one case per JVM) and `ctcProbe` for per-region output.

Results (raw, per case; edits = char edits):
| Case | Baseline PP-OCRv6_small | Candidate PP-OCRv5 server |
|---|---|---|
| Repo photo (3 lines) | CER 12/67 (0.179), WER 5/13: `This is how it's done` / `LIFE ITSELF IS COMPLLCATED` / `Sty Nove to leiy` | CER 17/67 (0.254), WER 7/13: `Tis is how it's done` / `LIFE ITSELF SS COMEATED` / `y- Nowe siy.` |
| Private photo (4 lines) | CER 5/91 (0.055), WER 3/18: `…` / `LIFE ITSIELF IS COMPLLOATED` / `My Name is dedjay` / `The work is on progress.` | CER 6/91 (0.066), WER 4/18: `…` / `LIFE ITSELF SS COMPLIATED` / `My Name is dekiay` / `The work is on progrcss.` |
| Rendered print (4 lines) | CER 0 | CER 0 (identical text) |
| Synthetic smoke (numericals / equation / prose; no ground truth) | `1` / `—` / `w` | `~~` / no text / no text (not evidence) |
Per-line changes: private line 2 `ITSIELF`->`ITSELF` (fixed) and `COMPLLOATED`->`COMPLIATED` (3 edits -> 2 on that line) but `IS`->`SS` (new);
line 3 `dedjay`->`dekiay` (2 -> 3 edits: b->k, j->i, o->a); line 4 `progress.`->`progrcss.` (new error). Repo photo: line 1 regresses
(`This`->`Tis`, a line the baseline had exact), line 2 regresses (1 -> 5 edits), line 3 equally unreadable. Candidate per-region
recognition scores are lower on correct lines (e.g. 0.92 vs 0.98 on line 1). Insertions/deletions/substitutions per region are in
`ctcProbe` output (candidate adds more deletions on the repo photo's line 3).
Timing (warm, rec only, mean of 5): repo photo 77 ms -> 281 ms; private 133 -> 548 ms; print 139 -> 510 ms (about 3.6-4x slower);
init 0.42-0.64 s -> 0.71-0.91 s; peak RSS 2.86/2.71/0.63 GB -> 3.10/3.09/0.86 GB (baseline vs candidate; page and print cases).
Model-card figure (0.5806 "Handwritten English") is a vendor table with no named metric/dataset; not interpreted as an error rate.
Verdict: REJECT for integration on this evidence: no net gain on two same-notebook photos (one improved word-level fix is offset by new
errors; the older photo and a previously exact line regress), ~4x slower recognition, larger model, same print result. Broader
evaluation of THIS candidate is not justified by these results; gather more pages first (see dataset request) before testing any
further recognizer. Limitations: 2 photos, 1 writer/notebook, no digits/equation ground truth, single run settings, other models
(e.g. TrOCR) not tried. Production, default decoder/padding/preprocessing and UI routing were not touched (default eval and 71 tests
re-run unchanged).

## Phase 3 closure (2026-11-22): corrected results and final checkpoint
Corrections to earlier reports:
1. Repository-photo WER "4/13" (Phase 3/3b) vs "5/13" (Phase 3c onward) is NOT a metric change. Reproduced at the two commits with the
   same manifest, same models, same metric rules (raw code points; whitespace tokens; no normalization):
   - `a6749ee` (before the fix): line 3 = `My Nowve leiy` (12 hyp words): `My` matches, `Name`->`Nowve`, `is`->`leiy`, `Debjoy.` deleted
     = 3 word edits + `COMPLLCATED` = 4 -> WER 4/13 = 0.308; CER 12/67.
   - `d2e7c3a` (bicubic-kernel fix): line 3 = `Sty Nove to leiy` (13 hyp words): 4 substitutions + `COMPLLCATED` = 5 -> WER 5/13 = 0.385;
     CER still 12/67 (character edits coincide).
   Cause: the kernel fix changed the pixels of the tilted line-3 crop (it had been ~16 gray levels too dark), which changed this
   low-margin line's decode (`My` -> `Sty`). So 4/13 is the pre-fix figure and 5/13 is the current figure; both are correct for
   their commits. Neither is an accuracy gain or loss attributable to "better preprocessing": the output is sensitive to tiny input changes.
2. Recognizer source crops were confirmed identical by CONTENT for the PP-OCRv5 trial: `ctcProbe` now prints the SHA-256 of each
   crop's BGR bytes plus its dimensions; baseline and candidate runs gave identical hash lists for all regions (repo photo 3/3,
   private photo 6/6, print 4/4). (Earlier statements relied on matching dimensions only.)
Final default-pipeline results (unchanged, current HEAD): repo photo CER 12/67 (0.179), WER 5/13 (0.385); private photo CER 5/91
(0.055), WER 3/18 (0.167); rendered print CER 0. `clean build` 71 tests green, `verifyOcrSetup` ok; bundled rec.onnx sha256 `5435fd74…`.
PP-OCRv5 server rec: REJECTED FOR INTEGRATION ON THE CURRENT EVIDENCE only (two photos from one notebook/writer, no digit/equation
ground truth). This is not a claim about the model's suitability for handwriting in general.
Review before push: unpushed diff contains no images, tensors, models, caches or env files (net); `eval-local/` stays git-ignored;
production source has no expected-text logic or replacement rules; experiments (`-Dnotebookai.exp.cropPad`, `-Dnotebookai.exp.beam`)
are opt-in JVM properties, default off, and shown in `identity()`. Note for the record: `HANDOFF.md` quotes the sample sentences
(already present in the pushed history and in the committed `samples/transcriptions`) and history contains one transient 24 KB
compiled `.pyc` (added in `8654c98`, untracked in `47f13d6`; contains only the script's local path and code strings, no private data).
It cannot be removed without rewriting history, which was not done.
Commands: `./gradlew clean build verifyOcrSetup`; `./gradlew evaluate -PevalManifest=eval/cases.tsv` (and `eval-local/cases.tsv`);
`./gradlew ctcProbe -PevalManifest=M` (per-region output and crop hashes); cross-check: `crossCheckDump` + `tools/xcheck/*.py`
(see Phase 3c/3d); candidate trial: see Phase 3e.

## Phase 4: persistent projects, multiple pages, text export (2026-11-22)
Starting checkpoint verified: `main` = `origin/main` = `5fb6e8f`, clean tree. No roadmap document defines Phase 4 (HANDOFF/README
only describe Phases 0-3), so the scope below is the agreed scope. OCR models, decoding, preprocessing and defaults are untouched.

### Scope (agreed)
Create/save/open a project; import several images as pages; prepare and recognize each page; edit text with raw OCR preserved;
save, close and reopen without losing anything; export the reviewed text. No batch OCR, no PDF/DOCX export, no autosave, no
automatic correction of any kind.

### Where state lived before (and now)
Page-level: working image (decoded original + prep state), rendered view, regions, raw OCR, edited transcript, selection/crop draft.
Project-level: OCR sessions, job lock/generation. Before: one image, original file not retained, job guard = (generation, image
revision) only. Now: `PageData` owns the original bytes, prep state, raw/edited text, recognized flag, regions and OCR identity;
`ProjectData` is the ordered page list; `ProjectSession` holds the active project, its path, dirty state and an epoch; only the ACTIVE
page is decoded (inactive pages are compressed bytes + a small thumbnail).

### Project file format (`.nbai`, format version 1)
ZIP archive, written/read with `java.util.zip` (no new dependency, no native deserialization). Exactly these entries:
- `project.json` (UTF-8, strict JSON via the in-repo `MiniJson`; duplicate keys, trailing data, raw control chars, depth > 64 rejected;
  unpaired surrogates are escaped so any Java string round-trips exactly). Fields: `format`="notebook-ai-project",
  `formatVersion`=1, `writtenBy`, `projectId`, `pages[]`.
- `pages/<pageId>/original`, one per page = the ORIGINAL JPEG/PNG bytes (stored uncompressed in the zip).
Page object: `id` (stable UUID), `name` (file name only), `image` (entry), `imageFormat`, `imageBytes`, `imageSha256`, `prep`
{`quarterTurnsCw`, `fineDegrees`, `crop`|null}, `recognized` (bool), `rawText` (null iff not recognized; "" = recognized, no text),
`editedText`, `ocrIdentity` (model name + det/rec sha256 prefixes + score mode + drop score + any experiment; no paths),
`regionsPrepKey`, `regions[]` (polygon, box, text, detScore, recScore).
The WORKING image is not stored: it is regenerated from original + prep by the same deterministic code (rotate steps, straighten,
crop). Region overlays are persisted and restored only if their prep key matches; otherwise they are dropped with a visible notice
("Extract again to inspect regions") and never shown stale. Selection rectangles, crop drafts, selected region, review results and
running jobs are transient: never saved, never an edit.
No secrets, models or absolute paths are written (the test suite asserts the JSON contains no absolute path).

### Safe persistence
Save/Save As: temp sibling in the target folder (default-umask permissions; existing target permissions are preserved), fsync of the
file, re-read + full validation (hashes, structure, page ids) of what was written, then atomic rename over the target (non-atomic
`REPLACE_EXISTING` fallback where atomic move is unsupported). Any failure deletes the temp file and keeps the previous valid file.
Limitation: the containing directory is not fsynced and OS crash/power loss during the rename is outside what this guarantees; this is
not crash-durable storage. Open: everything is parsed and validated first; the active project is replaced only on full success
(`ProjectSession.open`/`adopt`). Validation: entry names must match a fixed pattern, no duplicates/directories/extras/orphans, every
referenced image present with matching size and SHA-256, JPEG/PNG magic, header-only pixel-count check (no decode), schema checks
(types, ranges, prep, recognized/raw consistency). Nothing is extracted to disk. Unsupported versions give "written by a newer
version (format vN; this version reads up to v1)". Bounds: 500 pages, 64 MiB per image, 32 MiB `project.json`, 2 GiB total
expanded, 120 megapixels per image, 10,000 regions per page (also enforced when saving).

### Workflow and policies
- **Add Images…** (multi-select) always ADDS pages (replaces the old "Open Image…"); New/Open replace the project. Invalid files are
  skipped with a per-file reason; valid ones are added.
- Policy during an OCR job, import or project open: page switching, add/remove/reorder, New and Open are LOCKED; Save and Export stay
  available (they read a consistent snapshot). Independently every OCR result carries a `JobTicket` (project epoch, page id, image
  prep revision, job generation) and is applied by page id only if all still match, so a result can never land on another page/project.
- Dirty tracking (`DirtyTracker`): meaningful changes (import/remove/reorder, prep, OCR results, transcript edits) mark the project
  unsaved; navigation, copy, region selection, export do not; programmatic refreshes are guarded by `loadingPage`. Saves are
  asynchronous from a snapshot; only the snapshot's revision is marked saved, so edits made during a save stay unsaved.
  New/Open/Quit offer Save / Discard / Cancel; Cancel, a cancelled Save As or a failed save never proceed.
- Prep changes still clear the page's OCR results and transcript (with the existing confirmation when it has edits), now per page.

### Text export
UTF-8, no BOM, via temp file + rename (permissions as above), rejecting text that cannot be encoded rather than altering it.
Current page: its edited text, verbatim. All pages: project order, exactly one blank line (`\n\n`) between consecutive pages, always
(so positions are preserved); optional `--- Page N ---` labels only when ticked. Unrecognized pages and recognized-but-empty pages
contribute empty text and are listed in a warning before export. Export never changes the project or its saved state. Overwrite
confirmation is the native chooser's; cancel and write failures leave an existing file unchanged.

### Privacy
A `.nbai` contains full user images and text; exports contain text. `*.nbai`, `projects-local/`, `exports-local/` are git-ignored.
No private images/transcriptions were added; tests use generated images. GUI test data lived in a disposable folder and was deleted.

### Automated tests (112 total, all green; 41 new)
`MiniJsonTest` (exact string round trip incl. lone surrogates, malformed input, depth), `ProjectStoreTest` (multi-page round trip with
exact Unicode/CRLF/trailing-space text, images, prep, regions, order and stable ids, recognized-empty vs unrecognized, owned images
and no absolute paths, failed save keeps previous file and removes the temp, invalid content, failed open leaves session unchanged,
corrupt/truncated/empty files, missing/bad JSON, bad UTF-8, wrong format, newer/invalid versions, unsafe/duplicate/orphan entries,
tampered image, invalid prep/state, stale-region drop, pixel bomb at save and load, too many entries/pages, oversized JSON, bytes
decode == file decode), `ProjectWorkflowTest` (dirty transitions incl. edit-during-save, session epoch and snapshot independence,
open swap-on-success, page order/identity, `JobTicket` acceptance matrix, raw vs edited, action locking, export order/separators/
labels/warnings/verbatim UTF-8/no BOM/failure cases, permissions, permission-error wording). Not tested for size: the 64 MiB / 2 GiB
byte bounds are enforced in code but exercised only through the 32 MiB JSON, entry-count and pixel limits.

### Installed-GUI acceptance (real mouse/keyboard on a private Xvfb against the `~/.local` build, jar `93006246b8a2` = staged)
Performed and passed: add 2 images in one multi-select; prepare page 1 (rotate) and page 2 (crop) differently and switch; extract
each; edit each (incl. `é`) and switch back and forth with independent state; Copy Text (clipboard compared); Restore Raw (page-
local); reorder and Export All (order, separator, UTF-8 bytes); Export Current incl. overwrite Cancel/Replace and a read-only-folder
failure; export warnings for an unrecognized page and the opt-in labels; Save (Untitled -> Save As), title/`*` transitions; no dirty
on navigation/copy/region select/selection rectangle; unsaved dialog Cancel; Discard; Save-then-proceed and a cancelled Save As not
proceeding; Save As to a second file (first file byte-identical); failed save (read-only folder: clear message, `*` remains, no temp
file); failed open (corrupt file: actionable message, project unchanged); quit with unsaved changes (Discard); reopen after the source
folder was moved (pages, order, prep, regions, thumbnails, exact text; stored originals byte-identical to the source files); remove
page (Cancel / OK, then dirty); mid-OCR lock (page list, Add/Extract/prep disabled, New/Open disabled, click on another page had no
effect) with the result landing on the page it started on (approved fixture photo: baseline transcript).
Fixes found by that pass (all re-verified): temp files were owner-only (now umask/existing permissions), status/controls stayed
greyed behind failure dialogs, raw I/O messages for permission errors and missing import files.
NOT exercised in the GUI: straighten and region click/copy/insert on the new multi-page screens (code paths unchanged from Phase 2,
covered there and by unit tests), an actual OCR result racing a page switch (prevented by the lock; the ticket logic is unit-tested
only), edits typed while an asynchronous save is in flight (unit-tested only), closing the window while an OCR job runs, and very
large projects (hundreds of pages / near the byte limits).

### Regression
Default pipeline unchanged: repo photo CER 12/67 WER 5/13; private photo CER 5/91 WER 3/18; rendered print exact (verified by running
the manifests). `verifyOcrSetup` ok. Installed identity: the status line shows `~/.local/lib/notebook-ai/lib/notebook-ai-0.1.0.jar`.

### Build note
`build/install` had been created root-owned (by an earlier `sudo make install` run), which made `gradlew clean` fail for the unprivileged
user. It was renamed aside to `build-old-rootowned/` (excluded via `.git/info/exclude`, nothing deleted); remove it with
`sudo rm -rf build-old-rootowned` if desired. Avoid running `make install` as root after a root-owned `build/` appears.

### Remaining gaps
No crash-durability guarantee; no autosave; no batch OCR; no PDF/DOCX; no multi-user locking of a project file; no undo of page
removal beyond not saving; thumbnails are regenerated on open (a render per page); project files can be large (all original images).
Phase 4 status: **Accepted** (required acceptance checks passed; unverified paths are listed above).

## Phase 5: reliability, hardening, release verification (2026-11-22) — PLAN (written before implementation)
Checkpoint: `main` = `origin/main` = `042d267` (Phase 4 accepted; its three commits were reviewed — no private data, binaries,
models or paths — and pushed without force). Phase 5 commits stay local. Preserved, not touched: `build-old-rootowned/` (+ its root-
owned leftover and its `.git/info/exclude` line), the `cand` trial-evidence folder, private evaluation data, user projects/exports.
Priority order: data loss / corruption / unsafe filesystem behaviour / stuck-application defects first, cosmetics last.
Scope and acceptance criteria:
1. **Background-job lifecycle.** Every worker (OCR, import, project open, save) must always deliver exactly one completion to the UI
   thread, even for `Error`s (OOM, linkage), so the application can never stay busy. Acceptance: deterministic tests with injected
   executors for success / exception / Error / ordering; controls restored in all cases.
2. **Shutdown policy.** Native inference cannot be interrupted safely, so closing while a task runs = "wait, then ask": the window
   closes after the task finishes, unsaved-change prompts preserved; no cancellation is claimed; OCR sessions are closed in `stop()`.
   Acceptance: pure policy tests + real-GUI checks of close during OCR, during save and with unsaved changes.
3. **Saving while state changes.** Save-then-proceed must not discard edits made after the saved snapshot. Acceptance: threaded test
   with a latch inside the save, out-of-order completion test, real-GUI check with a large project.
4. **Reconstruction integrity.** Working image rebuilt from original + prep must equal the interactive working image (dimensions and
   pixels) for combined sequences; saved crops/regions that do not fit the rebuilt image are discarded visibly, never shown stale.
5. **Resource limits.** Make limits configurable for tests; define what each measures; boundary tests at +-1; a decoded-memory guard
   that refuses an image BEFORE a dangerous allocation; bounded generated-image stress test with honest scale.
6. **Build/install safeguards.** Gradle-calling `make` targets refuse to run as root; `make install` never builds; actionable messages;
   verified with a temporary user-owned prefix. No sudo, no change to `/usr/local`.
7. **Preserve OCR behaviour** (models, decoder, experiments off, preprocessing): verified by the manifests, not assumed.
8. **Installed-app release verification** on a private display, final docs, release assessment (no tag/release/installer).

### Phase 5 results (2026-11-21, Linux only; Windows/macOS NOT tested)
Defects found by inspection, fixed, and covered by tests (cause -> fix):
- Workers caught only `Exception`; an `Error` (OOM) left the app busy/saving forever -> `BackgroundTasks` (Throwable-safe, exactly one completion).
- Save-then-New/Open/Quit discarded edits typed during the async save -> `ShutdownPolicy.mayProceedAfterSave` + re-prompt. GUI-verified.
- Close during OCR/import/open closed at once (daemon threads, sessions never closed) -> "wait, then ask" policy; `stop()` closes OCR sessions. GUI-verified (close during OCR, with Cancel and Discard).
- Saved crop/regions that do not fit the rebuilt frame were silently clamped -> `PageRestore` discards them with a notice.
- A 120 MP image could imply >1 GB decoded -> `MemoryGuard` (~12 B/px vs 70% of max heap) refuses BEFORE decoding (import and page activation).
- Gradle-calling make targets could run as root (cause: `sudo make build`, from the journal) -> `check-not-root`; `make install` never builds.
Limits (`ProjectStore.Limits`): maxImageBytes = one original image (entry uncompressed size); maxJsonBytes = expanded project.json;
maxTotalBytes = archive file size AND sum of expanded bytes read; maxPixels = header w*h before any decode; pages/regions are counts.
Defaults: see the `MAX_*` constants in `ProjectStore`. Boundaries tested exactly (+-1) with tiny limits; default maxima themselves were NOT stress-tested.
Tests: 130 JUnit tests pass (`./gradlew clean build`); `tools/verify-install.sh` 18/18 (temporary user-owned prefix, no sudo, no /usr/local).
Stress (generated non-private images, installed app, Xvfb): 30 x 12 MP JPEG import 3.1 s; 45 + 180 page switches: RSS 0.6 -> 2.0-2.5 GB
peak (HWM 2.59 GB), Java heap after GC stayed 0.18-0.46 GB (RSS is mostly freed-but-unreturned heap and native memory; not measured exactly);
48 MP page opens; 150 MP image rejected with a clear message; 10 extractions on 1500x700 pages fine; 16 x 12 MP random-noise project saved to a
186 MB file in ~2 s with an edit made during the save (kept unsaved, file held the snapshot). Inactive pages are not kept decoded
(thumbnails only); only the active page is decoded. Measurement limit: RSS sampling, no native memory tracking. 4 GB-RAM-class machine assumed;
projects approaching the 2 GiB total limit were NOT tested.
OCR baselines re-measured after all changes (unchanged): repo photo CER 12/67, WER 5/13; private photo CER 5/91, WER 3/18; print exact.
Doubled-extension observation: see the Phase 5 closure section (cause established there; the earlier remark attributing it to the dialog was not evidence-based).
(The earlier 'unverified / acceptance pending' notes here are superseded by the closure section below.)

## Phase 5 acceptance closure (2026-11-21)
Checkpoint verified: `main`, local HEAD `e00c284`, `origin/main` `042d267` (ancestor of HEAD, no divergence), clean tree, five unpushed Phase 5 commits.
Evidence kinds are kept separate below: **[GUI]** = observed in the installed app on a private Xvfb display with xdotool/xclip, **[AUTO]** = automated tests, **[INSPECT]** = code reading.

Installed build tested: `~/.local/lib/notebook-ai/lib/notebook-ai-0.1.0.jar`, sha256 prefix `b53fab85634553fc` for the GUI checks (identical to the staged `build/install` jar at that time).
After the checks, `make build` + `make install` were re-run from the same clean tree (no source change); the rebuilt jar has a different hash (`cf2349eb20abd4f0`, jar rebuild is not bit-for-bit
reproducible; I did not diff the contents) and the final smoke check (open project, page select, Extract-again confirmation, Cancel, Quit) was repeated on it. The full GUI checks below were NOT repeated on the rebuilt jar.
Disposable generated images (`small1..5.png`, different texts, no private data). Page names below use the file names.

| Check | Method | Result |
|---|---|---|
| A. independent preparation [GUI] | 5 pages; page `small5`: Right turn then straighten (slider, Apply) = 6.0 deg; `small4`: Left turn, 0 deg; 3 round trips between them, screenshots compared | each page kept its own image, orientation and straighten value; no leakage. Crop in the GUI was not exercised (crop sequences are [AUTO] only). |
| B. region copy / insert [GUI] | Extract `small3`, `small2`; select a region; Copy Region with a sentinel placed on the clipboard first; Insert at Caret at position 0 (page 2) and end (page 3) | `xclip -o` returned exactly `Page 2 total 12.50` and `Quick brown fox` (first attempt missed the button; the sentinel survived, confirming the check reads the real clipboard). Insert changed only the intended page at the caret; the other page stayed unedited; raw OCR in the saved file is unchanged |
| C. reorder + export [GUI] | moved a page to position 1 with the up button; Export All Pages without and with the label option; bytes read back | exact UTF-8, project order, `\n\n` separators, empty text for unrecognized pages, `--- Page N ---` labels when ticked (verified byte-for-byte). Export left the title clean (`all changes saved`) after a save. |
| D. save / close / reopen [GUI] | Save, Project>Quit, relaunch, Open, view each page, inspect `project.json` | order, orientations, 6.0 deg straighten, regions on both recognized pages, recognized/edited status restored; raw and edited text distinct and exact; per-page preparation distinct. Pre-close captures were not taken (scripting error), so this compares against earlier observed state, not a pixel diff; pixel-exact reconstruction is [AUTO] |
| E. filename [GUI + probe] | Save with no extension, Save As with `.nbai`, select-all before typing; plus a standalone JavaFX probe with the same filter | no extension -> `x.nbai`; `x.nbai` stays single. The doubled `.txt.txt`/`.nbai.nbai` seen earlier appears only when typing after Ctrl+L WITHOUT replacing the pre-filled name's extension; reproduced with a standalone probe that has `setInitialFileName("...txt")` (returned `probe3.txt.txt`) and NOT without it. App code writes exactly the chosen path and appends `.nbai` only if missing. No code change. |
Defects found in closure: none. Note: stray keystrokes while no dialog was open once activated a focused button (reordered a page) — a test-method slip, not an app defect.
Automated: `./gradlew clean build` 130 tests, 0 failures; `verifyOcrSetup` OK; `tools/verify-install.sh` ALL CHECKS PASSED (temporary prefix, no sudo).
OCR re-measured at closure: repo photo CER 12 edits (0.179) WER 5 edits; private photo CER 5 edits (0.055) WER 3 edits; rendered print exact. Unchanged.
Limitations kept explicit (not verified support): `BackgroundTasks` guarantees a completion is delivered after an `Error`, not recovery from every out-of-memory or fatal runtime condition (JVM/native state may be unusable);
stress test observed about 2.6 GB peak RSS and post-GC Java heap about 0.18-0.46 GB; full default maximum project sizes were not stress-tested; privileged system install (`sudo make install`) was not exercised;
Windows and macOS were not tested; no crash/power-loss durability claim; a GUI crop sequence and the close-during-open/import/save-with-wait paths were [GUI]-checked only for OCR and save (open/import not separately).
Phase 5 status: **Accepted for the documented scope** (Linux only; this is closure of the roadmap, not proof of perfect recognition or platform coverage). No tag, release or installer.

## Cross-platform installation and command setup (2026-11-24)
Starting checkpoint verified: `main` = `origin/main` = `abfc7ff`, clean tree (the reported `aab63ce` was the commit before the docs push).
Unpushed work: none at the start. Scope: `install.sh`, `install.cmd`, a maintained Makefile, a working `notebook-ai` command on Linux and in Windows CMD. No packaging framework, no bundled JRE, no Java auto-install.

### Findings that shaped the design (all established, not assumed)
- The Windows launcher generated by Gradle was **unusable**: `build.gradle` pinned `*-linux.jar` JavaFX artifacts, so `notebook-ai.bat` referenced Linux jars. ONNX Runtime 1.22.0 already bundles `win-x64` natives, so only JavaFX needed a classifier.
- Models resolve from `NOTEBOOK_AI_MODELS` or `<cwd>/models`; a launcher must therefore set the variable (the app then starts from any directory).
- The old `make install` layout (`<prefix>/lib/notebook-ai/{bin,lib,models}`) conflicts with the requested default (`~/.local/share/notebook-ai`); both were reconciled by an upgrade plan (legacy detection, never auto-migrated, explicit `--replace-legacy`).
- Java: required 21+; verified with 21 and 25 on Linux (the GUI was launched through the installed command under Java 25 with `JAVA_HOME` unset).

### What was implemented
- `build.gradle`: `-PtargetPlatform=linux-x64|win-x64` (default = host; others rejected), classifier parameterisation, start-script surgery parameterised, `PLATFORM` file in the distribution, non-host targets staged in `build/install-<platform>/`.
- `install.sh` (Linux) and `install.cmd` + `tools/install-windows.ps1` (Windows): per-user default, custom dirs, `--prefix/--destdir` (Linux), prerequisite validation before modification, staged copy + per-file SHA-256 verification + swap + rollback, ownership markers, `--status`, `--uninstall`, shadow/PATH reporting, legacy detection, refusal as root/Administrator, opt-in `--build`.
- Windows PATH: registry value read unexpanded, appended once, kind preserved, broadcast, never `setx`, only with consent (`--add-to-path`/prompt), uninstall removes only an entry this installer recorded.
- `tools/download-models.ps1`: Windows counterpart of the model script (same pinned commits and committed checksums).
- `Makefile`: dispatcher (build/models/test/verify/install/uninstall/status/clean/run/recognize/test-install/system-deps), explicit environment detection and support matrix, root guard preserved (+ Administrator guard on Windows), values passed via environment.
- `tools/verify-install.sh` (145 checks) and `tools/verify-install-windows.ps1` (114 checks + 4 Windows-only SKIPs on Linux); `.github/workflows/installers.yml` (not run).
- Docs: README (quick start, install/upgrade/uninstall, Makefile matrix), `installation.md` (rewritten), `fde.md` §13.

### Defects found by the new tests and fixed
1. `install.sh` missed a lone other-OS JavaFX jar (single `ls` with two globs exits non-zero when either glob is unmatched) -> checks each pattern separately.
2. My own removal instruction for old-layout installs was circular (`make uninstall PREFIX=…` now refuses unmarked directories) -> explicit `--uninstall --prefix P --replace-legacy` limited to the exact old structure; message and tests added.
3. PowerShell helper embedded backslashes in `Join-Path` (correct only on Windows, untestable under pwsh on Linux) -> `PJ` helper joins components one at a time.

### Verification actually executed (Linux host, 2026-11-24)
- `./gradlew clean build`: 130 unit tests, 0 failures. `./gradlew verifyOcrSetup`: OK.
- `make build`, `make build PLATFORM=win-x64`: both distributions staged; `PLATFORM` files `linux-x64` / `win-x64`; the Windows `.bat` references `-win.jar` and keeps CRLF; Windows DLLs are inside the JavaFX win jars.
- `sh tools/verify-install.sh`: 145 passed, 0 failed (temporary HOME/PATH only; no sudo; real `~/.local` and `/usr/local` untouched).
- `pwsh tools/verify-install-windows.ps1`: 114 passed, 0 failed, 4 skipped (needs Windows). This is PowerShell **on Linux**.
- Real staged Linux build installed into a temporary HOME: all 13 files byte-identical to the staged tree; installed jar sha256 equal; app launched through the installed command from `/` under a private display with `NOTEBOOK_AI_MODELS` set to the installed models; headless OCR through the installed jars/models from `/`.
- Real cross-staged Windows distribution + real models installed through the PowerShell logic in test mode (file-backed PATH): layout correct, PATH entry appended with existing entries untouched, tamper detected by `--status`.
- OCR regressions (defaults unchanged): repo photo CER 12 edits (0.179) WER 5 edits (0.385); private photo CER 5 edits (0.055) WER 3 edits (0.167); rendered print exact (the print fixture was **regenerated** because the earlier scratch file was gone; same text/font style, not byte-identical to the earlier one).

### Windows: verified vs unverified
Verified (under PowerShell on Linux): option parsing and exit codes, path validation table, PATH add/remove/dedupe/quote refusal, Java version parsing, wrapper text properties (CRLF, marker, ASCII, no absolute paths), fresh install, upgrade with stale-file removal, PATH ownership state, refusals leaving the install byte-identical, failure/rollback via seams, uninstall preservation, spaces/non-ASCII paths, platform mismatch, elevated refusal.
**Not verified:** anything executed by cmd.exe (`install.cmd`, `notebook-ai.cmd`, argument forwarding, exit codes), registry PATH write/broadcast, real Windows file locks, the Windows build (`gradlew.bat`), the application on Windows, GNU Make on Windows, non-ASCII paths under real code pages. **Windows is implemented, not accepted.**

### Remaining risks / next steps
- Run `.github/workflows/installers.yml` (or the Windows suite by hand) on real Windows; then launch the installed app and repeat a GUI smoke test there.
- `sudo sh install.sh --prefix /usr/local` was not exercised (no sudo allowed).
- Pre-existing old-layout installations on the development machine were deliberately left untouched; the per-user default installs alongside them and reports them.

## Release staging directories linux-dist/ and win-dist/ (2026-11-24)
Starting checkpoint verified: `main` at `bb60a79`, 4 commits ahead of `origin/main` (`abfc7ff`), clean tree. Nothing pushed, tagged or published.

### Findings that shaped the design
- The installed tree was 172 MB because the ONNX Runtime jar (133 MB) bundles natives for **every** OS; a "target-platform only" distribution therefore requires repackaging that jar (done; Linux dist 47 MB, Windows 111 MB).
- The JavaFX 21.0.7 Maven jars contain **no license text**; ONNX Runtime's jar ships only `ThirdPartyNotices.txt`; xmpcore ships nothing. OpenJFX legal files were fetched once from the `openjdk/jfx21u` repository (commit `dd5bd597...`) and committed under `packaging/legal/` with provenance.
- Models: Apache-2.0 per the model repositories' metadata (no LICENSE file in them) + upstream PaddleOCR license in `third-party/`; bundled, with the basis recorded as a known gap.
- No JRE is bundled and no existing runtime-packaging implementation exists; Java 21+ is a documented prerequisite.
- Gradle's generated start script needs `xargs` (found by a minimal-PATH test); documented.

### What was implemented
- Gradle: per-target configurations (Linux/Windows resolved side by side), `trimOnnxRuntime<T>`, `startScripts<T>`, `dist<T>`/`dist`, `verifyDist<T>`/`verifyDist`, `package<T>`/`package` (ZIP in `release-output/` + `.sha256`), `cleanDist`. Staged in `build/dist-staging/`, validated, then swapped in;
  an existing directory without our `distribution.json`, or with unlisted files, is never touched. Line endings normalised during staging. Default build unchanged.
- Installers: packaged mode (SHA256SUMS verified first, platform from `distribution.json`, only `bin lib PLATFORM` copied, legal documents to `<app>/legal/`, `--build` refused). Shared launcher bodies `packaging/launcher-body.{sh,cmd}` now drive the installed command AND `run-notebook-ai.*` (previously the logic was embedded in the installers).
- `notebook-ai --self-check` (headless; Java, JavaFX modules, ONNX natives, models) in `Main`; this is the only application-code change (OCR code, models and defaults untouched).
- Make targets: `dist-linux dist-windows dist verify-dist package package-linux package-windows dist-clean test-dist` (thin dispatch; root guard applies).
- Generated content: `distribution.json`, `SHA256SUMS`, `README.txt`, `THIRD-PARTY-NOTICES.txt`, `licenses/`.
- Tests: `tools/test-dist.sh` (50), `tools/test-dist-build.sh` (16), `tools/test-dist-windows.ps1` (34 + 5 Windows-only SKIPs), `tools/verify-install.sh` extended (156), CI workflow extended (not run).

### Defects found by the new checks and fixed
1. The validator rejected `install.sh` for its relative source-checkout fallback string -> build-directory needles now apply outside the three dual-mode installer files (absolute developer paths are forbidden everywhere).
2. `packaging/uninstall.cmd` had LF endings in the working copy -> staging normalises CRLF/LF instead of trusting the checkout.
3. Test-environment findings: `xargs` prerequisite; a UTF-8 locale is needed for the window title (`LANG=C.UTF-8` in the GUI test; under a bare C locale the X window is named `java`, cosmetic).

### Licensing / shipping blockers (explicit)
- ONNX Runtime LICENSE (MIT) text not obtained. xmpcore BSD-3 copyright notice not obtained. OpenJFX texts are from a branch snapshot, not the 21.0.7 tag. Model licensing relies on repository metadata. **No distribution is called ready to ship until these are resolved or consciously accepted.**
- The Windows distribution has been assembled, statically verified and its packaged-mode PowerShell installer logic exercised under pwsh on Linux; cmd.exe, `run-notebook-ai.cmd`, registry PATH and the application on Windows were **never executed**.

### Verified on the Linux host
Generation of both directories and archives; archive structure (single top-level directory, exec bits, normalised timestamps); extraction into a path with spaces; SHA256SUMS; metadata vs payload; no embedded developer paths; headless `--self-check` from the extracted archive and from the installed copy; real OCR through the installed trimmed ONNX Runtime; GUI window launched from the extracted archive under a private Xvfb display;
install, deletion of the extracted folder, upgrade, uninstall, tamper refusal; generator safeguards incl. mutation checks. Re-packaging an unchanged directory produced identical bytes once; reproducible builds are not claimed.
