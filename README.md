# notebook-ai

**Offline handwritten-text OCR for the desktop.** Open photos or scans of handwritten (or printed) notes,
prepare each image (rotate / straighten / crop), extract the text, review and correct it region by region,
keep many pages together in one portable project file, and export the reviewed text as UTF-8.

Pure **Java 21** + **JavaFX 21** user interface + **ONNX Runtime 1.22 (CPU)** running the bundled
**PaddleOCR PP-OCRv6_small** detector and recognizer. No Python, no server, no GPU, and **no network access
at runtime** — your images and text never leave your machine.

| | |
|---|---|
| Status | Phases 0–5 of the roadmap complete (Phase 5 accepted for the documented scope) |
| Platform verified | **Linux x86-64 only** (Windows and macOS have **not** been tested) |
| Runtime | JDK/JRE 21 (Gradle itself must run on Java ≤ 24; the Makefile pins JDK 21 when present) |
| Licence | BSD 3-Clause (this code); Apache-2.0 (PaddleOCR models, see `third-party/`) |
| Companion docs | [`installation.md`](installation.md) · [`fde.md`](fde.md) · [`HANDOFF.md`](HANDOFF.md) · [`MODEL_MANIFEST.md`](MODEL_MANIFEST.md) |

> **Honest scope statement.** This is a working, tested application, not a promise of perfect recognition.
> Handwriting accuracy depends strongly on the writer and the photo; the measured numbers are in
> [Accuracy](#accuracy-what-has-actually-been-measured). Everything under
> [Known limitations](#known-limitations-and-unverified-paths) is stated deliberately — read it before relying on the tool.

---

## Table of contents

1. [Features](#features)
2. [Quick start](#quick-start)
3. [Using the application](#using-the-application)
4. [Projects, pages and the `.nbai` file](#projects-pages-and-the-nbai-file)
5. [Exporting text](#exporting-text)
6. [Image preparation and coordinates](#image-preparation-and-coordinates)
7. [Region review](#region-review)
8. [Command-line tools (Gradle tasks)](#command-line-tools-gradle-tasks)
9. [Make targets](#make-targets)
10. [Configuration](#configuration)
11. [Reliability behaviour](#reliability-behaviour)
12. [Resource usage and limits](#resource-usage-and-limits)
13. [Accuracy: what has actually been measured](#accuracy-what-has-actually-been-measured)
14. [Testing and verification](#testing-and-verification)
15. [Privacy](#privacy)
16. [Repository layout](#repository-layout)
17. [Known limitations and unverified paths](#known-limitations-and-unverified-paths)
18. [Troubleshooting](#troubleshooting)
19. [Licences and credits](#licences-and-credits)

---

## Features

**Recognition**
- Text detection (DB detector) and recognition (CTC recognizer) with PP-OCRv6_small, both running locally on CPU.
- Regions are returned in **reading order** with box quality and recognition confidence scores.
- *Recognize Selection* (detector + recognizer on a dragged crop) and *Recognize Line* (recognizer only, for a
  single line) for targeted re-reading.
- JPEG **EXIF orientation is applied** so phone photos come out upright.

**Image preparation (non-destructive)**
- Rotate in 90° steps, **straighten** ±10° with a slider, rectangular **crop**, reset to the original.
- The source file is never modified; the original bytes are kept, and the working image is rebuilt from them.

**Multi-page projects**
- Many pages in one project; each page keeps its **own** preparation, raw OCR, regions and edited transcript.
- Add (multi-select), remove (with confirmation), reorder (▲/▼), switch pages freely.
- Only the **active page is decoded** in memory; the rest are thumbnails.

**Review and editing**
- The transcript is a verbatim editor. **Raw OCR output is immutable** and shown separately; *Restore Raw* (with
  confirmation) returns the transcript to it.
- Copy a region or a selection, or *Insert at Caret* into the transcript — explicit actions only; recognition
  results are never silently spliced into your text.
- Digits, signs, units, exponents, Unicode and line breaks pass through verbatim. Nothing is solved, rewritten,
  autocorrected or grammar-fixed.

**Safety**
- Atomic, validated saves (write temp file → flush → re-read and validate → rename). A failed save keeps the previous file.
- All-or-nothing project opening; a corrupt file can never replace the project you have open.
- Unsaved-changes prompts on New / Open / Quit; edits made *while* a save is running are never silently discarded.
- Closing the window during a running operation waits for it (nothing is cancelled or half-written).

**Export**
- Current page or all pages as UTF-8 text, project order, optional `--- Page N ---` labels, with a warning that
  lists pages that contribute empty text.

---

## Quick start

Linux, normal user (no `sudo` for building):

```sh
# 1. Prerequisites: a JDK/JRE 21 and curl (see installation.md for details)
java -version                 # should report 21.x for the runtime you intend to use

# 2. Get the code
git clone https://github.com/Nil2004n/handwritting_AI.git
cd handwritting_AI

# 3. Build and fetch the models (as YOUR USER, never with sudo)
make build models

# 4. Install into your home directory (no root needed)
make install PREFIX="$HOME/.local"

# 5. Run (make sure ~/.local/bin is on your PATH)
notebook-ai
```

Run straight from the source tree instead: `make run` (or `./gradlew run`). The full, step-by-step guide with
verification and troubleshooting is in [`installation.md`](installation.md).

---

## Using the application

A typical session:

1. **Add Images…** — select one or more JPG/PNG files. Each becomes a page. Files that cannot be used (unreadable,
   too large, too many pixels, not enough memory) are skipped and listed in a message; the rest are added.
2. Pick a page in the left list. **Prepare** it if needed: *Left/Right* turn it, move the *Straighten* slider and
   press *Apply Straighten*, enable *Crop mode* and drag a rectangle then *Apply Crop*, or *Reset Working*.
3. **Extract Page** — runs detection + recognition. Regions appear as outlines on the preview and as a list.
4. **Review** — click a list row (or a region on the image) to see its raw text, scores and the exact crop that was
   read. Fix the transcript in the editor at the bottom. Use *Copy Region* / *Insert at Caret* as needed.
5. Repeat for other pages. Reorder with ▲/▼ if you like.
6. **Project → Save** (`Ctrl+S`) to keep everything in one `.nbai` file, and/or **Export → …as Text** for plain text.

Keyboard shortcuts (menu accelerators): `Ctrl+N` New, `Ctrl+O` Open, `Ctrl+S` Save, `Ctrl+Shift+S` Save As.

What the title bar and status line tell you:
- `notebook-ai — name.nbai` : everything saved. `… *` : unsaved changes ("UNSAVED CHANGES" in red on the status line).
- Page import/removal/reordering, preparation changes, OCR results and transcript edits mark the project modified.
  Navigating, selecting regions and copying do **not**.

While an OCR job, import or project open is running, page switching and New/Open are locked and the controls
show a busy state; they are always restored when the job ends, whether it succeeded or failed.

> **Changing the working image** (rotate / straighten / crop / reset) clears the page's previous recognition
> results because their coordinates no longer apply. If the transcript has unsaved edits you are asked first.
> Extracting again on an edited transcript also asks before replacing your edits.

---

## Projects, pages and the `.nbai` file

A project is an ordered list of pages. Each page stores:

| Stored per page | Meaning |
|---|---|
| Original image bytes + SHA-256 | exactly the file you added; integrity-checked on load |
| Preparation | quarter turns (clockwise), fine rotation in degrees, optional crop rectangle |
| Recognized flag | distinguishes **"recognized, but empty"** from **"not recognized yet"** |
| Raw OCR text | immutable model output (absent until recognized) |
| Edited text | your reviewed transcript |
| OCR identity | which model/settings produced the raw text |
| Regions | polygon, box, text, detector score, recognition score |

The file `*.nbai` is a **ZIP archive** with exactly:
- `project.json` — strict JSON describing format, version, pages and their state;
- `pages/<id>/original` — the untouched bytes of each page's source image.

Consequences: reopening never depends on the original image files still existing; the file contains your **full
images and text** (treat it as private); the working image is **rebuilt** from the original + preparation, and any
saved crop or region geometry that does not fit the rebuilt image is **discarded with a notice** rather than shown wrongly.

Format version is `1`. A newer version number is refused with a clear message. Technical details of the format,
validation rules and limits are in [`fde.md`](fde.md).

**Save semantics.** *Save* takes a consistent snapshot on the UI thread and writes it on a background thread, so the
window stays responsive. Only that snapshot's revision is marked saved; anything you type while the save runs stays
unsaved. If you chose *Save* in an unsaved-changes prompt before New/Open/Quit and kept editing during the save, the
follow-up action does **not** proceed — you are asked again.

**Durability.** A save writes a temporary sibling file, flushes it, re-reads and validates it, then renames it over
the target. A failure at any step leaves the previous file untouched and the temporary file removed. This is *not* a
claim of crash- or power-loss durability.

---

## Exporting text

**Export → Current Page as Text…** and **Export → All Pages as Text…** write the *current reviewed transcripts*
(edited text) verbatim:

- UTF-8, **no BOM**.
- All pages: in project order, separated by one blank line (`\n\n` between pages).
- Optional checkbox: prefix each page with `--- Page N ---`.
- Pages that are unrecognized or empty contribute empty text and are named in a warning dialog.
- Exporting does not change the project's saved/dirty state.

---

## Image preparation and coordinates

- Rotate left/right (90°), straighten slider (−10°…+10°) with *Apply*, rectangular crop, reset. Operations apply in
  the only order the UI can produce: turns → straighten → crop. A turn or straighten clears any crop (its rectangle
  lived in the previous frame).
- Coordinates are **working-image pixels**, origin top-left. The preview uses a "contain" fit; mouse selection
  inverts it (letterbox bars excluded), clips to bounds, and treats tiny drags (< 8 px) as clicks.
- Straighten uses bicubic rotation about the centre with a white fill; the canvas grows to fit.

---

## Region review

- *Extract Page* lists regions in reading order and draws their outlines.
- Selecting a region shows its raw text, **detector score** (box quality), **recognition score** (mean kept-character
  confidence — *not* a probability of correctness) and the exact crop passed to the recognizer.
- Selection results appear in a separate area and are never spliced into the transcript automatically.
- The transcript remains the primary editor; raw output stays read-only and restorable.

---

## Command-line tools (Gradle tasks)

All tasks run as your normal user. Add `-Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64` if your default
Java is newer than Gradle supports (the Makefile does this for you when that JDK exists).

| Task | What it does |
|---|---|
| `./gradlew run` | Launch the JavaFX app from the source tree |
| `./gradlew build` / `clean build` | Compile and run the JUnit test suite |
| `./gradlew installDist` | Stage the distribution in `build/install/notebook-ai` |
| `./gradlew verifyOcrSetup` | Load both ONNX sessions, verify checksums, print model I/O |
| `./gradlew recognizeImage -Pimage=/path/to.jpg` | OCR one image: regions, boxes, scores, text, stage timings |
| `… -PdebugDir=/tmp/ocr-debug` | Additionally write overlay + crop debug images there (opt-in) |
| `./gradlew evaluate -PevalManifest=eval/cases.tsv` | Accuracy harness: verbatim CER/WER, line structure, timings |

`evaluate` manifests are TSV: `image<TAB>rotate-cw-steps<TAB>straighten-deg<TAB>expected` with `\n` for newlines.
Private cases go in `eval-local/cases.tsv` (git-ignored; never committed).

---

## Make targets

| Target | Purpose | Notes |
|---|---|---|
| `make build` | Compile and stage the app | **Refuses to run as root** |
| `make models` | Download + checksum-verify models | **Refuses to run as root**; needs network once |
| `make test` | `clean build` incl. unit tests | refuses root |
| `make verify` | `verifyOcrSetup` | refuses root |
| `make recognize IMAGE=…` | OCR one image | refuses root |
| `make run` | Launch the UI | needs a display; refuses root |
| `make clean` | `gradle clean` | refuses root |
| `make install [PREFIX=…]` | **Copy** the staged app, models and launcher | never builds; `sudo` only when the prefix needs it |
| `make uninstall [PREFIX=…]` | Remove installed files | |
| `make system-deps` | `apt-get install openjdk-21-jre` | asks for sudo, Debian/Ubuntu family only |

Why the root refusals exist: running Gradle under `sudo` once created root-owned files inside the repository
(`build/install`), which then broke ordinary builds. See [`installation.md`](installation.md#why-builds-must-not-run-as-root).

---

## Configuration

| Setting | Where | Effect |
|---|---|---|
| `NOTEBOOK_AI_MODELS` | environment | Directory containing `det.onnx`, `det.yml`, `rec.onnx`, `rec.yml`, `checksums.sha256`. The installed launcher sets it to `<prefix>/lib/notebook-ai/models` unless you already set it |
| `JAVA_HOME` | environment | Which Java runs the app; the launcher falls back to `/usr/lib/jvm/java-21-openjdk-amd64` if present and unset |
| `JAVA_OPTS` / `NOTEBOOK_AI_OPTS` | environment | Extra JVM options, e.g. `JAVA_OPTS=-Xmx4g` for very large images |
| `-Dnotebookai.exp.cropPad=<0..1>` | JVM property | **Experiment** (default off = 1.0): recognizer crop padding fraction |
| `-Dnotebookai.exp.beam=<n>` | JVM property | **Experiment** (default off = 0 = greedy): CTC prefix-beam width |

Experiments exist for controlled accuracy studies only. They are **off by default**, never chosen by file name,
image hash or ground truth, and the active set is included in the OCR identity so an experimental run cannot pass for
production output.

---

## Reliability behaviour

- **Background work.** OCR, import, project open and save run off the UI thread through one runner that delivers
  **exactly one completion** to the UI thread for every outcome, including unexpected exceptions and `Error`s
  (such as out-of-memory). The window therefore cannot be left permanently "busy". Recovery from every fatal
  runtime condition is *not* guaranteed — after a JVM out-of-memory, restart the app.
- **Stale results.** Every OCR result is checked against project, page and image revision before it is stored; an
  obsolete result is ignored with a status message.
- **Closing while busy.** Native inference cannot be interrupted safely, so the window **waits**: you are offered
  "Close when finished"; the unsaved-changes prompt still applies afterwards; nothing is cancelled.
- **Memory guard.** Before decoding an image the app estimates the decoded footprint (~12 bytes/pixel) against the
  maximum heap and refuses with an actionable message instead of crashing.

---

## Resource usage and limits

Project-file limits (defaults; checked on both save and load):

| Limit | Default | What it measures |
|---|---|---|
| Pages | 500 | page count |
| Image bytes | 64 MiB | one original image (uncompressed entry size) |
| `project.json` | 32 MiB | expanded JSON |
| Total | 2 GiB | archive file size **and** the sum of expanded bytes actually read |
| Pixels | 120 million | image width × height from the header, checked **before** decoding |
| Regions / page | 10,000 | count |

Measured behaviour (generated, non-private images; Linux; Xvfb; ~4 GB-class machine):

| Scenario | Observation |
|---|---|
| Import 30 × 12 MP JPEG pages | 3.1 s |
| ~225 page switches on that project | RSS rose from ~0.6 GB to a peak of **~2.6 GB**; Java heap after GC stayed **0.18–0.46 GB** |
| 48 MP page | opens |
| 150 MP image | rejected with a clear message |
| 16 × 12 MP noise images saved | 186 MB file, ~2 s, with an edit made during the save (kept unsaved; file held the snapshot) |

Measurement limits: RSS was sampled from `/proc`; native memory tracking was not enabled, so the gap between RSS and
heap is not itemised. **The default maximum project sizes (e.g. near 2 GiB or 500 pages) were not stress-tested.**
Boundary logic is verified exactly (±1) with deliberately tiny limits in automated tests.

---

## Accuracy: what has actually been measured

Re-measured at Phase 5 closure with the shipped defaults (greedy CTC decoding, experiments off):

| Case | CER | WER |
|---|---|---|
| Repository handwriting sample (3 lines, `samples/handwriting-test.jpg`) | 12 edits / 67 chars = **0.179** | 5 / 13 words = **0.385** |
| Private 4-line handwriting photo (not committed) | 5 / 91 = **0.055** | 3 / 18 = **0.167** |
| Rendered English print | **exact** (0 edits) | **exact** |

These are **single-page** data points, not a benchmark. Real handwriting varies widely; expect errors, especially on
unusual hand, low contrast, strong perspective, or non-English/mixed content. Always review the output — that is what
the review workflow is for. Digits and symbols are read as text by the same recogniser; there is no formula or table
understanding.

---

## Testing and verification

```sh
./gradlew clean build              # 130 JUnit tests (as of Phase 5 closure), 0 failures
./gradlew verifyOcrSetup           # model sessions load; checksums enforced
sh tools/verify-install.sh         # install safeguards in a throw-away prefix (18 checks, no sudo)
./gradlew evaluate -PevalManifest=eval/cases.tsv
```

Evidence kinds are kept apart in [`HANDOFF.md`](HANDOFF.md): **automated tests**, **GUI checks** performed on the
installed build under a private display, and **code inspection**. The installed-GUI checks performed were: independent
per-page preparation, region copy/insert with real clipboard reads, reorder + exact export, save/close/reopen, filename
behaviour, close-during-OCR, and edit-during-save. Anything not listed there was not GUI-verified.

---

## Privacy

- No network access at runtime. Models are fetched once at build time by `tools/download-models.sh`.
- A `.nbai` file contains **your full images and text**; exports contain your text. Files are written only where you choose.
- `*.nbai`, `projects-local/`, `exports-local/` and `eval-local/` are git-ignored. Do not commit private data.
- Models and build output are git-ignored as well.

---

## Repository layout

```
src/main/java/notebookai/
  ocr/       OCR pipeline (preprocess, ONNX service, DB postprocess, CTC decode, reading order, CLI, debug)
  model/     working image, model config, UI control state
  project/   project data, .nbai store, saving/dirty tracking, background tasks, shutdown policy, export, memory guard
  eval/      CER/WER, alignment, evaluate task
  ui/        JavaFX application (Main, NotebookAiApp)
src/test/java/…   JUnit 5 tests mirroring the packages
tools/       download-models.sh · verify-install.sh · xcheck/ (Python cross-check, optional)
eval/        committed evaluation manifest      eval-local/  private (git-ignored)
samples/     sample handwriting image and transcriptions
models/      ONNX models + checksums.sha256 (binaries git-ignored)
third-party/ upstream PaddleOCR licence
Makefile · build.gradle · settings.gradle · gradlew
```

A guided tour of the design is in [`fde.md`](fde.md). The earlier React-Native/Android implementation is preserved
under the `archive/android-rn-phase0` tag.

---

## Known limitations and unverified paths

Verified facts:
- Verified on **Linux** only.
- Single-page accuracy evidence only (see above).
- CPU inference, sequential, one image at a time; no batching.
- Single-column reading order; no table, formula, or numerical-expression handling.
- Supported inputs: JPG/JPEG and PNG.

Explicitly **not** verified / **not** claimed:
- Windows and macOS (the build contains Windows launcher generation, but it was never run).
- Privileged system installation (`sudo make install` to `/usr/local`) was not exercised in testing; the copy-only logic was verified in a temporary user-owned prefix.
- Crash or power-loss durability of saves. Atomic rename is used, but fsync of the directory and hardware behaviour are not claimed.
- Recovery from every out-of-memory or fatal runtime error.
- The default maximum project sizes under real load.
- A GUI crop sequence across save/reopen (covered by automated tests, not by a fresh GUI pass).
- No autosave, no undo for page removal beyond not saving, no multi-user locking of a project file, no PDF/DOCX import or export, no batch OCR.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `error: refusing to run 'make …' as root` | Intentional. Run it as your normal user; use `sudo` only for `make install` |
| `staged app missing` on install | Run `make build models` as your user first, then install |
| `models missing or invalid` | `make models` (needs network once); checks use `models/checksums.sha256` |
| `Unsupported class file major version` during build | Gradle on too-new Java; install JDK 21 (`make system-deps`) — the Makefile pins it |
| The command starts an *older* version | Another `notebook-ai` earlier on `PATH`; `make install` prints a WARNING naming it |
| "too large for the available memory" | Use a smaller image, or `JAVA_OPTS=-Xmx4g notebook-ai` |
| A page says "Could not load page" | Memory guard or unreadable image data; the message explains which |
| Save-dialog name ends `.txt.txt` / `.nbai.nbai` | You typed after the pre-filled name left its extension in place; select all (`Ctrl+A`) in the name box before typing |
| Window will not close immediately | An operation is still running; choose *Close when finished* (it cannot be cancelled safely) |

More in [`installation.md`](installation.md#troubleshooting).

---

## Licences and credits

- Application code: BSD 3-Clause, © 2026 Nilotpal Guha (`LICENSE`).
- Models: PaddleOCR PP-OCRv6_small (Apache-2.0), official ONNX exports by PaddlePaddle on Hugging Face, pinned by
  commit and verified by SHA-256 — see `MODEL_MANIFEST.md`; upstream licence in `third-party/PaddleOCR-LICENSE`.
- Libraries: ONNX Runtime (MIT), OpenJFX / JavaFX (GPL v2 + Classpath Exception), metadata-extractor (Apache-2.0),
  JUnit 5 (EPL-2.0, tests only).
