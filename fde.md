# fde.md — Full Design & Engineering reference for notebook-ai

> **What this file is.** A detailed design-and-engineering reference: architecture, the OCR pipeline, the project
> file format, the concurrency and safety model, the build and install system, the verification evidence and its
> limits. It is written for maintainers and reviewers. (The name "fde" was interpreted as "Full Design &
> Engineering"; if you meant something else, tell me and I will reshape it.)
>
> Companion docs: [`README.md`](README.md) (user guide) · [`installation.md`](installation.md) (setup) ·
> [`HANDOFF.md`](HANDOFF.md) (phase-by-phase history and evidence) · [`MODEL_MANIFEST.md`](MODEL_MANIFEST.md) (models).
>
> **Evidence labels used throughout.**
> **[AUTO]** = covered by automated tests · **[GUI]** = observed in the installed app on a private display ·
> **[INSPECT]** = established by reading code · **[UNVERIFIED]** = not tested, no claim made.

---

## Contents

1. [Goals, non-goals, constraints](#1-goals-non-goals-constraints)
2. [System overview](#2-system-overview)
3. [Technology stack and pinned versions](#3-technology-stack-and-pinned-versions)
4. [Source tree map](#4-source-tree-map)
5. [OCR pipeline in detail](#5-ocr-pipeline-in-detail)
6. [Image preparation model](#6-image-preparation-model)
7. [Project model and the `.nbai` format](#7-project-model-and-the-nbai-format)
8. [Persistence algorithm and integrity rules](#8-persistence-algorithm-and-integrity-rules)
9. [Concurrency, job ownership and shutdown](#9-concurrency-job-ownership-and-shutdown)
10. [UI architecture](#10-ui-architecture)
11. [Text export](#11-text-export)
12. [Resource model and limits](#12-resource-model-and-limits)
13. [Build system, packaging and install safeguards](#13-build-system-packaging-and-install-safeguards)
14. [Testing strategy and evidence](#14-testing-strategy-and-evidence)
15. [Accuracy evidence and evaluation harness](#15-accuracy-evidence-and-evaluation-harness)
16. [Security and privacy model](#16-security-and-privacy-model)
17. [Defects found and fixed (Phase 5)](#17-defects-found-and-fixed-phase-5)
18. [Design decisions and trade-offs](#18-design-decisions-and-trade-offs)
19. [Known limitations and unverified paths](#19-known-limitations-and-unverified-paths)
20. [Extension guide](#20-extension-guide)
21. [Glossary](#21-glossary)

---

## 1. Goals, non-goals, constraints

**Goals**
1. Fully **offline** handwritten-text OCR on a desktop, with a fast review/correct workflow.
2. **Never lose or corrupt user data**: atomic saves, all-or-nothing opens, no silent discarding of edits.
3. **Honest output**: raw model output is immutable and separate from the user's edited text; nothing is autocorrected,
   solved, or rewritten.
4. Multi-page projects in a single portable file whose reopening does not depend on the source images.
5. Reproducible, verifiable builds: pinned dependencies, pinned model commits, checksum-enforced model files.

**Non-goals (explicitly out of scope)**
- Cloud or networked recognition; GPU acceleration; batching optimisations.
- Table, formula, or mathematical-expression understanding; multi-column layout analysis.
- PDF/DOCX import or export; autosave; multi-user locking; undo history.
- Windows/macOS support claims (not tested).

**Hard constraints**
- Java 21, JavaFX 21, ONNX Runtime (CPU), no Python or native build steps at runtime.
- No network access at runtime; models are fetched only by the build-time script.
- Native ONNX inference is **not interruptible** — the design must not claim cancellation.

---

## 2. System overview

```text
                       ┌──────────────────────────── JavaFX UI (NotebookAiApp) ───────────────────────────┐
 user ── files ──►     │ page list · preview+overlay · region list · transcript editor · menus (Project/Export) │
                       └───────┬───────────────────────────────┬───────────────────────────┬──────────────┘
                               │ commands (FX thread)          │ snapshots                 │ results (FX thread)
                               ▼                               ▼                           ▲
                   ┌──────────────────────┐        ┌──────────────────────┐     ┌──────────────────────┐
                   │ ProjectSession       │        │ BackgroundTasks      │────►│ one completion per    │
                   │  ProjectData/PageData│        │ (worker thread)      │     │ task (even on Error)  │
                   │  DirtyTracker        │        └─────────┬────────────┘     └──────────────────────┘
                   │  JobTicket / epoch   │                  │
                   └─────────┬────────────┘     ┌────────────┴─────────────┐
                             │                  ▼                          ▼
                             │          ┌──────────────┐          ┌─────────────────┐
                             │          │ OcrService   │          │ ProjectStore    │
                             │          │ det + rec    │          │ AtomicFiles     │
                             │          │ ONNX Runtime │          │ MiniJson / ZIP  │
                             │          └──────────────┘          └─────────────────┘
                             ▼
                      WorkingImage (original BGR + turns + fine° + crop)  ──render──►  Rendered BGR
```

Layering rule: `ocr` and `project` contain **no JavaFX**; they are unit-testable headless and are reused by the CLI
tasks. `ui` is the only package that touches JavaFX. `model` holds UI-independent state (working image, control
enable rules).

---

## 3. Technology stack and pinned versions

| Component | Version | Role |
|---|---|---|
| Java | 21 (Gradle toolchain) | language/runtime |
| Gradle wrapper | 8.14.x | build; must itself run on Java ≤ 24 |
| JavaFX | 21.0.7 (`-linux` classifier jars) | UI; loaded as **named modules** on the module path, app stays non-modular |
| ONNX Runtime Java | 1.22.0 | CPU inference (includes linux-x64 natives) |
| metadata-extractor | 2.19.0 | JPEG EXIF orientation |
| JUnit Jupiter | 5.12.2 (+ platform launcher 1.12.2) | tests (launcher version aligned deliberately) |
| Detector model | PP-OCRv6_small DB detector (`det.onnx`, 2.48 M params) | text regions |
| Recognizer model | PP-OCRv6_small CTC recognizer (`rec.onnx`, 5.2 M params) | text lines |

Model provenance: official PaddlePaddle ONNX exports on Hugging Face, **pinned by commit SHA** in
`tools/download-models.sh`, integrity enforced by `models/checksums.sha256`. The recognizer decode table is
`["blank"] + 18,708 dictionary entries + [" "]` = **18,710 classes** (verified; index 18709 is the space character).

JavaFX packaging detail: the `*-linux.jar` artifacts carry the real `module-info`; unclassified jars are empty
placeholders. `build.gradle` therefore splits the classpath: JavaFX jars go on `--module-path` with
`--add-modules javafx.controls`; everything else stays on the classpath. The `startScripts` task is post-processed to
do the same split for the staged launchers, and **asserts** its text anchors so a future Gradle template change fails the
build loudly instead of silently shipping JavaFX on the classpath. Headless tasks (tests, `verifyOcrSetup`,
`recognizeImage`, `evaluate`) use the plain classpath and never load JavaFX.

---

## 4. Source tree map

```text
notebookai.ocr        CropOps · CtcDecoder · CtcProbe · CrossCheckDump · DbPostprocess · DebugImages · DebugTrace
                      DetPreprocess · Experiments · ImageOps · OcrException · OcrService · OcrSetup · OcrSetupCheck
                      ReadingOrder · RecognizeCli · RecPreprocess
notebookai.model      ControlState · ModelConfig · OcrResult · WorkingImage
notebookai.project    AtomicFiles · BackgroundTasks · DirtyTracker · JobTicket · MemoryGuard · MiniJson · PageData
                      PageRestore · ProjectActions · ProjectData · ProjectException · ProjectSession · ProjectStore
                      RegionData · ShutdownPolicy · TextExport
notebookai.eval       Alignment · EditDistance · Evaluate
notebookai.ui         Main · NotebookAiApp
```

Responsibilities in one line each:

| Class | Responsibility |
|---|---|
| `OcrService` | owns both ONNX sessions; `recognize(...)`, `recognizeLine(...)`; identity string; `close()` |
| `DetPreprocess` / `RecPreprocess` | resize/normalise inputs exactly as the model YAML specifies |
| `DbPostprocess` | probability map → polygons (thresholding, contours, unclip, SLOW scoring) |
| `CtcDecoder` | greedy CTC decode (default); beam decode only behind an experiment flag |
| `ReadingOrder` | tilt-aware line grouping and ordering of regions |
| `ImageOps` | decode (true BGR), EXIF orientation, rot90, bicubic rotation, header peek |
| `WorkingImage` | original + preparation state; `render()`, `restore()`, `frameSize()` |
| `PageData` / `ProjectData` | in-memory project model (mutable, single-threaded by contract) |
| `ProjectSession` | active project, path, dirty tracker, epoch, save snapshot/job |
| `DirtyTracker` | revision/savedRevision logic; save tokens |
| `JobTicket` | ownership of a background OCR job |
| `ProjectStore` | `.nbai` read/write/validate, limits |
| `AtomicFiles` | temp-sibling write, permission handling, IO error descriptions |
| `MiniJson` | strict JSON parser/writer (handles lone surrogates) |
| `PageRestore` | rebuild a page's working image; discard geometry that no longer fits |
| `BackgroundTasks` | run work off the UI thread; exactly one completion for every outcome |
| `ShutdownPolicy` | pure decision table for "close requested" and save-then-proceed |
| `MemoryGuard` | refuse decoding before allocation if it cannot fit |
| `ProjectActions` / `ControlState` | pure enable/disable rules for menus and buttons |
| `TextExport` | verbatim UTF-8 export, labels, warnings |
| `NotebookAiApp` | all UI wiring |

---

## 5. OCR pipeline in detail

Pipeline for `OcrService.recognize(bgr, width, height, debugDir)`:

```text
decoded image (true BGR, EXIF-oriented)
  └─► DetPreprocess: limit_type=min, limit_side_len=736 (upscale if min side < 736, per det.yml pairing),
                     max_side_limit=4000, each side snapped to a multiple of 32, normalise, NCHW float
        └─► detector (ONNX)  → probability map
              └─► DbPostprocess: thresh 0.2 · box_thresh 0.45 · unclip_ratio 1.4 · max_candidates 3000
                                 score mode SLOW (polygon-masked mean), neighbour-padding trim
                    └─► regions (quads) ─► ReadingOrder (tilt-aware rows/lines)
                          └─► CropOps: perspective-crop each quad (upstream unclip padding by default)
                                └─► RecPreprocess: height 48, width from aspect ratio (base 320/48), normalise
                                      └─► recognizer (ONNX) → logits [T × 18710]
                                            └─► CtcDecoder (greedy argmax, collapse repeats, drop blank)
                                                  └─► keep lines with score ≥ DROP_SCORE (0.5)
```

Key facts and decisions:

- **Colour order.** Images are decoded as true BGR to match the upstream OpenCV pipeline; this was a corrected
  defect in an earlier phase (a channel-order mismatch skews normalisation).
- **Detector sizing.** The values come from the model YAML pairing, *not* the tools-CLI defaults (960/max), which differ.
- **DB postprocess.** Faithful to upstream: threshold map → connected contours → minimum-area rectangles →
  polygon score (SLOW mode) → unclip by ratio 1.4 → re-score/filter. A neighbour-padding trim was added to match
  upstream box geometry. A cross-check harness (`tools/xcheck`, Python, optional) compared intermediate stages against the
  reference implementation during development; it is **not** part of the shipped runtime.
- **Reading order.** Upstream sorts by the first corner's y, which mis-orders lines on tilted pages. `ReadingOrder`
  projects regions into the dominant text frame (u along text, v across), clusters rows by v, orders by v then u, and
  splits a row into display lines when the gap along u exceeds one line thickness; gaps up to 0.6 × the taller
  neighbour's padded thickness are treated as word gaps and joined with a space. **Region results are never merged
  or edited** — only grouped for display.
- **Recognition scores.** `detScore` = box quality; `recScore` = mean confidence over kept characters. Neither is a
  probability of correctness; the UI says so.
- **Recognizer crop.** The exact crop passed to the recogniser is shown in the review panel so a wrong read can be
  diagnosed visually.
- **Selection OCR.** *Recognize Selection* = full detector+recogniser on the dragged rectangle; *Recognize Line* =
  recogniser straight on the crop (assumes one line).
- **Opt-in experiments** (`Experiments`): `-Dnotebookai.exp.cropPad` (0..1, default 1.0 = upstream padding) and
  `-Dnotebookai.exp.beam` (default 0 = greedy). **Defaults are OFF.** The active set is part of `identity()` so an
  experimental result cannot pass as production. They exist for controlled studies; they are never chosen by file name,
  hash or ground truth.
- **No hard-coded corrections.** There is no lexicon, grammar fixer, expected-output lookup or post-hoc rewriting of text.

Output type: `OcrResult` (regions with polygon, box, text, scores; combined text; timings). Debug overlays/crops are
written **only** when a debug directory is passed explicitly, and never into the source tree.

---

## 6. Image preparation model

`WorkingImage` keeps the **oriented original** (BGR bytes + width/height) and three parameters:

- `quarterTurnsCw` ∈ {0,1,2,3}
- `fineDegrees` ∈ [−10, +10]
- `crop` = `[x0,y0,x1,y1]` in the rotated/straightened frame, or `null`

`render()`: apply 90° turns (lossless `rot90`) → `rotateBicubic(fine)` about the centre with white fill → crop (clamped).

Frame size after straighten (mirrored exactly by `WorkingImage.frameSize`, so validation needs no pixel work):

```text
(w,h) = swapped if odd quarter turns
nw = max(ceil(|w·cosθ| + |h·sinθ|), 1)
nh = max(ceil(|w·sinθ| + |h·cosθ|), 1)
```

Invariants:
- A turn or a straighten **clears** the crop (its rectangle belonged to the previous frame). So a persisted crop
  always postdates turns and straighten, which makes **rebuild from original + (turns, fine, crop)** deterministic.
- Every mutation bumps `revision`; it is the page's *prep revision* used by `JobTicket`.
- `restore(original, w, h, turns, fine, crop)` applies operations in that fixed order.
- `PageRestore.restore(...)` validates a saved crop against `frameSize` and saved region polygons/boxes against the rebuilt
  image (1 px tolerance). Failing geometry is **dropped with a user-visible notice**, text is kept. Stale geometry is
  never drawn. **[AUTO]**

Coordinates: working-image pixels, origin top-left. Display mapping inverts a "contain" fit (uniform scale, centred,
letterbox excluded), clips to bounds, rejects selections smaller than `MIN_SELECT` = 8 px.

Evidence: combined sequences (rotate→crop→straighten→crop; crop→rotate→reset→crop; repeated straighten; straighten→crop;
different sequences per page) rebuild **bit-exactly** after save/reopen **[AUTO]**; `frameSize` equals `render()` output
dimensions for all turn × angle combinations tested **[AUTO]**. In the GUI, per-page rotation + straighten survived
save/reopen and page switching **[GUI]**; a GUI crop sequence was **[UNVERIFIED]** beyond the automated tests.

---

## 7. Project model and the `.nbai` format

### In-memory model

```text
ProjectData { projectId, List<PageData> }
PageData    { id, name, original bytes, sha256,
              quarterTurnsCw, fineDegrees, crop?,
              recognized, rawText?  (null iff not recognized),
              editedText, ocrIdentity, List<RegionData>, regionsPrepKey }
RegionData  { polygon[4][2], box[4], text, detScore, recScore }
```

Semantic rule: `recognized == true` with `rawText == ""` means "recognized, found nothing"; `recognized == false` means
"not processed yet". They are distinct everywhere (UI, export warnings, file).

### File format (version 1)

`*.nbai` is a ZIP archive containing only:

```text
project.json
pages/<id>/original          ← one entry per page; <id> matches [A-Za-z0-9_-]{1,64}
```

`project.json` (strict JSON, UTF-8):

```json
{
  "format": "notebook-ai-project",
  "formatVersion": 1,
  "writtenBy": "notebook-ai",
  "projectId": "…",
  "pages": [
    {
      "id": "…", "name": "small3.png",
      "image": "pages/<id>/original", "imageFormat": "png|jpeg",
      "imageBytes": 12345, "imageSha256": "<hex>",
      "prep": { "quarterTurnsCw": 1, "fineDegrees": 5.96774193548387, "crop": null },
      "recognized": true,
      "rawText": "…", "editedText": "…", "ocrIdentity": "…",
      "regionsPrepKey": "…",
      "regions": [ { "polygon": [[x,y],…], "box": [x0,y0,x1,y1], "text": "…", "detScore": 0.95, "recScore": 1.0 } ]
    }
  ]
}
```

Why a ZIP of originals: reopening never depends on external files; the image bytes are exactly what the user added; the
hash lets corruption/tampering be detected.

### Validation on load (all enforced; any failure rejects the whole file)

- `format` must equal `notebook-ai-project`; `formatVersion` newer than 1 → explicit "this version reads up to v1" refusal.
- Entry names must match the fixed pattern; no unexpected entries; no duplicates; every page's image entry present.
- Per-image SHA-256 and byte count must match; header dimensions must be valid and within the pixel limit.
- Prep values within range (turns 0–3, |fine| ≤ 10, crop ordered and non-negative); region polygons/boxes finite and sane.
- Strings length-bounded where applicable (page names ≤ 255 chars); counts bounded (pages, regions per page).
- Size limits (see §12) apply to the archive, each expanded entry, `project.json`, and cumulative expanded bytes.
- Truncated, junk, empty and tampered archives are rejected **[AUTO]**. A failed open leaves the current project
  object identical and still dirty/clean as before **[AUTO]**.

`MiniJson` is a strict, dependency-free JSON implementation (no comments/trailing commas, bounded depth) that
round-trips lone UTF-16 surrogates so arbitrary transcript text survives save→load. **[AUTO]**

---

## 8. Persistence algorithm and integrity rules

**Save** (`ProjectStore.save`):

1. UI thread takes a **snapshot** (`ProjectSession.beginSave`): a deep copy of the project plus a token = the current
   revision. Later edits mutate the live project, not the snapshot.
2. Worker: build the archive into a **temporary sibling file** in the target directory (permissions: umask-derived for
   new files; the existing file's permissions preserved when overwriting).
3. Flush/fsync the temp file.
4. **Re-read and fully validate** the temp file with the same loader used for opening.
5. Atomic rename over the target.
6. Any failure → temp file removed, previous target untouched, `ProjectException` with a human-readable message
   (`AtomicFiles.describe` maps IO errors such as no space / read-only / permission to actionable text).
7. On success the UI calls `session.saveSucceeded(token)`.

**Dirty tracking** (`DirtyTracker`): `revision` increments on every modification; `savedRevision` records the last
saved snapshot revision. `saveSucceeded(token)` sets `savedRevision = max(savedRevision, token)`, so an **older save
that completes late can neither clear newer edits nor regress newer saved state**. `isDirty = revision != savedRevision`.
**[AUTO]** including out-of-order completion and a threaded test that edits the project *inside* an in-flight save
(latched at the point between validation and rename).

**Save-then-proceed**: after *Save* in the unsaved prompt, New/Open/Quit proceed only if
`ShutdownPolicy.mayProceedAfterSave(saveOk, stillDirty)` — i.e. nothing new is unsaved. Otherwise the user is told
the project changed during the save and is prompted again. **[AUTO]** + **[GUI]** (typing during a save of a 186 MB
project; the app did not quit, re-prompted, then quit cleanly after a second save; the saved file held the snapshot text).

**What is *not* claimed:** crash or power-loss durability. The directory entry is not separately fsynced, and
filesystem/hardware behaviour is out of scope. **[UNVERIFIED]**

---

## 9. Concurrency, job ownership and shutdown

### Threads

- **FX thread:** all UI and all mutation of `ProjectData`/`PageData`/`WorkingImage` (single-writer rule).
- **Worker threads** (daemon, via `BackgroundTasks`): OCR inference, project open/parse, saves (operating on an
  immutable snapshot), import decoding, thumbnail generation (read-only on page bytes, epoch-checked).

### `BackgroundTasks`

```text
run(Callable<T> work, Consumer<Outcome<T>> onUi)
  worker.execute( try { value = work.call() } catch (Throwable t) { error = describe(t) }
                  ui.execute( once-only: onUi.accept(Outcome(value, error)) ) )
```

Properties **[AUTO]** with injected deterministic executors (no sleeps): success; every failure class
(`ProjectException`, `OcrException`, `IllegalStateException`, `OutOfMemoryError`, `LinkageError`, `StackOverflowError`)
yields **exactly one** completion; the completion runs on the UI executor, never on the worker; completion order follows
submission order on an ordered UI executor.

Limit of the guarantee: it ensures the *UI state machine* is released. It does **not** promise that the JVM or native
runtime is healthy after an out-of-memory or native fault. **[UNVERIFIED]** beyond the described tests.

### Job ownership (`JobTicket`)

An OCR job captures `(projectEpoch, pageId, prepRevision, generation)`. Its result is applied only if the project epoch is
unchanged, the page still exists **and is the active page**, the page's prep revision is unchanged and no newer job
started. Otherwise the status line says "Ignored stale result". New/Open increments the epoch. **[AUTO]**

### UI locks

While a job runs: page switching, New/Open, Add Images and Extract are disabled; the controls come back in a `finally`
path after every outcome (including dialogs shown on failure, which refresh controls *before* the modal opens so the
window is never left greyed). **[GUI]**

### Shutdown policy (`ShutdownPolicy.onCloseRequest(taskRunning, saving, dirty)`)

| taskRunning | saving | dirty | Action |
|---|---|---|---|
| any true | | | `WAIT_FOR_BACKGROUND` → dialog "Close when finished / Keep working"; nothing is cancelled; closes automatically afterwards (the unsaved prompt then still applies) |
| false | false | true | `PROMPT_UNSAVED` (Save / Discard / Cancel) |
| false | false | false | `CLOSE_NOW` |

`stop()` closes the shared `OcrService` (ONNX sessions) on the way out. **[GUI]**: closing during OCR → dialog → OCR result
lands → unsaved prompt → Cancel returns to normal with controls restored; Discard exits with no stray process; closing
during a save never produces a partial project file (temp-then-rename).

**Why wait instead of cancel:** ONNX Runtime native inference cannot be interrupted safely; killing threads risks
corrupted native state and partial writes. Waiting is the only honest policy.

---

## 10. UI architecture

`NotebookAiApp` is the single UI class. State: `session` (project + dirty tracking), `activePageId`, `working`
(the active page's `WorkingImage`), per-page thumbnails, busy flags (`jobRunning`, `saving`, `closing`, `closeRequested`),
`loadingPage` (suppresses false-dirty while a page is being loaded into controls).

- **Only the active page is decoded.** Switching decodes the target page (memory-guarded, may discard unusable saved
  geometry with a notice); thumbnails are produced by a background loader and are never used as the source of truth.
- **`updateControls()`** derives every enable/disable from a pure `ControlState` + `ProjectActions` (unit-tested), so
  rules (e.g. "Extract needs an active, unlocked page", "Restore Raw needs recognized text") live in one place.
- **Menus:** Project (New `Ctrl+N`, Open `Ctrl+O`, Save `Ctrl+S`, Save As `Ctrl+Shift+S`, Quit); Export (Current Page / All
  Pages as Text…).
- **Prompts:** Save/Discard/Cancel for unsaved changes; confirmation for Remove, Restore Raw, extracting over edits,
  operations that clear results over edited text.
- **File names:** Save As appends `.nbai` only if the chosen name lacks it. **Observation resolved:** a doubled extension
  (`x.nbai.nbai`, `x.txt.txt`) appears only when the user types after the dialog pre-filled a name and leaves the pre-filled
  extension in place. Reproduced with a standalone JavaFX probe (initial file name set → chooser returned `…probe3.txt.txt`; no
  initial name → correct). With select-all before typing, a name without extension became `x.nbai` and `x.nbai` stayed
  single. **[GUI]**. The app writes exactly the path the chooser returns; no code change was needed.

---

## 11. Text export

`TextExport`:
- `pages(project)` builds `(number, edited text, recognized?)` rows in project order.
- `renderOne` = the page's edited text verbatim. `renderAll(pages, labels)` = page texts joined by `"\n\n"`; with labels each
  page is prefixed by `--- Page N ---\n`.
- `warnings(pages)` lists unrecognized or empty pages ("exported as empty text").
- `writeUtf8(path, text)` writes UTF-8 **without BOM** via `AtomicFiles`.
- Exports never change the project's dirty state. **[GUI]**: five pages, mixed recognized/unrecognized, non-ASCII text
  (`é ½ — 漢`), with and without labels, byte-compared; page order followed reordering.

---

## 12. Resource model and limits

### What each project limit measures (`ProjectStore.Limits`, defaults)

| Limit | Default | Measures | When enforced |
|---|---|---|---|
| `maxPages` | 500 | page count | save and load |
| `maxImageBytes` | 64 MiB | one original image: expanded entry size | save, load (header and read) |
| `maxJsonBytes` | 32 MiB | expanded `project.json` | load (bounded read) and save |
| `maxTotalBytes` | 2 GiB | archive file size **and** cumulative expanded bytes read | load; save |
| `maxPixels` | 120,000,000 | header width × height | load/import **before** decoding |
| `maxRegionsPerPage` | 10,000 | region count | save and load |

Limits are a record, so tests use tiny values to check **exact ±1 boundaries** (N accepted, N−1 rejected) on both save
and load **[AUTO]**. The *default* maxima under real load are **[UNVERIFIED]**.

### Memory

- Decoded RGB working copies dominate memory. `MemoryGuard.check(w, h, maxHeap)` refuses when
  `w·h·12 B > 0.7·maxHeap` (≈ four live 3-byte-per-pixel buffers during decode/prepare/render). It is an estimate; native
  inference memory is separate and uncounted. Applied on import and on page activation; thumbnails use the same check.
- `OutOfMemoryError` during a background task is reported as an actionable message and releases the UI; during page
  activation it is caught and reported rather than leaving a half-loaded page.

### Measured (generated non-private images; Linux; Xvfb; ≈ 4 GB-class machine) **[GUI]**

| Scenario | Result |
|---|---|
| Import 30 × 12 MP JPEGs | 3.1 s |
| ≈ 225 page switches | RSS 0.6 GB → peak **≈ 2.6 GB** (HWM 2.59 GB); Java heap after GC **0.18–0.46 GB** |
| 48 MP page | opens |
| 150 MP image | rejected: "unreadable or larger than 120 megapixels" |
| 10 extractions on 1500×700 pages | fine, ≈ 0.3–0.5 s each |
| 16 × 12 MP noise images → project | 186 MB file saved in ≈ 2 s |

Interpretation notes: RSS far above live heap is dominated by freed-but-not-returned heap regions and native memory
(ONNX/JavaFX/image buffers). Native memory tracking was **not** enabled, so that split is an **inference**, not a
measurement.

---

## 13. Build system, packaging and install safeguards

### Gradle: explicit target platform

`build.gradle` selects the JavaFX native classifier from an explicit **target platform**: `linux-x64` → `linux`, `win-x64` → `win`.
Default = the build host; `-PtargetPlatform=win-x64` stages a Windows distribution from Linux (output `build/install-win-x64/notebook-ai`; a non-host
target never overwrites `build/install/notebook-ai`). Anything else (macOS, ARM, 32-bit) fails configuration with a clear message. ONNX Runtime 1.22.0
bundles `win-x64` and `linux-x64` natives in one jar (verified by listing it), so only JavaFX needs the classifier. The module-path surgery on the start
scripts is parameterised by the classifier, and `installDist` writes `PLATFORM` (`platform=…`, versions) into the tree so the installers can reject a wrong-OS build.
Before this change the generated `notebook-ai.bat` pointed at `*-linux.jar` files, i.e. the Windows launcher was unusable. **[INSPECT]** + staged output inspected: the
Windows `.bat` now references `javafx-*-win.jar`, CRLF endings kept, no Linux jars in the tree, Windows DLLs present in the JavaFX jars.

### Installers (the only install logic)

`install.sh` (POSIX sh) and `install.cmd` → `tools/install-windows.ps1` implement validation, staging, swap/rollback, ownership markers, status and uninstall. The Makefile
only dispatches. Layout (both OSes): `<APP>/{.notebook-ai-install, INSTALL.info, MANIFEST.sha256, dist/, models/}` (+ `bin/notebook-ai.cmd`, `path-state.txt` on Windows).

Algorithm (both): validate distribution (PLATFORM matches host, one `notebook-ai-*.jar`, ONNX Runtime jar, JavaFX jars of the right OS and none of another) → validate models against
the committed `checksums.sha256` → Java ≥ 21 → ownership decisions on existing targets (owned marker / legacy / foreign) → copy to `<parent>/.notebook-ai.installing.<pid>` → hash every
copied file against the source → write manifest + info + launcher candidate → move existing app to `.previous.<pid>` → move new app in → write the command → delete previous.
Any failure after the swap restores the previous app. Temp names are installer-created and cleaned only when they carry the marker.

Target validation: absolute, no `.`/`..`, not `/`, HOME or ancestors, `~/.local`, Desktop/Downloads/Documents, system directories, the repository or its parent; command dir not inside the app dir and
vice versa; symlinked app directories refused; newline-containing paths refused. Per-user install refused as root/Administrator; `--build` refused as root/Administrator.

Windows PATH: registry value read with `DoNotExpandEnvironmentNames`, appended once, kind preserved, `WM_SETTINGCHANGE` broadcast, never `setx`; entry recorded in `path-state.txt` and removed on uninstall only if recorded.

### Makefile

Dispatcher with an explicit support matrix (`NBAI_HOST`, `NBAI_ENV_ERROR`): GNU Make 4.x on Linux tested; Windows/cmd.exe dispatch implemented and untested; MSYS2/Git Bash/Cygwin/NMake/macOS refused.
Gradle-calling targets depend on `check-not-root` (`CURRENT_UID`, overridable only for tests). `install/uninstall/status` only call the installer. Custom directories travel through the environment
(`"$$APP_DIR"`), so spaces are safe.

### Root-ownership incident (the reason for the guards)

Journal evidence: `sudo make install` → `sudo make build` → `sudo make install`; the middle command ran Gradle as root, creating root-owned `build/install` in the repository. Remedy at the time: renamed,
not deleted, git-excluded locally. Safeguards: root-refusal on all Gradle/download targets and `--build`, copy-only installers, actionable messages. No recursive chown or chmod 777 is ever recommended.

### Release distributions (linux-dist/, win-dist/)

Generated by Gradle (`distLinux`, `distWindows`, `dist`; `packageLinux`, `packageWindows`, `package`; `verifyDist`; `cleanDist`), so the logic is portable and Make stays a dispatcher.
Each target has its **own resolvable configuration** (JavaFX classifier jars with `transitive=false`, ONNX Runtime, metadata-extractor + xmpcore), so both distributions are produced in
one invocation from any host; the global `-PtargetPlatform` switch of `installDist` is untouched. Steps: ONNX Runtime jar repackaged with only the target's `ai/onnxruntime/native/<os-arch>/`
(133 MB -> 8 MB on Linux; `ThirdPartyNotices.txt` inside the jar is preserved and shipped); start scripts created per target with the same module-path surgery; staged in
`build/dist-staging/<dir>.new`; models verified against `models/checksums.sha256`; installer scripts **copied** from the maintained sources (`install.sh`, `install.cmd`, `tools/install-windows.ps1`) with
line endings normalised (LF/CRLF); the launcher body (`packaging/launcher-body.{sh,cmd}`) is a **single source** used by the installed command and by `run-notebook-ai.*`; READMEs/notices from templates;
`distribution.json`; `SHA256SUMS` (all files except itself, sorted); then `validateDist` (required files, checksums both ways, metadata vs payload, platform-only jars, exactly one ONNX native set, no forbidden paths, no
developer-machine paths, start-script module path, CRLF/exec bits, notices mention gaps) **before** anything is replaced. An existing output directory is replaced only if it carries our `distribution.json` and contains no file that
`SHA256SUMS` does not list; otherwise the build stops and names the files.

Packaged mode of the installers: if `distribution.json` and `app/bin/notebook-ai[.bat]` sit next to the installer, defaults become `app/` and `app/models/`, `SHA256SUMS` is verified first, the platform in `distribution.json` must match,
`--build` is refused and messages say "extract the archive again". Only `bin`, `lib` and `PLATFORM` are copied from `app/` (never `app/models` into `dist/`), and `LICENSE`, `THIRD-PARTY-NOTICES.txt`, `licenses/` go to `<app>/legal/` (covered by `MANIFEST.sha256`).
`Main --self-check` (headless) verifies Java, the JavaFX modules on the module path, the ONNX natives and the models; it is what the tests execute on the shipped payload.

Evidence: `tools/test-dist.sh` (Linux archive end to end, real GUI launch under Xvfb + xfwm4), `tools/test-dist-build.sh` (generator safeguards incl. mutation checks), `tools/test-dist-windows.ps1` (Windows archive content + packaged-mode helper logic under pwsh;
`[WINDOWS-ONLY]` cmd.exe section never executed). Known licensing gaps are declared in `distribution.json` (`licensingGaps`) and `THIRD-PARTY-NOTICES.txt`.

### Native installers (jpackage)

`gradle/native-installers.gradle` (applied from `build.gradle`; nothing in it runs in the normal build). Inputs are the ZIP pipeline's **validated** payloads (`linux-dist/app`, `win-dist/app`): `stageNativeInput` wipes `build/native/<os>/` and copies the four app jars to `input/`, the three platform JavaFX jars to `content/fx`,
the models to `content/models`, licenses + notices to `content/legal` (outer ZIP wrappers are never copied). `validateNativeInput` then checks exactly what jpackage will receive (jar sets, ONNX natives for one platform, model checksums, legal documents, forbidden paths). Separate directories for
input, content, runtime, temp (`work`) and output (`out`) keep old packages out of new ones; final artifacts go to `release-output/` with SHA-256 sidecars.

*Runtime strategy.* `jlink --add-modules java.base,java.desktop,java.logging,java.xml,java.prefs,jdk.unsupported,jdk.jfr,jdk.charsets` from the packaging JDK's `jmods` (56 MB). jdeps gave `java.base,java.desktop,java.logging,javafx.controls`; JavaFX modules declare `java.xml`, `jdk.unsupported`, (static) `jdk.jfr`;
`jdk.charsets` is defensive (EXIF text). Verified by execution, not by jdeps alone. The runtime's `legal/` directory is required and carried. JavaFX is deliberately **not** linked into the runtime (the Maven jars contain no jmods natives) and is not on the classpath: `--app-content` places it in `lib/fx`, the launcher gets `--module-path $APPDIR/../fx --add-modules javafx.controls`.
The application stays a classpath application. `ModelConfig.modelsDir`: `NOTEBOOK_AI_MODELS` > `-Dnotebookai.models` > `<cwd>/models` (unit-tested).

*Linux.* `jpackage --type deb`, then a repack (still the jpackage package): `/usr/bin/notebook-ai` symlink and a Debian copyright file owned by the package, regenerated `md5sums`, refreshed `Installed-Size`, a read-only shadowing notice and non-fatal desktop-menu calls in the maintainer scripts. The non-fatal change came from the apt test: jpackage's scripts run `xdg-desktop-menu` under `set -e`,
which aborts install and **blocks removal** on systems without menu directories. `Depends` = jpackage's `ldd`-derived list (build-host package names) plus the GTK/GL/X11 libraries found by `ldd` on the native libraries hidden inside the JavaFX and ONNX jars. `verifyDeb` validates metadata, contents, natives, models, launcher configuration, maintainer scripts, md5sums and embedded paths.

*Windows.* `packageExe` (Windows host only; WiX 3.x checked on PATH because JDK 21's jpackage supports WiX 3 only - WiX 4/5 support is JDK 24+, JDK-8319457): `--type exe --win-per-user-install --win-menu --win-shortcut-prompt --win-dir-chooser --win-upgrade-uuid <constant> --add-launcher notebook-ai-cli (win-console=true)`.
The upgrade UUID is a committed constant and must never change. Everything Windows-specific is unexecuted.

*Identity and honesty.* `native-package.json` (inside each package): version, source commit and dirty flag, target, runtime vendor/version/EA flag/modules, licensing state, publisher-configured flag, signing state, `internalBuild` with reasons. Internal builds are allowed; `verifyReleaseLicensing` is the public-release gate.

### Installer test suites

- `tools/verify-install.sh` (156 checks, `make test-install`): runs the real installer, launcher and Makefile in isolated temporary HOME/PATH environments with fixture distributions (stub start script echoing
  arguments and returning a chosen status), fake `java`/`uname`/`id`/`cp`/`mv` for failure injection, plus the real staged build compared file-by-file by SHA-256. **Executed on Linux.**
- `tools/verify-install-windows.ps1` (114 checks + 4 Windows-only SKIPs when run on Linux): pure validation/PATH/Java-parsing/wrapper-text tests, and the whole helper as a child process in test mode
  (file-backed PATH store, platform override, failure seams). The `[WINDOWS-ONLY]` section runs `install.cmd`/`notebook-ai.cmd` under cmd.exe and **has not been executed anywhere**; `.github/workflows/installers.yml`
  would run it on a real Windows runner (also not run).

## 14. Testing strategy and evidence

130 JUnit 5 tests, 0 failures (`./gradlew clean build`) at Phase 5 closure. Test packages mirror source packages:

| Area | Examples of what is pinned down |
|---|---|
| `eval` | edit distance, alignment, CER/WER arithmetic |
| `model` | `WorkingImage` operations, control enable rules, dictionary parsing, `ModelConfig` |
| `ocr` | CTC and beam decoding, detection regression, geometry, end-to-end pipeline, upstream **reference parity**, real-sample regression |
| `project` | strict JSON, store round-trips, malformed-file rejection (versions, hashes, missing/extra entries, bad coordinates, truncation, junk, empty), workflow rules, **limit boundaries**, **reconstruction pixel equality**, **background-task contract**, **shutdown policy**, **save-while-editing (threaded, latched)**, **out-of-order save completion** |

Principles: deterministic synchronisation (latches/manual executors; no sleeps); failure injection (`FailureInjector` hook
inside the save between validation and rename); tiny configurable limits for exact boundary checks; generated images only
(no private data in tests).

### GUI evidence (installed build, private Xvfb display, xdotool + xclip + screenshots)

| Area | Observed |
|---|---|
| Independent per-page preparation | page A right-turn + 6.0° straighten, page B left-turn; stable across repeated switching |
| Region copy / insert | real clipboard read (`xclip -o`) after a sentinel; insert at start and end positions; other page unaffected; raw text unchanged in saved file |
| Reorder + export | position change; exact UTF-8 bytes with and without labels; dirty state unchanged by export |
| Save / reopen | order, preparation, regions, recognized/edited status, raw vs edited text all restored |
| Filename behaviour | see §10 |
| Close during OCR | wait dialog → completion → unsaved prompt → Cancel / Discard |
| Edit during save (186 MB project) | edits remained unsaved; Save-then-quit re-prompted; file held the snapshot |
| Stress | see §12 |

Honest scope of the GUI pass: one machine, one display server, scripted input. Pre-close screenshots for a pixel diff
were **not** captured in the final reopen check, so exactness of reconstruction rests on the automated tests; the GUI
check confirms visible equality only. A few automation slips happened (a stray key press once reordered a page; a first
Copy attempt missed its button) and were recorded rather than hidden.

---

## 15. Accuracy evidence and evaluation harness

`./gradlew evaluate -PevalManifest=<tsv>` prints, per case: verbatim CER and WER (counts and ratios), line counts,
mean line CER, stage timings; plus an aggregate. Manifest line: `image<TAB>rotate-cw<TAB>straighten<TAB>expected`.
Optional `--warmup`/`--repeat` for timing studies.

Re-measured at Phase 5 closure (defaults, experiments off):

| Case | Characters | CER | Words | WER |
|---|---|---|---|---|
| Repository 3-line handwriting sample | 67 | 12 edits = 0.179 | 13 | 5 edits = 0.385 |
| Private 4-line handwriting photo (not committed) | 91 | 5 edits = 0.055 | 18 | 3 edits = 0.167 |
| Rendered English print | — | exact | — | exact |

Caveats: single pages; one writer per sample; no confidence intervals; **not** a benchmark. Hardening work in Phase 5 was
required not to change recognition, and the manifests were re-run to confirm identical results.

---

## 16. Security and privacy model

- **No network at runtime.** The only network use is the build-time download of dependencies and models.
- **Untrusted project files.** Project loading is treated as parsing untrusted input: fixed entry-name pattern (no path
  traversal — entry names never become filesystem paths), hash verification, size and count limits enforced during streaming
  reads (not only from headers), header-based pixel limit before decode, strict JSON, all-or-nothing semantics.
- **File permissions.** Saved files use the user's umask for new files and preserve permissions of an existing file; no
  hard-coded world-readable modes.
- **Data at rest.** `.nbai` holds full images and text. They are not encrypted. Git-ignore rules keep `*.nbai`,
  `projects-local/`, `exports-local/`, `eval-local/` and model binaries out of the repository.
- **Install safety.** Root-refusal for builds; narrow, path-validated deletion during install; no recursive ownership
  changes; no writes outside the chosen prefix.
- **Dependencies.** Pinned versions; model files checksum-verified; no dynamic code download.

Not provided: encryption, signing, sandboxing, malware scanning of images (image decoding uses the JDK and
metadata-extractor; malformed images are bounded by header/pixel/memory checks but decoder vulnerabilities are out of scope).

---

## 17. Defects found and fixed (Phase 5)

Found by **[INSPECT]** first, then pinned by **[AUTO]** tests and/or **[GUI]**:

| # | Defect | Cause | Fix | Evidence |
|---|---|---|---|---|
| 1 | App could stay busy/"saving" forever | workers caught only `Exception`; an `Error` (e.g. OOM) skipped the completion path | `BackgroundTasks` catches `Throwable`, always delivers exactly one completion | AUTO |
| 2 | Typing during an async save was lost on Save→New/Open/Quit | follow-up ran after any successful save, ignoring newer edits | `mayProceedAfterSave` + re-prompt | AUTO + GUI |
| 3 | Closing during OCR/import/open closed at once; sessions never closed | no close policy; no `stop()` | wait-then-ask policy; `stop()` closes `OcrService` | AUTO + GUI |
| 4 | Invalid saved crop/regions silently clamped / drawn stale | no validation against rebuilt frame | `PageRestore` + `frameSize`; discard with notice | AUTO |
| 5 | 120 MP limit could still imply > 1 GB decoded footprint | pixel limit alone ignores heap | `MemoryGuard` before decode (import, activate, thumbnails) | AUTO + GUI (150 MP reject) |
| 6 | Root-owned build artefacts | `sudo make build` ran Gradle as root | `check-not-root`; copy-only install; `verify-install.sh` | script + journal evidence |
| 7 | Stale status text after wait-to-close | status not reset | reset to "Operation finished." | GUI |

Closure checks found **no** further product defects. The doubled-extension observation was investigated and traced to
the pre-filled-name interaction (not app logic); an earlier remark attributing it to the dialog without evidence was
corrected in the documentation.

---

## 18. Design decisions and trade-offs

| Decision | Reason | Trade-off |
|---|---|---|
| Store originals, rebuild working images | smallest file, lossless, deterministic, no stale pixels | rebuild cost on open/switch; larger file than storing only crops |
| ZIP + JSON, not a database | portable, inspectable, easy to validate; no dependencies | whole-file rewrite on each save (~seconds for hundreds of MB) |
| Own minimal JSON | strict control, lone-surrogate fidelity, no dependency | maintenance; must stay strict |
| Snapshot-then-write saves | responsive UI, consistent file, precise dirty semantics | a snapshot copy in memory during the save |
| Wait, don't cancel, on close | native inference is not interruptible | user may wait for a long page |
| Decode only the active page | bounded memory | switching costs a decode (hundreds of ms for 12 MP) |
| Memory guard heuristic (12 B/px, 70 % heap) | prevents a crash before allocation | estimate; may refuse a borderline image or still run out in extreme cases |
| Linux-only classifiers | simplest verified configuration | no Windows/macOS support claim |
| Experiments behind JVM flags, default off | allows study without changing production behaviour | flags are unsupported for normal users |
| Never edit recognised text automatically | honesty; user is the authority on their handwriting | recognition errors remain until corrected by the user |

---

## 19. Known limitations and unverified paths

Verified facts: Linux only; JPG/PNG only; CPU only; single column; single-page accuracy evidence.

Not verified / not claimed:
- Windows, macOS, non-x86-64 Linux; Wayland-specific behaviour.
- `sudo make install` to `/usr/local`; offline transfer of a built tree.
- Crash/power-loss durability; behaviour on network filesystems; extremely long file names; disk-full mid-save beyond the
  tested failure injection.
- Recovery after real JVM out-of-memory or native faults (only the UI-state-release contract is tested).
- Full-scale default limits (≈ 2 GiB, 500 pages) under load.
- GUI crop sequences across save/reopen (automated only); GUI import/open-with-wait paths (OCR and save were GUI-checked).
- Accessibility, high-DPI scaling, localisation.
- Multi-instance use of the same `.nbai` (no locking).

---

## 20. Extension guide

- **Add an input format** (e.g. TIFF/WebP): extend `ImageOps.decode/peekDimensions`, keep header-based limits and the memory
  guard; project format already stores `imageFormat`.
- **Change the project format:** bump `FORMAT_VERSION`, keep readers for old versions, add malformed-file tests, never
  weaken validation. Newer files must continue to be refused cleanly by older readers (already the behaviour).
- **Add an export format:** build on `TextExport.pages`; keep UTF-8/no-BOM rules or document the change; do not alter the
  stored text.
- **Change recognition behaviour:** do it behind an `Experiments`-style flag first, measure with `evaluate` on committed
  and private manifests, and update the evidence in this file; never tune against ground truth, file names, or hashes.
- **New background operation:** run it through `BackgroundTasks`; capture a `JobTicket` if its result targets a page; refresh
  controls in a `finally` path; make sure the close policy still sees `jobRunning`.
- **Port to another OS:** change the JavaFX classifiers in `build.gradle`, review ONNX Runtime natives, replace POSIX-only
  Makefile/scripts, then re-run the whole verification set on that OS before claiming support.

---

## 21. Glossary

- **BGR** — blue-green-red channel order used by OpenCV-style pipelines (and the models' preprocessing).
- **CER / WER** — character / word error rate = edit distance ÷ reference length (verbatim, no normalisation).
- **CTC** — connectionist temporal classification; the recogniser outputs per-timestep class scores that are collapsed into text.
- **DB** — differentiable binarisation text detector.
- **Epoch** — project generation counter; changes on New/Open so late results can be recognised as stale.
- **Prep revision** — a page's working-image mutation counter.
- **Snapshot** — immutable copy of the project taken at the start of a save.
- **Unclip** — dilation of a detected text polygon by a ratio of its area/perimeter before recognition.
- **`.nbai`** — the notebook-ai project file (ZIP: `project.json` + original images).
