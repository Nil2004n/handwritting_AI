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
9. [Install, upgrade and uninstall](#install-upgrade-and-uninstall) · [Release distributions](#release-distributions-linux-dist--win-dist)
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

Per-user installation; **no `sudo` / Administrator rights**. Build and install are separate steps.

### Linux (x86-64) — tested

```sh
# prerequisites: Java 21+ (JRE to run, JDK to build), curl, git, make (optional)
git clone https://github.com/Nil2004n/handwritting_AI.git && cd handwritting_AI

make build models        # as YOUR user: compile + stage; fetch & checksum-verify models (network once)
make install             # per-user install (copies the staged build; never builds)
# without make:  ./gradlew installDist && sh tools/download-models.sh && sh install.sh

notebook-ai              # if ~/.local/bin is not on PATH the installer prints the exact line to add
```

### Windows (x64) — implemented, **not yet tested on a real Windows machine**

```bat
:: prerequisites: Java 21+ (JRE to run, JDK to build); Make is NOT required
gradlew.bat installDist
powershell -NoProfile -ExecutionPolicy Bypass -File tools\download-models.ps1
install.cmd --add-to-path
:: open a NEW Command Prompt, then:
notebook-ai
```

`install.cmd` installs for the current user into `%LOCALAPPDATA%\Programs\Notebook-AI` and only edits your user PATH if you
say so (`--add-to-path`, or answer the prompt). Details, options, upgrade/uninstall and the support matrix are in
[Install, upgrade and uninstall](#install-upgrade-and-uninstall) and [`installation.md`](installation.md).

Run straight from the source tree instead: `make run` or `./gradlew run`.

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

## Install, upgrade and uninstall

One layout on both systems: `<APP>/{dist,models}` plus ownership marker, `INSTALL.info` and `MANIFEST.sha256`.
The installer copies a **complete, validated, already-built** distribution; it does not build or download unless you pass `--build`.

| | Linux (default) | Windows (default) |
|---|---|---|
| Entry point | `sh install.sh` (or `make install`) | `install.cmd` (Make not needed) |
| Application directory | `${XDG_DATA_HOME:-$HOME/.local/share}/notebook-ai` | `%LOCALAPPDATA%\Programs\Notebook-AI` |
| Command | `$HOME/.local/bin/notebook-ai` (a generated launcher) | `<app>\bin\notebook-ai.cmd` (that `bin` dir goes on your **user** PATH only with consent) |
| Custom locations | `--app-dir DIR --bin-dir DIR` | `--app-dir DIR` |
| System-wide | `sudo sh install.sh --prefix /usr/local` (copy only; classic `<prefix>/lib/notebook-ai` + `<prefix>/bin`) | not provided |
| PATH | prints the exact line for your shell; never edits startup files | `--add-to-path` / prompt / `--no-path`; preserves existing entries, no duplicates, no `setx` |
| Upgrade | re-run the installer: staged copy, hash-verified, swapped in, rolled back on failure | same |
| Status / verify | `sh install.sh --status` | `install.cmd --status` |
| Uninstall | `sh install.sh --uninstall` | `install.cmd --uninstall` |

Common options: `--dist DIR` (staged build), `--models DIR`, `--build` (opt-in; runs Gradle as you, uses the network, refused as
root/Administrator), `--help`. Linux also: `--prefix`, `--destdir`, `--replace-legacy`. Windows also: `--add-to-path`, `--no-path`.

What the installers guarantee (and what tests exist for them):
- Prerequisites are checked **before** anything is modified: staged distribution complete and built for **this** OS/architecture,
  models present and matching `models/checksums.sha256`, Java 21+ found. A Linux build is never installed on Windows (or vice versa).
- Targets are validated: absolute only, no `..`, never `/`, your home, `~/.local`, Desktop/Downloads/Documents, system directories,
  the source repository or its parents. A non-empty directory without the ownership marker is never modified; a command file not
  written by notebook-ai is never overwritten.
- Upgrades copy to an installer-owned temp directory next to the target, verify every file by SHA-256, then swap; the previous
  installation is kept until the new one is in place and is restored if any step fails. Stale libraries are never mixed in.
- Uninstall removes only the owned application directory and owned command (and, on Windows, a PATH entry **this installer added**).
  Projects (`.nbai`), exports, evaluation data and the parent directories are never touched.
- Older installs from the previous `make install` (`~/.local/lib/notebook-ai`, `/usr/local/...`) are detected and reported but
  **never migrated or deleted automatically**; `--replace-legacy` replaces only the old *command*, and prefix installs the old directory
  in place. The old default `make install` (to `/usr/local`) is now the per-user install; use `PREFIX=/usr/local` for the old behaviour.
- Windows: PowerShell 5.1+ is used for filesystem/PATH safety (`tools/install-windows.ps1`). `install.cmd` runs it with a
  *process-scoped* `-ExecutionPolicy Bypass`; no policy is changed. A Group-Policy-enforced policy still wins.

### Makefile (optional)

`make help` lists everything. Targets: `build`, `models`, `test`, `verify`, `install`, `uninstall`, `status`, `clean`, `run`,
`recognize IMAGE=…`, `test-install`, `system-deps`. `install`/`uninstall`/`status` only delegate to the installers; `build`/`models`/
`test`/`verify`/`run`/`clean` refuse to run as root (and as Administrator on Windows). `clean` deletes only the Gradle `build/` output.
Options are passed by environment, so values with spaces are safe: `make install APP_DIR="/opt/my apps/nbai" BIN_DIR="$HOME/bin"`,
`make install PREFIX=/usr/local` (with sudo), `make build PLATFORM=win-x64` (stage a Windows distribution, e.g. from Linux).

| Environment | Status |
|---|---|
| Linux x86-64 + GNU Make 4.x + sh | **tested** (all targets) |
| Windows x64 + GNU Make using cmd.exe | dispatch to `gradlew.bat` / `install.cmd` implemented; **not tested** |
| Windows without Make | fully supported through `install.cmd` + `gradlew.bat` (not tested on real Windows) |
| MSYS2 / Git Bash / Cygwin make, NMake, macOS, BSD, Linux ARM | **unsupported**: refused with a clear message |

---

## Release distributions (linux-dist / win-dist)

Two ready-to-zip directories for people who do **not** have the source, Gradle, Git or Make. They are *generated* (never hand-assembled),
git-ignored, and packaged into archives in `release-output/`.

```sh
make models                       # once: fetch + verify the OCR models (they are bundled into the distributions)
make dist-linux                   # -> linux-dist/       (linux-x64)          ./gradlew distLinux
make dist-windows                 # -> win-dist/         (win-x64, cross-assembled)   ./gradlew distWindows
make dist                         # both                                      ./gradlew dist
make package                      # -> release-output/notebook-ai-<ver>-linux-x64.zip, ...-windows-x64.zip (+ .sha256), only from VALIDATED directories
                                  #                                           ./gradlew package   (packageLinux / packageWindows individually)
make verify-dist | dist-clean     # re-validate / remove only what this build generated   (./gradlew verifyDist | cleanDist)
```

On Windows without Make use `gradlew.bat distWindows packageWindows` (the same Gradle tasks). The default `build`/`installDist` is unchanged and builds nothing extra.

What a recipient gets (Linux shown; Windows has `.cmd` equivalents and `scripts\install-windows.ps1`):

```text
notebook-ai-<ver>-linux-x64/          (single top-level directory in the ZIP)
  install.sh  uninstall.sh  run-notebook-ai.sh    install per user / remove / run in place
  README.txt  LICENSE  THIRD-PARTY-NOTICES.txt  licenses/    instructions and legal documents
  distribution.json  SHA256SUMS                   metadata + checksum of every shipped file
  scripts/launcher-body.sh                        shared launcher body (also used by the installed command)
  app/{bin,lib,models,PLATFORM}                   the application, ONE platform's JavaFX + ONNX Runtime natives, the OCR models
```

- **Not fully standalone:** a **Java 21+ runtime must be installed separately** (no JRE is bundled; runtime bundling would need separate approval).
  The recipient also needs a desktop session (Linux) / PowerShell 5.1+ for the installer (Windows) and the standard Unix tools the start script uses.
- **Models are bundled** (PP-OCRv6_small, Apache-2.0 as declared by the model repositories), verified against `models/checksums.sha256` while
  generating, so OCR works offline after extraction. Nothing is downloaded at install or run time; absent models produce an actionable error.
- **One architecture per directory.** The ONNX Runtime jar (133 MB with every OS's natives) is repackaged to the target's natives only; the change is
  documented in `THIRD-PARTY-NOTICES.txt` and recorded (original + trimmed SHA-256) in `distribution.json`.
- **Direct run, no install:** `./run-notebook-ai.sh` / `run-notebook-ai.cmd` (works from any directory; checks Java; writes nothing into the folder).
  `--self-check` runs a headless check of Java, the JavaFX modules, the ONNX natives and the models.
- **Install / upgrade / uninstall** from the extracted folder use the same installers described above (`install.sh`, `uninstall.sh`; `install.cmd`, `uninstall.cmd`) in *packaged mode*: they verify `SHA256SUMS`
  first, refuse the wrong platform, never build or download, and the installed command keeps working after the extracted folder is deleted.
- **`distribution.json`** records name, version, source commit, whether the source tree was dirty, target OS/arch, required Java, model identities/checksums, native trimming, and the known licensing gaps.
  Archives are generated with stable ordering and normalised timestamps; re-packaging an unchanged directory gave identical bytes (checked once), but reproducible builds are **not claimed**
  (`distribution.json` carries a generation timestamp).

**Not ready to ship yet (see `THIRD-PARTY-NOTICES.txt` "known gaps"):** the ONNX Runtime LICENSE text and the xmpcore copyright notice could not be obtained, the OpenJFX texts come from a repository
snapshot rather than the 21.0.7 tag, and model licensing rests on repository metadata. The **Windows distribution has never been executed on Windows.**
No archive has been published, tagged or released.

---

## Native installers (.deb and Windows .exe, bundled Java runtime)

jpackage-built installers that **include their own Java runtime**, so end users need no Java. They are separate from the ZIP distributions above
(which still require Java 21+ and are unchanged). Each is built **on its own OS**; jpackage cannot cross-build.

| | Linux | Windows |
|---|---|---|
| Artifact | `release-output/notebook-ai_<ver>_amd64.deb` | `release-output/notebook-ai-<ver>-windows-x64-setup.exe` |
| Build host | Debian/Ubuntu-family x86-64 | Windows x64 |
| Build command | `make package-deb` or `./gradlew packageDeb` | `gradlew.bat packageExe` (or `make package-exe` with GNU Make) |
| Host-native shortcut | `make package-native` / `./gradlew packageNative` (picks this host's type, fails early elsewhere) | same |
| Prerequisites | JDK 21 **with `jmods`**, `dpkg-deb`, `fakeroot`, `ldd` (`sudo apt-get install dpkg-dev fakeroot binutils`) | JDK 21 with `jmods` + **WiX Toolset 3.x on PATH** (3.14.1; JDK 21's jpackage does not support WiX 4/5) |
| Also needed first | `make models` (the models are bundled) | `tools\download-models.ps1` |
| Installs to | `/opt/notebook-ai` (+ `/usr/bin/notebook-ai`, menu entry) | `%LOCALAPPDATA%\notebook-ai` (per user, no Administrator) |
| Command | `notebook-ai` (system PATH, `/usr/bin`) | GUI: Start Menu / `notebook-ai.exe`; console: `notebook-ai-cli.exe` (e.g. `--self-check`) |
| Install | `sudo apt install ./notebook-ai_<ver>_amd64.deb` | run the `.exe` (per-user; optional desktop shortcut prompt) |
| Upgrade | install the newer `.deb` the same way | run the newer `.exe` (stable upgrade UUID, so no duplicate app) |
| Uninstall | `sudo apt remove notebook-ai` (`purge` too) | Settings > Apps > notebook-ai |
| Verify | `./gradlew verifyDeb`, `make test-deb` | `gradlew.bat checkNativeInputWindows` (any host: static input check) |

**Bundled runtime.** A `jlink` runtime of the *identified* packaging JDK with `java.base, java.desktop, java.logging, java.xml, java.prefs, jdk.unsupported, jdk.jfr, jdk.charsets`
(jdeps found `java.base, java.desktop, java.logging`; the rest are JavaFX's declared requirements and defensive additions, validated by running the self-check, real OCR and the GUI). JavaFX stays on the module
path (`lib/fx`, never the classpath), the four application jars on the classpath, the models in `lib/models`. The launcher passes `-Dnotebookai.models=$APPDIR/../models` (a native launcher cannot set environment variables);
an explicit `NOTEBOOK_AI_MODELS` still wins. The package never uses `java` from `PATH` or `JAVA_HOME`. The runtime source is never downloaded by this build: it is the JDK named by `-PpackagingJdkHome`, `NBAI_PACKAGING_JDK`,
`JAVA_HOME` (if it has `jmods`) or the Gradle toolchain, recorded in `lib/legal/native-package.json`. **A build from an early-access runtime (this development machine's Debian JDK is `21.0.11-ea`) is flagged an internal build.**
CI (`installer-smoke.yml`, manual dispatch only; replaced the former `native-installers.yml`) pins Temurin `21.0.13+9` (verified at run time) and WiX 3.14.1 (installer SHA-256 pinned).

**Publisher fields** are configuration, not invented: `-PpublisherName="..." -PdebMaintainerEmail=...`. Unset values become clearly marked placeholders (`unconfigured@invalid`) and mark the build internal.
There is no application icon in the repository, so jpackage's default icon is used.

**Debian details.** Maintainer scripts are jpackage's (`xdg-desktop-menu install/uninstall`, made non-fatal) plus a read-only notice if `/usr/local/bin/notebook-ai` or `~/.local/bin/notebook-ai` could shadow `/usr/bin/notebook-ai`.
They do not touch shell startup files, download, build or launch anything. Dependencies are computed on the build host (Debian/Kali "t64" library names), so build the `.deb` on the distribution family you target; direct JavaFX/GTK needs are added explicitly.

**Signing: nothing is signed.** An unsigned `.exe` may show an unknown-publisher warning; what Windows actually shows has not been observed (a CI runner cannot show SmartScreen), and the installer is per-user, so it is not expected to need Administrator rights (to be confirmed by the Windows test); the `.deb` is not signed (apt verifies repository metadata, not a local file). Optional external signing: `-PsignCommand="signtool sign ... {file}"`
(credentials come from your environment, never from the repository); `native-package.json` records `signed: false` unless that step succeeds.

**License gate.** `packaging/licensing-status.json` is the machine-readable evidence file (shipped inside every package). `./gradlew verifyReleaseLicensing` **fails while any release-blocking item is not `resolved`**, and every native package built meanwhile
is marked `internalBuild` with the reasons. Currently resolved: ONNX Runtime LICENSE (from the official 1.22.0 release artifact), OpenJFX texts (release tags 21.0.7+0/+1), metadata-extractor (Apache-2.0).
**Open: xmpcore 6.1.11 (unresolved: no copyright notice obtainable) and the PP-OCRv6 models (partial: model-card + project license, no grant specific to the weights).** Details: `packaging/legal/PROVENANCE.txt`.

**What has actually been verified (Linux x86-64 host, Kali):** the `.deb` builds and validates; extracted-payload execution with no Java on PATH (self-check, exit codes, model override, real OCR through the bundled runtime, a real GUI launch under a private display
that maps the *bundled* `libjvm.so`); a real-`dpkg` lifecycle in a private non-root tree; and a real **apt** install/upgrade/remove/purge in a throw-away **Debian trixie** container (dependencies resolved by apt, no Java on the machine). **Not verified:** Ubuntu or any other Debian-family release,
`lintian` (not installed), a GUI inside the container, and **everything about the Windows installer** - the `.exe` has never been built or run (no Windows machine); `checkNativeInputWindows` validates only the jpackage *input*.
`tools/test-installer-windows.ps1` (comprehensive Windows lifecycle test, kept for manual/local use on a disposable Windows machine; its CI runs were slow and one stage stalled, so it is no longer run automatically) exists for a real Windows runner. The fast `installer-smoke` workflow is the routine CI evidence. Silent-install switches of jpackage's EXE and the `add-launcher` option inheritance are assumptions to confirm on first run.
Windows PATH integration is **not offered** (it would need custom WiX work), so `notebook-ai` does **not** work globally in CMD. The console executable is `%LOCALAPPDATA%\notebook-ai\notebook-ai-cli.exe` (e.g. `"%LOCALAPPDATA%\notebook-ai\notebook-ai-cli.exe" --self-check`); add its folder to your user PATH yourself if you want a short name.
Neither package is ready to ship.

---

## Configuration

| Setting | Where | Effect |
|---|---|---|
| `NOTEBOOK_AI_MODELS` | environment | Directory containing `det.onnx`, `det.yml`, `rec.onnx`, `rec.yml`, `checksums.sha256`. The installed launcher sets it to `<app dir>/models` unless you already set it (the app resolves models from this variable, never from the working directory, so it starts from any directory) |
| `JAVA_HOME` | environment | Which Java runs the app (Java **21 or newer** is required; the launcher checks and says so). If `JAVA_HOME` is unset and the Java on `PATH` is too old, the Linux launcher tries `$NOTEBOOK_AI_JDK_FALLBACK` (default `/usr/lib/jvm/java-21-openjdk-amd64`) |
| `XDG_DATA_HOME` | environment (Linux) | Base for the default application directory (`$XDG_DATA_HOME/notebook-ai`, default `~/.local/share/notebook-ai`) |
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
make test-install                  # = sh tools/verify-install.sh: Linux installer + Makefile suite (156 checks, temporary locations only)
pwsh -File tools/verify-install-windows.ps1   # Windows installer logic suite (114 checks + 4 Windows-only SKIPs when run on Linux)
./gradlew evaluate -PevalManifest=eval/cases.tsv
```

Evidence kinds are kept apart in [`HANDOFF.md`](HANDOFF.md): **automated tests**, **GUI checks** performed on the
installed build under a private display, and **code inspection**. The installed-GUI checks performed were: independent
per-page preparation, region copy/insert with real clipboard reads, reorder + exact export, save/close/reopen, filename
behaviour, close-during-OCR, and edit-during-save. Anything not listed there was not GUI-verified.

Distribution evidence: `make test-dist` (`tools/test-dist.sh`, 50 checks) extracts the real Linux archive into a path with spaces, runs it from another directory (headless `--self-check`
and a GUI launch under a private display), installs it into a temporary HOME, deletes the extracted folder and re-runs the installed command, performs real OCR through the installed trimmed
ONNX Runtime, upgrades, uninstalls and checks tamper refusal; `tools/test-dist-build.sh` (16 checks) tests the generator's safeguards; `tools/test-dist-windows.ps1` (34 checks + 5 Windows-only SKIPs on Linux) checks
the Windows archive's content and packaged-mode installer logic.

Installer evidence: the Linux suite (`tools/verify-install.sh`) really executes `install.sh`, the generated launcher and the Makefile
in isolated temporary HOME/PATH environments (fresh install, upgrade, rollback, unsafe targets, spaces/non-ASCII paths, argument and
exit-status forwarding, Java checks, shadowing, legacy layout, uninstall) and installs the real staged build, comparing every file
by SHA-256. The Windows suite executes the PowerShell helper with a file-backed PATH store, but **under PowerShell on Linux**: it
covers validation, PATH logic, staging, upgrade, rollback and uninstall; the cmd.exe wrapper, `install.cmd`, the registry PATH write and
Windows file locking are only exercised by its `[WINDOWS-ONLY]` section (and the `installers` GitHub Actions workflow), which
has **not been run** on real Windows.

---

## Privacy

- No network access at runtime. Models are fetched once at build time by `tools/download-models.sh`.
- A `.nbai` file contains **your full images and text**; exports contain your text. Files are written only where you choose.
- `*.nbai`, `projects-local/`, `exports-local/` and `eval-local/` are git-ignored. Do not commit private data.
- Models and build output are git-ignored as well.

---

## Repository layout

```
spelling/                  offline English spelling assistance (pure Java, no dependencies, bundled SCOWL word lists; separate from the engine): docs/SPELLING.md
engine/                    PORTABLE OCR engine (Gradle subproject, pure Java 17, no AWT/JavaFX): PixelImage, ModelAssets, OcrEngine, OcrResult
                           and the pipeline internals. Specification and Android notes: docs/ENGINE.md
android-proof/             separate Gradle build (needs an Android SDK; NOT part of the desktop build): engine proof harness, not a product UI
src/main/java/notebookai/  the DESKTOP application (the Gradle root project)
  ocr/       desktop adapters and tools sharing the package name with the engine: DesktopImageIO, OcrService (facade), debug writers, CLI, diagnostics
  model/     working image, model config (desktop models-directory precedence), UI control state
  project/   project data, .nbai store, saving/dirty tracking, background tasks, shutdown policy, export, memory guard
  eval/      CER/WER, alignment, evaluate task
  ui/        JavaFX application (Main, NotebookAiApp)
src/test/java/…   JUnit 5 tests mirroring the packages
install.sh · install.cmd   installers (Linux / Windows); Makefile dispatches to them and to Gradle
tools/       download-models.sh/.ps1 · install-windows.ps1 · verify-install.sh · verify-install-windows.ps1 · xcheck/ (Python, optional)
.github/workflows/installers.yml   Linux + Windows installer tests (CI)
eval/        committed evaluation manifest      eval-local/  private (git-ignored)
samples/     sample handwriting image and transcriptions
models/      ONNX models + checksums.sha256 (binaries git-ignored)
third-party/ upstream PaddleOCR licence
Makefile · build.gradle · settings.gradle · gradlew
```

**Mobile developers: start with [`docs/MOBILE_HANDOFF.md`](docs/MOBILE_HANDOFF.md) (checklist, fixtures, the exact Android device tests still required).**

**Engine / mobile handoff:** the OCR engine is a separate module (`engine/`) with a documented contract, pipeline specification and an
Android proof harness: see [`docs/ENGINE.md`](docs/ENGINE.md). Android *build* integration is verified; Android *runtime* acceptance is pending
(no device was available). Commands: `./gradlew :engine:build` (engine tests + dependency check), and
`ANDROID_HOME=$HOME/Android/Sdk ./gradlew -p android-proof :app:verifyEngineAndroidApi :app:verifyProofDependencies :app:assembleDebug`.
Packaging tests that must not touch `release-output/` take `-PreleaseOutputDir=/some/temp/dir`.

**Spelling assistance (optional, suggestion-first):** the transcript is checked in the background against bundled, offline SCOWL word lists (US/UK/Canadian English);
possible misspellings appear in a panel under the transcript with ranked suggestions, **Replace / custom replacement / Ignore Once / Ignore All / Add to Dictionary** and Undo/Redo.
It never changes the raw OCR text and never replaces anything automatically (automatic replacement is deferred). The personal dictionary and the setting live in your user profile
(`~/.config/notebook-ai` on Linux, `%APPDATA%\notebook-ai` on Windows; override with `NOTEBOOK_AI_CONFIG_DIR`), nothing is uploaded. It is not Microsoft's spell checker. Details, limits and
measurements: [`docs/SPELLING.md`](docs/SPELLING.md). Commands: `./gradlew test`, `xvfb-run -a ./gradlew spellingGuiAcceptance`.

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
- **Windows execution**: the Windows distribution stages and its PowerShell installer logic is tested under PowerShell on Linux, but `install.cmd`, `notebook-ai.cmd`, the registry PATH update and the application itself have **never run on real Windows**. Windows support is therefore *implemented, not accepted*.
- macOS, Linux ARM/aarch64, Windows ARM64 and 32-bit systems (rejected with a clear message).
- Privileged system installation (`sudo sh install.sh --prefix /usr/local`) was not exercised; the copy-only logic was verified in temporary user-owned prefixes.
- Non-ASCII install paths on Windows (depend on the console code page; tested only on Linux).
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
| `no staged distribution at …` / `staged distribution is for 'X'` | Build it first as your normal user (`make build` / `gradlew.bat installDist`); a distribution built for another OS is rejected |
| `… exists and is not a notebook-ai installation` | The target directory is not owned by the installer (no marker); choose another `--app-dir`. Nothing was modified |
| `… is the command from the OLD 'make install'` | Pass `--replace-legacy` to point the command at the new install (old files stay), or remove the old one with `sh install.sh --uninstall --prefix … --replace-legacy` |
| `notebook-ai: Java N found … Java 21 or newer is required` | Install/point `JAVA_HOME` at Java 21+ |
| Windows: new terminal still says `'notebook-ai' is not recognized` | PATH was not added (declined) or the terminal was opened before; run `install.cmd --add-to-path` and open a **new** Command Prompt, or run the command by its full path |
| Windows: `cannot replace … a file in it is probably in use` | Close Notebook-AI and terminals running it, then re-run; the existing installation is unchanged |
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
