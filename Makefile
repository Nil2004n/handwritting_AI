# notebook-ai — build, test and install tasks. One Makefile, shared target names on Linux and Windows.
#
# SUPPORT MATRIX (what is actually claimed)
#   GNU Make 4.x + Linux x86-64 + sh ........ TESTED (all targets)
#   GNU Make    + Windows x64 + cmd.exe ..... dispatch to gradlew.bat / install.cmd is implemented but NOT TESTED
#   Windows without Make ................... fully supported WITHOUT this file: use install.cmd and gradlew.bat
#   MSYS2 / Git Bash / Cygwin make, NMake,
#   PowerShell-as-shell, macOS, BSD ........ UNSUPPORTED: detected and refused with a message
# Make is OPTIONAL. The installers (install.sh, install.cmd) work without it and own all install logic;
# this file only dispatches to them and to Gradle. Building and installing are separate steps.
#
# Typical flow (as your NORMAL user, never sudo / Administrator):
#   make build models              # compile + stage the distribution; fetch/verify models (network once)
#   make install                   # per-user install (see install.sh --help for the layout)
#   make uninstall                 # remove the owned installation
#
# Custom locations (any value, including spaces, is passed through the environment, never re-parsed):
#   make install APP_DIR=/path/app BIN_DIR=/path/bin          (Linux)
#   make install APP_DIR=C:\path\Notebook-AI                  (Windows; also ADD_TO_PATH=1 or NO_PATH=1)
#   make install PREFIX=/usr/local                            (Linux system-wide classic layout; copy only; use sudo)
#   make build PLATFORM=win-x64                               (stage a Windows distribution, e.g. from Linux)

PREFIX ?=
DESTDIR ?=
APP_DIR ?=
BIN_DIR ?=
DIST ?=
MODELS_DIR ?=
PLATFORM ?=
ADD_TO_PATH ?=
NO_PATH ?=
REPLACE_LEGACY ?=
export PREFIX DESTDIR APP_DIR BIN_DIR DIST MODELS_DIR

# ---- environment detection (explicit; no guessing) ----
NBAI_ENV_ERROR :=
ifeq ($(origin MAKE_VERSION),undefined)
NBAI_ENV_ERROR := This Makefile needs GNU Make 4.x (MAKE_VERSION is undefined). On Windows use install.cmd and gradlew.bat directly.
endif
ifeq ($(OS),Windows_NT)
NBAI_HOST := windows
ifneq ($(strip $(MSYSTEM)$(CYGWIN)$(MSYS)),)
NBAI_ENV_ERROR := MSYS2 / Git Bash / Cygwin make is not a supported combination here. Use install.cmd and gradlew.bat from a normal Command Prompt.
endif
SHELL := cmd.exe
else
NBAI_HOST := $(shell uname -s 2>/dev/null)
ifneq ($(NBAI_HOST),Linux)
NBAI_ENV_ERROR := Unsupported system '$(NBAI_HOST)'. Supported: Linux x86-64 (tested) and Windows x64 via cmd.exe (untested).
endif
endif

# Effective user id; overridable ONLY so tools/verify-install.sh can exercise the root guard without being root.
CURRENT_UID ?= $(shell id -u 2>/dev/null)

