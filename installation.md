# Installation guide — notebook-ai

This guide covers building, installing, upgrading and removing notebook-ai on **Linux (x86-64)** and **Windows (x64)**.
Where something has **not** been tested it is marked **(untested)**. The honest summary:

| | Linux x86-64 | Windows x64 |
|---|---|---|
| Distribution builds | tested | tested (cross-staged on Linux; real Windows build untested) |
| Installer logic | tested (`tools/verify-install.sh`, 145 checks) | logic tested under PowerShell **on Linux** (114 checks); cmd.exe/registry/file-locking **not tested** |
| The application itself | tested | **never run on Windows** |
| Per-user install, no sudo/Admin | yes | yes |
| System-wide install | `sudo sh install.sh --prefix /usr/local` (copy only; **untested**) | not provided |

---

## Contents

1. [Concepts: build, models, install are separate steps](#1-concepts)
2. [Prerequisites](#2-prerequisites)
3. [Linux: install](#3-linux-install)
4. [Windows: install](#4-windows-install)
5. [Where things go](#5-where-things-go)
6. [PATH behaviour](#6-path-behaviour)
7. [Verify an installation](#7-verify-an-installation)
8. [Upgrade](#8-upgrade)
9. [Uninstall](#9-uninstall)
10. [Installer options reference](#10-installer-options-reference)
11. [Safety guarantees and their limits](#11-safety-guarantees-and-their-limits)
12. [Why builds must not run as root / Administrator](#12-why-builds-must-not-run-as-root--administrator)
13. [Models and network use](#13-models-and-network-use)
14. [Migrating from the old `make install` layout](#14-migrating-from-the-old-make-install-layout)
15. [Environment variables](#15-environment-variables)
16. [Running without installing, headless, offline](#16-running-without-installing-headless-offline)
17. [Supported platforms matrix](#17-supported-platforms-matrix)
18. [Troubleshooting](#18-troubleshooting)

---

## 1. Concepts

Three independent steps, each run **as your normal user** (never `sudo`, never an elevated prompt):

1. **Build** the distribution: `make build` / `./gradlew installDist` / `gradlew.bat installDist`.
   Output: `build/install/notebook-ai` (for the host OS) containing `bin/`, `lib/` and a `PLATFORM` file
   (`platform=linux-x64` or `platform=win-x64`). JavaFX ships OS-specific native jars, so a distribution is built for **one** explicit
   platform; the installers refuse a distribution for another one.
2. **Prepare the models**: `make models` / `sh tools/download-models.sh` / `tools\download-models.ps1`. Network needed once; every file
   is verified against the committed `models/checksums.sha256`.
3. **Install**: `make install` / `sh install.sh` / `install.cmd`. Copies the staged build + models into the application directory.
   It **never builds and never downloads** unless you pass the explicit opt-in `--build` (which still refuses to run as root/Administrator).

---

## 2. Prerequisites

| Requirement | Detail |
|---|---|
| Java **21 or newer** | A **JDK** to build, a JRE or JDK to run. Checked by the installers and again by the launchers (a too-old Java gets a clear message). Tested with 21 and 25 on Linux. **No Java is installed or bundled for you.** |
| curl (Linux) / PowerShell 5.1+ (Windows) | model download |
| `sha256sum`, `sed`, `find`, `cp`, `mv`, POSIX `sh` (Linux) | used by `install.sh` (coreutils) |
| PowerShell 5.1 or 7+ (Windows) | used by `install.cmd` for filesystem/PATH safety; ships with Windows 10/11 |
| `git` | to clone |
| GNU Make 4.x | **optional** on Linux; **not needed** on Windows |
| A graphical session | to run the UI |
| Disk / RAM | ≈ 1 GB to build (Gradle cache); ≈ 175 MB installed (of which ≈ 31 MB models); ≥ 4 GB RAM recommended |

Install Java 21 on Debian/Ubuntu/Kali: `sudo apt-get install openjdk-21-jdk` (or `make system-deps` for the JRE). On Windows install a
Java 21+ distribution (for example Eclipse Temurin) and make sure `java` is on PATH or `JAVA_HOME` is set **(untested)**.

---

## 3. Linux: install

```sh
git clone https://github.com/Nil2004n/handwritting_AI.git
cd handwritting_AI

make build models        # as your user: stage the build, fetch + verify models
make install             # per-user install
```

Without `make`:

```sh
./gradlew installDist
sh tools/download-models.sh
sh install.sh
```

One step (opt-in build, still as your user): `sh install.sh --build` (needs `models/` prepared first; uses the network for Gradle).

The installer prints the application directory, the command path, whether the command directory is on `PATH`, and next steps. Start the
app with `notebook-ai`.

**Custom locations** (values with spaces are fine):

```sh
sh install.sh --app-dir "$HOME/apps/notebook ai" --bin-dir "$HOME/bin"
make install APP_DIR="$HOME/apps/notebook ai" BIN_DIR="$HOME/bin"
```

**System-wide copy** (classic layout `<prefix>/lib/notebook-ai` + `<prefix>/bin`; copies only; build first as your user) **(untested)**:

```sh
sudo sh install.sh --prefix /usr/local        # or: sudo make install PREFIX=/usr/local
```

Packagers can stage with `--prefix /usr --destdir /tmp/stage`; the launcher keeps the final `/usr/...` path.

---

## 4. Windows: install

**Status: implemented; not yet executed on real Windows.** A workflow (`.github/workflows/installers.yml`) exists to run the Windows suite on
a real runner; it has not been run.

```bat
git clone https://github.com/Nil2004n/handwritting_AI.git
cd handwritting_AI

gradlew.bat installDist
powershell -NoProfile -ExecutionPolicy Bypass -File tools\download-models.ps1
install.cmd --add-to-path
```

Then open a **new** Command Prompt and run `notebook-ai`.

- `install.cmd` needs no Make and no Administrator rights. If you run it from an elevated prompt it refuses (a per-user install would land in
  the wrong profile, and Gradle must not run elevated).
- It runs `tools\install-windows.ps1` with a **process-scoped** `-ExecutionPolicy Bypass`; no execution policy is changed. If Group Policy
  enforces a policy, PowerShell will block it and you will see the error.
- `--add-to-path` is your explicit consent to a **user** PATH change. Without it (and without answering `y` to the interactive prompt) PATH is not touched
  and the exact wrapper path plus manual instructions are printed. The machine-wide PATH is never changed.
- Build on Windows with `gradlew.bat installDist`; or stage a Windows distribution **from Linux** with
  `./gradlew installDist -PtargetPlatform=win-x64` (output in `build/install-win-x64/notebook-ai`, selected automatically by `install.cmd`).
- Non-ASCII characters in paths depend on the console code page **(untested)**; prefer an ASCII install path if you see garbled names.
- With GNU Make for Windows (cmd.exe) the same targets dispatch to `gradlew.bat`/`install.cmd` **(implemented, untested)**; MSYS2/Git Bash/Cygwin make is refused.

---

## 5. Where things go

Same internal layout everywhere (`<APP>`):

```text
<APP>/.notebook-ai-install     ownership marker: only directories with this file are ever replaced or removed
<APP>/INSTALL.info             version, platform, main-jar SHA-256, install time, Java at install, command path
<APP>/MANIFEST.sha256          SHA-256 of every installed file (checked by --status)
<APP>/dist/bin, dist/lib       the staged Gradle distribution (start script, notebook-ai jar, ONNX Runtime, JavaFX, ...)
<APP>/models/                  det.onnx det.yml rec.onnx rec.yml checksums.sha256
<APP>/bin/notebook-ai.cmd      Windows only: the command wrapper (this bin directory is what goes on PATH)
<APP>/path-state.txt           Windows only: records a PATH entry THIS installer added (used by uninstall)
```

| | Default application dir | Command |
|---|---|---|
| Linux per-user | `${XDG_DATA_HOME:-$HOME/.local/share}/notebook-ai` | `$HOME/.local/bin/notebook-ai` (generated launcher) |
| Linux `--prefix P` | `P/lib/notebook-ai` | `P/bin/notebook-ai` |
| Windows per-user | `%LOCALAPPDATA%\Programs\Notebook-AI` | `<APP>\bin\notebook-ai.cmd` |

Not used by default: the repository, Desktop, Downloads, temporary directories or the current directory. Nothing is written into the
source checkout by an installation. Your projects (`.nbai`), exports and evaluation data live where **you** saved them and are never touched.

The Linux launcher is a small `sh` script with the application path baked in (quoted safely); it works from any directory, checks Java ≥ 21, sets
`NOTEBOOK_AI_MODELS` to `<APP>/models` unless you set it, forwards every argument unchanged and `exec`s the start script, so the exit status is the
application's. The Windows wrapper finds the app relative to itself (relocatable), checks Java ≥ 21 (and fails open if the version cannot be parsed),
sets `NOTEBOOK_AI_MODELS` the same way, forwards `%*` and returns the application's exit code. *Windows limitation:* arguments containing `%`, `^`
or unquoted `&` follow normal cmd.exe parsing rules and may not arrive verbatim.

---

## 6. PATH behaviour

**Linux.** The installer checks whether the command directory is on `PATH`. If not, it prints how to run the command now and the exact line for
your shell (`~/.bashrc`, `~/.zshrc`, `fish_add_path`, or `~/.profile`). It **never edits shell startup files**. If another `notebook-ai` earlier on `PATH`
shadows the new one, it names the actual resolved command and shows how to run this installation explicitly; the other command is not modified. Old
`~/.local` or `/usr/local` installations are never removed automatically.

**Windows.** The user PATH is read from the registry **without expansion** (so `%USERPROFILE%` etc. stay as written), existing entries are kept byte
for byte, the entry is appended once (case-insensitive, trailing-slash- and `%VAR%`-aware duplicate detection), the original value kind is preserved
and running programs are notified. `setx` is never used (it truncates and expands). A PATH containing a double quote, or an install directory containing a
semicolon, is refused rather than rewritten. A new Command Prompt is required; the installer cannot change the PATH of the terminal that started it.
Shadowing is reported via `where notebook-ai` when detectable.

---

## 7. Verify an installation

```sh
sh install.sh --status        # Windows: install.cmd --status
```

It reports the directory, build info and whether every installed file still matches the recorded SHA-256 (exit status 1 if not). Further:

```sh
make verify                   # model sessions load (from the source tree)
make test-install             # installer test suite in temporary locations (Linux)
```

To check an installation against the build it came from (Linux):

```sh
( cd build/install/notebook-ai && find . -type f -exec sha256sum {} + | sort -k2 | sed 's|  \./|  dist/|' ) > /tmp/staged.sum
( cd "${XDG_DATA_HOME:-$HOME/.local/share}/notebook-ai" && find dist -type f -exec sha256sum {} + | sort -k2 ) > /tmp/installed.sum
cmp /tmp/staged.sum /tmp/installed.sum && echo identical
```

(A *rebuild* of the same source changes the jar's hash because jar timestamps are not reproducible; compare an installation only with the build it was copied from.)

---

## 8. Upgrade

```sh
git pull --ff-only
make build models            # as your user
make install                 # same options as before
```

Windows: `gradlew.bat installDist` then `install.cmd` (same options). The installer: validates the new distribution and models first; copies to
an installer-owned temporary directory next to the target; verifies every file by SHA-256; moves the existing installation aside; moves the new one into place;
writes the command; removes the old copy. If any step fails the previous installation is restored and the temporary directory is removed. Old and new libraries are
never mixed. **Limitations:** a Windows installation whose files are locked by a running Notebook-AI cannot be moved aside — close the app and re-run (the
existing installation stays unchanged); replacement is directory-rename based, not transactional across power loss.

---

## 9. Uninstall

```sh
sh install.sh --uninstall [same --app-dir/--bin-dir/--prefix as installed]     # Linux
make uninstall [APP_DIR=… BIN_DIR=…]
install.cmd --uninstall [--app-dir DIR]                                        # Windows
```

Removed: the owned application directory (marker required) and the owned command (Linux). On Windows, a PATH entry is removed **only if this installer added it**
(recorded in `path-state.txt`). Retained and reported: your projects (`.nbai`), exports, evaluation data, shell configuration, the command directory, `Programs`, AppData and every
other program. A directory without the ownership marker, or a command pointing at a different installation, is left alone and reported.

---

## 10. Installer options reference

`sh install.sh [install|uninstall|status] [options]` (Linux), `install.cmd [install|uninstall|status] [options]` (Windows). `--help` prints the same.

| Option | OS | Meaning |
|---|---|---|
| `--app-dir DIR` | both | application directory (absolute path) |
| `--bin-dir DIR` | Linux | directory receiving the `notebook-ai` command |
| `--prefix DIR` / `--destdir DIR` | Linux | classic system layout / packaging root |
| `--dist DIR`, `--models DIR` | both | staged distribution / verified models directory (defaults: next to the installer) |
| `--build` | both | opt-in: run Gradle first (network; refused as root/Administrator) |
| `--replace-legacy` | Linux | allow replacing the command (and, with `--prefix`, the directory) left by the old `make install` |
| `--add-to-path` / `--no-path` | Windows | consent / decline user PATH change (otherwise you are asked when interactive) |
| `--uninstall`, `--status`, `-h/--help` | both | modes / help |

Exit codes: `0` success, `1` failure (the previous installation is untouched, except where a message says otherwise), `2` usage error.

---

## 11. Safety guarantees and their limits

Guaranteed by code **and** exercised by the suites: prerequisites (platform/architecture, complete distribution, checksum-verified models, Java ≥ 21) are checked before any
modification; targets must be absolute, free of `.`/`..`, and are refused if they are a system directory, `/`, your home or its ancestors, `~/.local`, Desktop/Downloads/Documents,
the repository or its parent; non-empty directories without the ownership marker and foreign command files are never modified; copies are verified byte-for-byte; failures roll back; temporary
paths are installer-named and marker-guarded; no `eval`/`Invoke-Expression`, `chmod 777`, recursive `chown`, `setx`.

Not guaranteed: power-loss atomicity; protection from you deleting files inside an installation by hand (use `--status`); Windows file-lock behaviour (**untested**); anything about macOS, ARM or 32-bit systems (rejected).

---

## 12. Why builds must not run as root / Administrator

Once, `sudo make build` ran Gradle as root and created root-owned files in the repository, breaking ordinary builds. Now: `make build/models/test/verify/run/clean` refuse root (and
Administrator on Windows); `install.sh --build` and `install.cmd --build` refuse too; `make install`/`install.sh` only **copy** a staged build, so `sudo sh install.sh --prefix …` cannot create root-owned
build output. Never "fix" ownership with `chmod 777` or recursive `chown`; move an affected directory aside as your user or remove one exact, reviewed path.

---

## 13. Models and network use

The models (PaddleOCR PP-OCRv6_small, Apache-2.0) are downloaded by an explicit command — `tools/download-models.sh` or `tools\download-models.ps1` — from pinned Hugging Face commits (the Windows script uses the
**same** pinned commits and the committed `models/checksums.sha256`; nothing new is trusted). Existing valid files are kept (no network). The installers copy only already-verified files and re-check
checksums; they never download. The application never uses the network at run time. Gradle downloads dependencies from Maven Central at **build** time only.

---

## 14. Migrating from the old `make install` layout

The previous workflow installed to `<prefix>/lib/notebook-ai/{bin,lib,models}` (for example `~/.local/lib/notebook-ai` or `/usr/local/lib/notebook-ai`) with a launcher at `<prefix>/bin/notebook-ai`. The new default
per-user layout is different (`~/.local/share/notebook-ai`), so **nothing is migrated or deleted automatically**:

1. `sh install.sh` detects an old launcher at `~/.local/bin/notebook-ai` and stops with an explanation. Re-run with `--replace-legacy` to point the command at the new install (the old files stay).
2. The installer reports any old-layout directory it finds (`~/.local`, `/usr/local`, `/usr`) with the exact removal command, e.g. `sh install.sh --uninstall --prefix "$HOME/.local" --replace-legacy` (or `make uninstall PREFIX="$HOME/.local" REPLACE_LEGACY=1`; use `sudo` only for `/usr/local` or `/usr`). It removes exactly the old structure (`bin/ lib/ models/`) and the old command, nothing else.
3. To keep the *old* layout deliberately: `sh install.sh --prefix "$HOME/.local" --replace-legacy` replaces the old application directory in place (this is the only way the installer touches an old-layout directory).
4. Note: `make install` without `PREFIX` previously meant `/usr/local`; it now means the per-user install. `sudo make install` is refused with an explanation — use `sudo make install PREFIX=/usr/local`.

---

## 15. Environment variables

| Variable | Used by | Effect |
|---|---|---|
| `NOTEBOOK_AI_MODELS` | app / launchers | model directory (must hold the four files + `checksums.sha256`); defaults to `<APP>/models` in installed launchers |
| `JAVA_HOME` | launchers / installers | Java to use; must contain `bin/java` (`bin\java.exe`) |
| `NOTEBOOK_AI_JDK_FALLBACK` | Linux launcher/installer | JDK 21 home tried when the `PATH` Java is too old (default `/usr/lib/jvm/java-21-openjdk-amd64`) |
| `JAVA_OPTS`, `NOTEBOOK_AI_OPTS` | start scripts | extra JVM options, e.g. `JAVA_OPTS=-Xmx4g` |
| `XDG_DATA_HOME` | `install.sh` | base for the default application directory |
| `LOCALAPPDATA` | `install.cmd` | base for the default Windows application directory |
| `APP_DIR`, `BIN_DIR`, `PREFIX`, `DESTDIR`, `DIST`, `MODELS_DIR`, `PLATFORM`, `ADD_TO_PATH`, `NO_PATH`, `REPLACE_LEGACY` | `make` | install/build options (values pass through the environment, so spaces are safe) |
| `NBAI_TEST_*` | test suites only | test seams, honoured only with `NBAI_TEST_MODE=1`; never set them for real installs |

---

## 16. Running without installing, headless, offline

- From the source tree: `make run` or `./gradlew run` (models from `./models` or `NOTEBOOK_AI_MODELS`).
- Headless checks need no display: `./gradlew build`, `verifyOcrSetup`, `recognizeImage`, `evaluate`, and both installer suites.
- UI tests on a private display (Linux): `Xvfb :77 -screen 0 1600x1000x24 &` then `DISPLAY=:77 notebook-ai` (use your own display number; never point tests at your real desktop).
- Offline: build once online, then copy `build/install/notebook-ai` and the verified `models/` to the target and run the installer there with `--dist`/`--models` **(offline transfer untested)**.

---

## 17. Supported platforms matrix

| Component | Supported & tested | Implemented, untested | Refused / unsupported |
|---|---|---|---|
| OS / arch | Linux x86-64 | Windows x64 | macOS, Linux aarch64, Windows ARM64/x86 (clear error; native artifacts are not interchangeable) |
| Shell for the Linux installer | POSIX `sh` (dash), bash for the user's shell hints | other POSIX shells | — |
| Windows entry point | — | `install.cmd` in cmd.exe; PowerShell 5.1+ | Windows without PowerShell |
| Make | GNU Make 4.x on Linux | GNU Make using cmd.exe on Windows | MSYS2 / Git Bash / Cygwin make, NMake, BSD make |
| Java | 21 and 25 | — | < 21 (refused with a message) |

JavaFX 21.0.7 is used with the `linux` and `win` (x64) classifiers; ONNX Runtime 1.22.0 bundles the matching natives for linux-x64 and win-x64.

---

## 18. Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `refusing to run 'make build' as root` | guard working as designed | run as your normal user |
| `no staged distribution at …` | nothing built, or built elsewhere | `make build` / `gradlew.bat installDist` as your user, or pass `--dist` |
| `the staged distribution is for 'win-x64', but this machine is 'linux-x64'` | wrong-OS build | rebuild on this OS, or stage with `-PtargetPlatform=` for the *target* OS and install it there |
| `unsupported architecture` / `unsupported operating system` | only Linux x86-64 and Windows x64 are supported | use a supported machine |
| `model file … is missing` / `checksum verification failed` | models absent or corrupted | `make models` (Windows: `tools\download-models.ps1`) |
| `Java N found … Java 21 or newer is required` / `no Java found` | old or missing Java | install Java 21+, or set `JAVA_HOME` |
| `'…' exists and is not a notebook-ai installation` | target dir not owned | choose another `--app-dir`; nothing was changed |
| `… is the command from the OLD 'make install'` | legacy launcher at the command path | see [§14](#14-migrating-from-the-old-make-install-layout) |
| `'notebook-ai' … resolves to X, NOT the command just installed` | another command earlier on PATH | run the printed explicit path or reorder PATH; the other command is untouched |
| `notebook-ai: command not found` (Linux) | command dir not on PATH | add the printed line to your shell startup file, open a new terminal |
| `'notebook-ai' is not recognized` (Windows) | PATH declined, or terminal opened before | `install.cmd --add-to-path`, open a **new** Command Prompt, or use the full path |
| `cannot replace … a file in it is probably in use` (Windows) | running app locks files | close Notebook-AI/terminals, re-run; existing install unchanged |
| `refusing to run elevated` (Windows) | Administrator prompt | open a normal Command Prompt |
| PowerShell blocks the script | Group Policy enforces an execution policy | ask your administrator; the installer will not change policy |
| `Unsupported class file major version` while building | Gradle on too-new Java | use JDK 21 (the Makefile pins it when `/usr/lib/jvm/java-21-openjdk-amd64` exists) |
| App says an image is too large for memory | memory guard | smaller image or `JAVA_OPTS=-Xmx4g` |
| `Permission denied` removing `build/…` | earlier root-owned artefacts | see [§12](#12-why-builds-must-not-run-as-root--administrator) |

Diagnostics for a bug report (no private data): `java -version`, `uname -a` / `ver`, the exact failing command and its full output, `sh install.sh --status`.
