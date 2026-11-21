# Installation guide — notebook-ai

This guide takes you from a clean Linux machine to a verified, installed `notebook-ai` command, and explains how to
update, uninstall and troubleshoot it. Everything here describes behaviour that exists in this repository's
`Makefile` and scripts; where something was **not** tested, it is marked **(untested)**.

**Tested platform:** Linux x86-64 (Kali/Debian family, JDK 21, X11 display). Windows and macOS: **untested**.
Privileged install to `/usr/local` with `sudo`: **untested** (the copy logic is verified in a temporary user-owned prefix).

---

## Contents

1. [The one rule: build as yourself, install with privilege only if needed](#1-the-one-rule)
2. [Prerequisites](#2-prerequisites)
3. [Get the source](#3-get-the-source)
4. [Build and fetch models](#4-build-and-fetch-models)
5. [Install](#5-install)
6. [Verify the installation](#6-verify-the-installation)
7. [Run](#7-run)
8. [Update to a newer version](#8-update-to-a-newer-version)
9. [Uninstall](#9-uninstall)
10. [Running without installing](#10-running-without-installing)
11. [What gets installed where](#11-what-gets-installed-where)
12. [Environment variables and JVM options](#12-environment-variables-and-jvm-options)
13. [Why builds must not run as root](#why-builds-must-not-run-as-root)
14. [Headless, remote and test displays](#14-headless-remote-and-test-displays)
15. [Offline and air-gapped use](#15-offline-and-air-gapped-use)
16. [Troubleshooting](#troubleshooting)
17. [Other platforms](#17-other-platforms)

---

## 1. The one rule

```text
make build models         ← as YOUR normal user. Never with sudo.
make install              ← copies files only. Use sudo here ONLY if the destination needs it.
```

`make build`, `make models`, `make test`, `make verify`, `make recognize`, `make run` and `make clean` **refuse to run
as root**. `make install` never compiles or downloads anything; it only copies an already staged build.
If you try `sudo make build`, you will get an explanatory error and nothing runs.

---

## 2. Prerequisites

| Requirement | Version | Why | Check |
|---|---|---|---|
| Java (JDK to build, JRE enough to run) | **21** | the app targets Java 21 (Gradle toolchain 21) | `java -version` |
| `curl` | any recent | downloads models once, at build time | `curl --version` |
| `sha256sum`, `make`, `git`, POSIX `sh`, `unzip` (optional) | coreutils/make | checksum verification, Makefile | `make --version` |
| A graphical session (X11 or Wayland/XWayland) | — | JavaFX window | `echo $DISPLAY` |
| Network | once | Gradle dependency download (Maven Central) + model download (Hugging Face) | |
| Disk | ≈ 1 GB free to build (Gradle cache + build output); models ≈ 31 MB | | `df -h .` |
| RAM | ≥ 4 GB recommended | large photos are decoded in memory | `free -m` |

Notes:
- Gradle 8.14 (wrapper included) supports running on Java up to 24. If your *default* Java is newer (e.g. 25), the
  Makefile automatically pins the Gradle daemon to `/usr/lib/jvm/java-21-openjdk-amd64` **when that path exists**.
  With another JDK location, pass `-Dorg.gradle.java.home=…` yourself (see [Troubleshooting](#troubleshooting)).
- You do **not** need to install Gradle, Python, CUDA or any OCR package. The wrapper `./gradlew` fetches Gradle.

### Install Java 21 (Debian / Ubuntu / Kali)

```sh
make system-deps          # runs: sudo apt-get update && sudo apt-get install -y openjdk-21-jre
```

`system-deps` installs a **JRE**. To *build* you need a **JDK** (includes `javac`):

```sh
sudo apt-get install -y openjdk-21-jdk
```

On other distributions install an OpenJDK/Temurin 21 package with your package manager **(untested)**.
Verify: `java -version` → `openjdk version "21…"`.

---

## 3. Get the source

```sh
git clone https://github.com/Nil2004n/handwritting_AI.git
cd handwritting_AI
git status          # should be clean
```

Work in a directory you own. Do **not** clone or build as root (see [§13](#why-builds-must-not-run-as-root)).

---

## 4. Build and fetch models

```sh
make build models
```

What each step does:

- **`make build`** → `./gradlew installDist`. Compiles the code and stages the application under
  `build/install/notebook-ai/{bin,lib}`. The first run downloads Gradle and the dependencies (ONNX Runtime 1.22.0,
  JavaFX 21.0.7 Linux jars, metadata-extractor 2.19.0, JUnit) into `~/.gradle`.
- **`make models`** → `sh tools/download-models.sh`. Downloads four files into `models/` from the official
  PaddlePaddle Hugging Face repositories, **pinned by commit**:
  `det.onnx`, `det.yml` (detector) and `rec.onnx`, `rec.yml` (recognizer). It then runs
  `sha256sum -c models/checksums.sha256`; any mismatch is an error. Behaviour worth knowing:
  - Existing, already-valid files are kept ("keeping existing valid …"), so re-running is cheap and safe.
  - It tries the pinned commit first and falls back to the branch ref if the host answers the pinned URL with an
    intermittent error; **integrity is enforced by the checksums either way**.
  - It waits ~10 s between files because the CDN rate-limits rapid requests. Expect about 40 s plus transfer time.
  - Models are Apache-2.0; they are git-ignored and never committed.

Optional full test run (compiles and runs the 130-test JUnit suite, ~1–2 minutes):

```sh
make test            # = ./gradlew clean build
```

---

## 5. Install

### 5a. User-local install (recommended; no root)

```sh
make install PREFIX="$HOME/.local"
```

Ensure `~/.local/bin` is on your `PATH`:

```sh
echo "$PATH" | tr ':' '\n' | grep -x "$HOME/.local/bin" || \
  echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.profile   # then log in again, or: export PATH=…
```

### 5b. System-wide install to `/usr/local` **(untested)**

Build as your user first (§4), then:

```sh
sudo make install
```

`sudo` here only **copies files**; it does not compile, download or run Gradle. If the staged build or models are
missing, the install stops with instructions to build as your normal user — it does not build for you.

### 5c. Custom prefix / packaging

```sh
make install PREFIX=/opt/notebook-ai                 # needs write access to /opt/notebook-ai
make install PREFIX=/usr DESTDIR=/tmp/stage          # staged tree for packagers: /tmp/stage/usr/…
```

With `DESTDIR` set, files go under `$DESTDIR$PREFIX`; the launcher still refers to the final `$PREFIX` path.
The "shadowed command" check is skipped for `DESTDIR` installs.

### What `make install` does and refuses to do

1. Requires the staged build (`build/install/notebook-ai/bin/notebook-ai`) → else error with normal-user instructions.
2. Requires valid models (`sha256sum -c`) → else error telling you to run `make models`.
3. Requires a **writable** prefix → else tells you to re-run with `sudo`.
4. Refuses an unsafe `APP_DIR` (it must end in `/lib/notebook-ai`) **before deleting anything**.
5. Removes only the application-owned `bin/` and `lib/` inside `<prefix>/lib/notebook-ai` (so no stale JARs survive
   an upgrade); it leaves other apps, the `models/` directory and everything else alone.
6. Copies the staged `bin` and `lib`, the four model files and `checksums.sha256`.
7. Writes the launcher `<prefix>/bin/notebook-ai` (mode 755).
8. Warns if a *different* `notebook-ai` earlier on your `PATH` would shadow the one just installed.

---

## 6. Verify the installation

### 6a. Quick checks

```sh
command -v notebook-ai                        # expect …/.local/bin/notebook-ai (or /usr/local/bin/…)
ls "$HOME/.local/lib/notebook-ai"             # bin  lib  models
( cd models && sha256sum -c checksums.sha256 )   # det.onnx: OK … four lines
```

### 6b. Installed JAR equals the staged build

```sh
sha256sum build/install/notebook-ai/lib/notebook-ai-0.1.0.jar \
          "$HOME/.local/lib/notebook-ai/lib/notebook-ai-0.1.0.jar"
```

The two hashes must be identical. (A *rebuild* of the same source produces a different JAR hash because JAR
timestamps are not reproducible; compare an installed copy only with the build it was copied from.)

### 6c. Model sessions load

```sh
make verify          # ./gradlew verifyOcrSetup   → "Native resources closed. Setup valid."
```

### 6d. Install-safeguard self-test (no sudo, touches nothing outside a temp dir)

```sh
sh tools/verify-install.sh
```

It installs into a throw-away user-owned prefix and checks 18 properties, ending with `ALL CHECKS PASSED`:
install succeeds and never calls Gradle; installed JAR equals staged JAR; files are owned by you; a stale jar is
removed on reinstall while sibling apps and `models/` are untouched; an unsafe `APP_DIR` is refused with nothing
deleted; each Gradle-calling target refuses to run as root; a missing staged build fails with normal-user
instructions (also when "root"); the shadowed-launcher warning appears. It requires a staged build (`make build`).

### 6e. OCR smoke test from the command line

```sh
make recognize IMAGE=samples/handwriting-test.jpg
```

Prints regions with boxes and scores, the combined text and timings. On the committed sample the known measurement is
CER 12/67 characters and WER 5/13 words.

### 6f. Optional: accuracy regression

```sh
./gradlew evaluate -PevalManifest=eval/cases.tsv      # expect CER=0.179 (12 edits) WER=0.385 (5 edits)
```

---

## 7. Run

```sh
notebook-ai
```

The launcher sets `NOTEBOOK_AI_MODELS` to the installed model directory (unless you set it), sets `JAVA_HOME` to the
JDK 21 path if unset and available, and starts `<prefix>/lib/notebook-ai/bin/notebook-ai`. The window title reads
`notebook-ai — Untitled`. The status area shows the model line (`PP-OCRv6_small (…, ONNX Runtime CPU)`). If this is
missing or shows an error, see [Troubleshooting](#troubleshooting).

Running from the source tree: `make run` or `./gradlew run`.

---

## 8. Update to a newer version

```sh
cd handwritting_AI
git pull --ff-only
make build models          # as your user; models are re-verified, only re-downloaded if invalid
sh tools/verify-install.sh # optional
make install PREFIX="$HOME/.local"      # same prefix as before (use sudo only if that prefix requires it)
```

Check for a shadowing copy: if `make install` prints `WARNING: 'notebook-ai' on your PATH resolves to …`, an older
copy elsewhere is still first on `PATH`. Update or remove it (the message prints the exact `make install PREFIX=…`
command for that location).

Your saved projects (`.nbai`) are plain files outside the install tree and are untouched by updates. Project format
version 1 is read by this release; a newer format version produces a clear refusal, not a corrupted read.

---

## 9. Uninstall

```sh
make uninstall PREFIX="$HOME/.local"           # removes <prefix>/lib/notebook-ai and <prefix>/bin/notebook-ai
sudo make uninstall                            # if it was installed to /usr/local (untested)
```

This removes only the app directory and the launcher. It does not touch your projects, exports or the source
checkout. To also reclaim build caches: `make clean` (and `~/.gradle` if you no longer build anything).

---

## 10. Running without installing

```sh
make build models
make run                      # or ./gradlew run
```

Models resolve from `./models`, or from `NOTEBOOK_AI_MODELS` if set. You can also run the staged build directly:
`build/install/notebook-ai/bin/notebook-ai` (set `NOTEBOOK_AI_MODELS=$PWD/models`).

---

## 11. What gets installed where

```text
<prefix>/bin/notebook-ai                      launcher script (sh)
<prefix>/lib/notebook-ai/bin/notebook-ai      Gradle start script
<prefix>/lib/notebook-ai/lib/                 notebook-ai-0.1.0.jar, onnxruntime, javafx-*-linux.jar, metadata-extractor, …
<prefix>/lib/notebook-ai/models/              det.onnx det.yml rec.onnx rec.yml checksums.sha256   (≈ 31 MB)
```

Not installed: source, tests, evaluation data, your projects. The application never writes into the install tree
and never uses the network. Default `PREFIX` is `/usr/local`; `~/.local` needs no root.

---

## 12. Environment variables and JVM options

| Variable | Used by | Effect |
|---|---|---|
| `NOTEBOOK_AI_MODELS` | app / launcher | model directory (must contain the four files + `checksums.sha256`) |
| `JAVA_HOME` | launcher / start script | Java to run with |
| `JAVA_OPTS`, `NOTEBOOK_AI_OPTS` | Gradle start script | extra JVM options, e.g. `JAVA_OPTS="-Xmx4g"` |
| `PREFIX`, `DESTDIR` | `make install/uninstall` | destination (see §5c) |
| `IMAGE` | `make recognize` | image to OCR |

Memory: very large photos need heap. The app refuses an image whose estimated decoded footprint
(≈ 12 bytes/pixel) would not fit in about 70 % of the maximum heap and tells you how to raise it, e.g.
`JAVA_OPTS=-Xmx4g notebook-ai`. A 12 MP phone photo is fine with a 2 GB heap. Observed peak RSS in a stress run with
many 12 MP pages was about 2.6 GB, so plan for a few GB when working with large projects.

Experiment switches (`-Dnotebookai.exp.cropPad`, `-Dnotebookai.exp.beam`) exist for accuracy studies; leave them unset
for normal use.

---

## Why builds must not run as root

History: `sudo make install`, `sudo make build`, `sudo make install` were run once; the middle step ran Gradle as
root and created **root-owned** files under `build/install` inside the repository. Ordinary `./gradlew clean` then
failed with permission errors, and the directory had to be moved aside (it was never deleted).

Safeguards now in place:
- Every Gradle/download target depends on `check-not-root` and fails with instructions if the effective UID is 0.
- `make install` depends only on a *pre-existing* staged build and valid models; it cannot trigger a build.
- Never use `sudo chown -R` or `chmod 777` to "fix" this. If you ever find root-owned files in the checkout, move the
  directory aside as your user or remove it deliberately with a targeted `sudo rm -r <that exact path>` after reviewing
  it, then rebuild normally.

---

## 14. Headless, remote and test displays

The UI needs a display. For automated checks without a monitor, use a private virtual display (Linux):

```sh
Xvfb :77 -screen 0 1600x1000x24 &
DISPLAY=:77 notebook-ai
```

(Use your own display number; do not point tests at your real desktop.) Command-line OCR and all tests
(`./gradlew build`, `verifyOcrSetup`, `recognizeImage`, `evaluate`) are headless and need no display.

Over SSH: use X forwarding (`ssh -X`) or a remote-desktop session. **(untested)**

---

## 15. Offline and air-gapped use

The application itself is fully offline. Only the **build** needs the network (Maven Central, Gradle distribution,
Hugging Face). To build offline: build once online, then copy `build/install/notebook-ai` and the verified `models/`
files to the target machine and use `make install` there, or copy the installed `<prefix>/lib/notebook-ai` tree and
launcher directly. The target needs only a Java 21 runtime. **(Offline transfer untested.)**

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `error: refusing to run 'make build' as root` | guard working as designed | run as your normal user; only `make install` may use `sudo` |
| `error: staged app missing. Build it first AS YOUR NORMAL USER` | `make install` found no `build/install/…` | `make build models` (no sudo), then install again |
| `error: models missing or invalid` | models absent, partial or corrupted | `make models` (network needed); it re-verifies checksums |
| `error: … is not writable; rerun with sudo` | prefix owned by root | use `PREFIX=$HOME/.local`, or `sudo make install` |
| `error: refusing unsafe APP_DIR` | `APP_DIR` override does not end in `/lib/notebook-ai` | remove the override; this check prevents deleting the wrong directory |
| `Unsupported class file major version NN` | Gradle running on Java newer than it supports | `sudo apt-get install openjdk-21-jdk`; or `./gradlew -Dorg.gradle.java.home=/path/to/jdk21 …` |
| `Could not determine java version` / toolchain errors | no JDK 21 found | install a **JDK** (not only a JRE) 21 |
| `curl: (22)` / 404 / "Invalid rev id" during `make models` | transient CDN/rate-limit | re-run `make models`; it resumes, skips valid files, falls back to branch ref |
| `sha256sum: WARNING … did NOT match` | corrupted download or wrong files | delete the named file in `models/`, run `make models` again |
| `notebook-ai: command not found` | `<prefix>/bin` not on `PATH` | add it (§5a) and re-login |
| The old version still launches | another copy first on `PATH` | follow the WARNING printed by `make install` |
| `Error: Could not find or load main class` / missing JavaFX | partial or stale install | reinstall: `make build && make install` (the installer wipes stale `lib/` first) |
| Window does not appear / `Cannot open display` | no graphical session | set `DISPLAY`, or use Xvfb (§14) |
| `Permission denied` removing `build/` or `build/install` | earlier root-owned build artefacts | see [Why builds must not run as root](#why-builds-must-not-run-as-root) |
| App says an image is too large for memory | memory guard | smaller image, or `JAVA_OPTS=-Xmx4g notebook-ai` |
| Save-dialog filename ends with a doubled extension | pre-filled extension left in the name box | press `Ctrl+A` in the name box before typing the new name |
| Build is slow the first time | dependency download | subsequent builds use `~/.gradle` cache |
| `make system-deps` does nothing useful | not a Debian-family system | install OpenJDK 21 manually |

Collecting diagnostics for a bug report (no private data): `java -version`, `uname -a`, `make verify` output,
the exact failing command and its full message, and `sha256sum -c models/checksums.sha256`.

---

## 17. Other platforms

- **Windows / macOS:** not tested. The build generates a Windows launcher (`.bat`) and JavaFX/ONNX Runtime publish
  other platform artifacts, but `build.gradle` currently pins **Linux** JavaFX classifiers
  (`javafx-*:21.0.7:linux`) and the Makefile assumes POSIX tools. Porting requires changing those classifiers and
  verifying the whole pipeline. **(Not done; no claim of support.)**
- **Other Linux architectures (aarch64):** not tested; the pinned JavaFX classifier and ONNX Runtime natives would
  need review.