ifeq ($(NBAI_HOST),windows)
GRADLEW = gradlew.bat
PLAT_FLAG = $(if $(PLATFORM),-PtargetPlatform=$(PLATFORM),)
INSTALLER = install.cmd
MODELS_CMD = powershell -NoProfile -ExecutionPolicy Bypass -File tools/download-models.ps1
INSTALL_ARGS = $(if $(APP_DIR),--app-dir "%APP_DIR%",) $(if $(DIST),--dist "%DIST%",) $(if $(MODELS_DIR),--models "%MODELS_DIR%",) $(if $(ADD_TO_PATH),--add-to-path,) $(if $(NO_PATH),--no-path,)
UNINSTALL_ARGS = $(if $(APP_DIR),--app-dir "%APP_DIR%",)
TEST_INSTALL_CMD = powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-install-windows.ps1
else
# Gradle itself must run on a JVM it supports (Gradle 8.14: up to Java 24). Pin the daemon JVM to JDK 21 when
# present so a newer default Java cannot break the build with "Unsupported class file major version".
GRADLE_FLAGS :=
ifneq (,$(wildcard /usr/lib/jvm/java-21-openjdk-amd64))
GRADLE_FLAGS += -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64
endif
GRADLEW = ./gradlew $(GRADLE_FLAGS)
PLAT_FLAG = $(if $(PLATFORM),-PtargetPlatform=$(PLATFORM),)
INSTALLER = sh install.sh
MODELS_CMD = sh tools/download-models.sh
# Values reach install.sh through the environment ("$$VAR" is expanded by the shell, never by make): spaces are safe.
INSTALL_ARGS = $(if $(PREFIX),--prefix "$$PREFIX",) $(if $(DESTDIR),--destdir "$$DESTDIR",) $(if $(APP_DIR),--app-dir "$$APP_DIR",) $(if $(BIN_DIR),--bin-dir "$$BIN_DIR",) $(if $(DIST),--dist "$$DIST",) $(if $(MODELS_DIR),--models "$$MODELS_DIR",) $(if $(REPLACE_LEGACY),--replace-legacy,)
UNINSTALL_ARGS = $(if $(PREFIX),--prefix "$$PREFIX",) $(if $(DESTDIR),--destdir "$$DESTDIR",) $(if $(APP_DIR),--app-dir "$$APP_DIR",) $(if $(BIN_DIR),--bin-dir "$$BIN_DIR",) $(if $(REPLACE_LEGACY),--replace-legacy,)
TEST_INSTALL_CMD = sh tools/verify-install.sh
endif

.PHONY: help build models test verify recognize run clean install uninstall status test-install system-deps \
        dist dist-linux dist-windows verify-dist package package-linux package-windows dist-clean test-dist \
        check-env check-not-root

help:
	@echo "notebook-ai Makefile (GNU Make). Make is optional: Windows users can use install.cmd and gradlew.bat directly."
	@echo ""
	@echo "Targets:"
	@echo "  build             compile and stage the distribution (gradle installDist); PLATFORM=win-x64 stages Windows"
	@echo "  models            fetch + verify the OCR models (network needed once)"
	@echo "  test              clean build + unit tests"
	@echo "  verify            load the ONNX sessions and print their I/O (gradle verifyOcrSetup)"
	@echo "  install           install the STAGED build for the current user (never builds; delegates to the installer)"
	@echo "  uninstall         remove the owned installation (projects and exports are never touched)"
	@echo "  status            show and verify the installation"
	@echo "  clean             delete the Gradle build output (build/) only"
	@echo "Release distributions (generated, git-ignored; need 'make models' first; never run as root):"
	@echo "  dist-linux        generate linux-dist/   (linux-x64)     dist-windows   generate win-dist/ (win-x64, cross-assembled)"
	@echo "  dist              both                   verify-dist    re-validate the generated directories"
	@echo "  package           release-output/notebook-ai-<ver>-{linux,windows}-x64.zip + .sha256 (only from validated dist dirs)"
	@echo "  package-linux | package-windows | dist-clean (removes only output this build generated) | test-dist"
	@echo "  run | recognize IMAGE=... | test-install | system-deps (Debian/Ubuntu)"
	@echo ""
	@echo "Install options: APP_DIR= BIN_DIR= PREFIX= (system layout, use sudo) DESTDIR= DIST= MODELS_DIR= REPLACE_LEGACY=1"
	@echo "                 Windows: ADD_TO_PATH=1 | NO_PATH=1"
	@echo "Support: Linux x86-64 + GNU Make 4.x tested; Windows x64 dispatch implemented, untested; MSYS2/Git Bash/Cygwin/NMake unsupported."
	@echo "Never run build targets as root/Administrator: root-owned build files break later builds."
	@echo "Detected: host=$(NBAI_HOST) make=$(MAKE_VERSION)"
	$(if $(NBAI_ENV_ERROR),@echo ENVIRONMENT PROBLEM: $(NBAI_ENV_ERROR),@echo Environment: OK)

