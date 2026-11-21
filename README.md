# notebook-ai (Java desktop — projects, pages, review + OCR, text export)

Offline handwritten-text OCR: open an image → prepare (rotate/straighten/crop)
→ extract real text → review regions → edit → copy. Pure Java 21, JavaFX UI,
ONNX Runtime CPU, bundled-local PP-OCRv6_small models. No Python, no server,
no network at runtime.

## What works
- `./gradlew recognizeImage -Pimage=/path/to.jpg` — CLI: regions with
  boxes + detector/recognition scores, combined text, stage timings.
  Optional `-PdebugDir=/tmp/ocr-debug` writes overlay + crop images there.
- `./gradlew run` — JavaFX window: image prep, page extract, selection
  OCR, single-line OCR, region review with overlay, verbatim transcript
  editing, copy, raw restore. JavaFX loads as named modules (no startup
  warning on the pinned JDK 21 runtime).
- `./gradlew verifyOcrSetup` — Phase 0 gate: sessions load, I/O printed.
- `./gradlew evaluate -PevalManifest=eval/cases.tsv` — accuracy harness:
  verbatim CER/WER + line structure + timings per case. Private cases go
  in `eval-local/cases.tsv` (same format, git-ignored, never committed).
- `./gradlew clean build` — compiles + runs the JUnit suite.

## Projects, pages and export (Phase 4)
- A **project** is an ordered list of **pages**. **Add Images…** (multi-select) adds JPG/PNG files as
  pages; it never replaces the project. Each page keeps its own preparation (rotate/straighten/crop),
  raw OCR, regions, and edited transcript. Switch pages in the left list; **Remove** (with
  confirmation) and **▲/▼** reorder. Only the active page is decoded in memory.
- **Project → New / Open… / Save / Save As…** use one portable file, `.nbai` (a ZIP: `project.json`
  + the ORIGINAL image bytes of every page). Reopening never depends on the source image files.
  Saving writes a temporary sibling file, re-validates it, then renames it over the target, so a
  failed save keeps the previous file; opening is all-or-nothing (a corrupt file never replaces
  the open project). Format details and limits: HANDOFF.md "Phase 4".
- The title shows `*` and the status line says UNSAVED CHANGES after page import/removal/reordering,
  image preparation, OCR results and transcript edits. Navigating, copying and selecting regions
  do not. New/Open/Quit ask Save / Discard / Cancel when there are unsaved changes.
- While an OCR job, import or open runs, switching pages and New/Open are locked and every OCR
  result is checked against its project, page and image state before it is stored.
- **Export → Current Page / All Pages as Text…** writes the CURRENT reviewed transcripts verbatim
  as UTF-8 (no BOM). All pages: project order, one blank line between pages (page labels only if you
  tick the option). Unrecognized or empty pages contribute empty text and are listed in a warning.
- **Privacy:** a `.nbai` file contains your full images and your text, and exports contain your
  text. Treat them as private; they are never written anywhere you did not choose, and `*.nbai`
  is git-ignored in this repository.

## Image preparation and coordinates
- Rotate left/right (90° steps), straighten slider (±10°) + Apply, reset
  to original; rectangular crop selection with apply/clear. The source file
  is never modified; an original + working image is kept (no edit history).
- Working-image pixels, origin top-left. Preview uses contain fit; mouse
  selection inverts it (letterbox excluded) and clips to bounds; tiny
  drags are treated as clicks, not regions.
- Changing the working image clears obsolete results (asks first if the
  transcript has unsaved edits).

## Region review
- Extract Page lists regions in reading order and draws their outlines on
  the preview. Clicking a list row (or a region on the image) shows its raw
  text, detector score (box quality) and recognition score (mean kept-char
  confidence — not a correctness probability), plus the exact recognizer crop.
- Recognize Selection runs detector + recognizer on the dragged crop;
  Recognize Line runs the recognizer directly on it (for single lines).
  Selection results appear in a separate area — never spliced into the
  transcript. Copy or insert-at-caret are explicit actions.
- Transcript stays the primary editor; raw OCR output is immutable and can
  be restored with confirmation. Digits, signs, units, exponents and line
  breaks pass through verbatim — nothing is solved, rewritten or guessed.

## Setup
```sh
sh tools/download-models.sh   # models + sha256sum verification
./gradlew clean build
```

## Safe build/install (Phase 5)
Build as your normal user, install with the privilege the prefix needs. Build targets refuse to run as root; `make install` only copies the staged build.
```sh
make build models                      # normal user, never sudo
make install PREFIX="$HOME/.local"     # user-local, no sudo
sudo make install                      # optional system-wide; copy only
sh tools/verify-install.sh             # checks the above in a temporary prefix
```
Tested on Linux only. Closing the window while OCR/import/open/save runs waits for it (nothing is cancelled). Project limits: see HANDOFF.md.

## Install globally (Makefile)```sh
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
- Real handwriting (2026-11-21): 3-line repo sample CER 0.179 / WER 0.308;
  4-line private sample (`~/Desktop/test.jpg`, not committed) CER 0.055 /
  WER 0.167. Single-page evidence only — broader coverage needs more pages
  with exact transcriptions (contributions welcome: drop them in
  `eval-local/` with a `cases.tsv` entry).
- CPU only, sequential single-image inference, no batching optimization.
- Debug overlays/crops are opt-in and never written into the source tree.

## Docs
`MODEL_MANIFEST.md` (sources, files, checksums, verified processing) ·
`HANDOFF.md` (this phase: work, results, blockers, next task). The previous
React Native/Android implementation is preserved under the
`archive/android-rn-phase0` tag.
