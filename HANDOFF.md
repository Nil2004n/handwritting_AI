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
1. Real handwriting accuracy UNVERIFIED — no handwriting samples exist;
   next phase should add some and compare against references.
2. Reading order follows upstream `sorted_boxes` on quad point 0; on
   strongly rotated quads the displayed `box` y0 can differ from the sort
   key — same as upstream, by design.
3. CPU-only, sequential; no performance optimization attempted.

## Next task (Phase 2)
Image review + numericals: region review UI with source-image context,
per-region correction, and worked-calculation transcription using the
`samples/transcriptions/*.txt` references. Do NOT claim handwriting
accuracy until measured on real handwriting.

## Commit
Phase 1 commit on `main` (see `git log`). Models stay git-ignored;
no push.