check-env:
ifneq ($(NBAI_ENV_ERROR),)
	@echo "error: $(NBAI_ENV_ERROR)" 1>&2
	@exit 1
else
	@:
endif

ifeq ($(NBAI_HOST),windows)
# `net session` succeeds only in an elevated (Administrator) prompt.
check-not-root: check-env
	@net session >nul 2>&1 & if not errorlevel 1 (echo error: refusing to run Gradle as Administrator - open a normal Command Prompt 1>&2 & exit /b 1)
	@exit /b 0
else
# Gradle/download targets must never run as root: they would create root-owned files in this repository
# (this happened once with `sudo make build`). Only install/uninstall (plain file copies) may need sudo.
check-not-root: check-env
	@if [ "$(CURRENT_UID)" = "0" ]; then \
		echo "error: refusing to run 'make $(MAKECMDGOALS)' as root: it runs Gradle/downloads and would create"; \
		echo "       root-owned files in this repository. Run it as your normal user:"; \
		echo "         make build models           # normal user"; \
		echo "         make install                # per-user install, no sudo"; \
		echo "         sudo make install PREFIX=/usr/local   # optional system-wide copy (never builds)"; \
		exit 1; \
	fi
endif

build: check-not-root
	$(GRADLEW) $(PLAT_FLAG) installDist

models: check-not-root
	$(MODELS_CMD)

test: check-not-root
	$(GRADLEW) clean build

verify: check-not-root
	$(GRADLEW) verifyOcrSetup

recognize: check-not-root
ifeq ($(NBAI_HOST),windows)
	@if "$(IMAGE)"=="" (echo Usage: make recognize IMAGE=C:\path\to.jpg & exit /b 2)
else
	@if [ -z "$(IMAGE)" ]; then echo "Usage: make recognize IMAGE=/path/to.jpg"; exit 2; fi
endif
	$(GRADLEW) recognizeImage -Pimage="$(IMAGE)"

run: check-not-root
	$(GRADLEW) run

# Deletes the Gradle build output (build/) only. Never touches projects, exports, eval data or models.
clean: check-not-root
	$(GRADLEW) clean

# Release distributions: portable packaging logic lives in Gradle (works on Linux and Windows hosts); Make only dispatches.
dist-linux: check-not-root
	$(GRADLEW) distLinux

dist-windows: check-not-root
	$(GRADLEW) distWindows

dist: check-not-root
	$(GRADLEW) dist

verify-dist: check-not-root
	$(GRADLEW) verifyDist

package: check-not-root
	$(GRADLEW) package

package-linux: check-not-root
	$(GRADLEW) packageLinux

package-windows: check-not-root
	$(GRADLEW) packageWindows

dist-clean: check-not-root
	$(GRADLEW) cleanDist

# End-to-end distribution tests (extract the archive, run, install, uninstall); needs 'make package-linux' first.
test-dist: check-env
ifeq ($(NBAI_HOST),windows)
	powershell -NoProfile -ExecutionPolicy Bypass -File tools/test-dist-windows.ps1
else
	sh tools/test-dist.sh
endif

# Installing copies an already staged build. It never builds or downloads, so `sudo make install PREFIX=...`
# cannot create root-owned build files. All logic (validation, ownership, upgrade, rollback) lives in the installer.
install: check-env
	$(INSTALLER) $(INSTALL_ARGS)

uninstall: check-env
	$(INSTALLER) --uninstall $(UNINSTALL_ARGS)

status: check-env
	$(INSTALLER) --status $(UNINSTALL_ARGS)

test-install: check-env
	$(TEST_INSTALL_CMD)

# System Java 21 runtime (explicit opt-in; prompts for sudo). Linux/Debian family only.
system-deps: check-env
ifeq ($(NBAI_HOST),windows)
	@echo "error: system-deps is Linux-only. On Windows install a Java 21+ runtime (e.g. Temurin) yourself." 1>&2
	@exit 1
else
	@if command -v apt-get >/dev/null 2>&1; then \
		sudo apt-get update && sudo apt-get install -y openjdk-21-jre; \
	else \
		echo "No apt-get found. Install a Java 21+ runtime (JRE) manually."; exit 1; \
	fi
endif
