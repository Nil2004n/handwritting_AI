# notebook-ai — install, test and run the offline Java OCR desktop app.
#
# Recommended flow:
#   make build models          # as your normal user (compiles, fetches models)
#   make install               # copies files (rerun with sudo for /usr/local)
#   sudo make install          # system-wide install (builds nothing as root)
#
# Other targets:
#   make test                  # full clean build + unit tests
#   make verify                # load ONNX sessions, print I/O metadata
#   make recognize IMAGE=...   # OCR one image (JPG/PNG)
#   make install PREFIX=$HOME/.local   # user-local install, no root needed
#   make uninstall [PREFIX=...]
#   make system-deps           # apt-install openjdk-21-jre (prompts for sudo)
#   make run | make clean | make help
#
# Installed layout: $(PREFIX)/lib/notebook-ai/{bin,lib,models},
# launcher at $(PREFIX)/bin/notebook-ai (ensure it is on PATH).
# Models are bundled at install time; the app never downloads at runtime.

PREFIX ?= /usr/local
DESTDIR ?=
APP_DIR = $(DESTDIR)$(PREFIX)/lib/notebook-ai
BIN_DIR = $(DESTDIR)$(PREFIX)/bin

# Gradle itself must run on a JVM it supports (Gradle 8.14: up to Java 24).
# Pin the daemon JVM when a JDK 21 is present so a Java-25 default — or a
# sudo environment without the user's ~/.gradle/gradle.properties — never
# breaks the build with "Unsupported class file major version".
GRADLE_FLAGS :=
ifneq (,$(wildcard /usr/lib/jvm/java-21-openjdk-amd64))
GRADLE_FLAGS += -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64
endif
GRADLE = ./gradlew $(GRADLE_FLAGS)

.PHONY: help build models test verify recognize run clean install uninstall system-deps check-dist check-models

help:
	@echo "Targets: build test verify recognize run clean install uninstall system-deps"
	@echo "  make build models         compile, stage installDist, fetch models (as user)"
	@echo "  make test                 clean build + unit tests"
	@echo "  make verify               ./gradlew verifyOcrSetup"
	@echo "  make recognize IMAGE=...  OCR one image (JPG/PNG)"
	@echo "  make install [PREFIX=...] copy staged app + models + launcher"
	@echo "                            (run 'make build models' first; sudo only for copy)"
	@echo "  make uninstall [PREFIX=...] remove installed files"
	@echo "  make system-deps          apt-install openjdk-21-jre (sudo)"
	@echo "  make run                  launch the JavaFX UI (needs display)"
	@echo "  make clean                gradle clean"

build:
	$(GRADLE) installDist

models:
	sh tools/download-models.sh

test:
	$(GRADLE) clean build

verify:
	$(GRADLE) verifyOcrSetup

recognize:
	@if [ -z "$(IMAGE)" ]; then echo "Usage: make recognize IMAGE=/path/to.jpg"; exit 2; fi
	$(GRADLE) recognizeImage -Pimage="$(IMAGE)"

run:
	$(GRADLE) run

clean:
	$(GRADLE) clean

# System Java 21 runtime (explicit opt-in; prompts for sudo).
system-deps:
	@if command -v apt-get >/dev/null 2>&1; then \
		sudo apt-get update && sudo apt-get install -y openjdk-21-jre; \
	else \
		echo "No apt-get found. Install a Java 21+ runtime (JRE) manually."; exit 1; \
	fi

# The staged distribution must already exist (built as the normal user, so
# `sudo make install` never compiles or downloads anything as root).
check-dist:
	@if [ ! -x build/install/notebook-ai/bin/notebook-ai ]; then \
		echo "error: staged app missing; run 'make build' first (as your normal user)."; \
		exit 1; \
	fi

check-models:
	@if ! (cd models && sha256sum -c checksums.sha256 >/dev/null 2>&1); then \
		echo "error: models missing or invalid; run 'make models' first (as your normal user)."; \
		exit 1; \
	fi

install: check-dist check-models
	@if [ ! -w "$(DESTDIR)$(PREFIX)" ]; then \
		echo "error: $(DESTDIR)$(PREFIX) is not writable; rerun with sudo, e.g.:"; \
		echo "  sudo make install"; \
		exit 1; \
	fi
	@case "$(APP_DIR)" in */lib/notebook-ai) ;; *) echo "error: refusing unsafe APP_DIR '$(APP_DIR)'"; exit 1;; esac
	rm -rf "$(APP_DIR)/bin" "$(APP_DIR)/lib"   # app-owned dirs only: no stale jars survive an upgrade
	install -d "$(APP_DIR)/models" "$(BIN_DIR)"
	cp -r build/install/notebook-ai/bin build/install/notebook-ai/lib "$(APP_DIR)/"
	cp models/det.onnx models/det.yml models/rec.onnx models/rec.yml \
		models/checksums.sha256 "$(APP_DIR)/models/"
	@printf '%s\n' \
		'#!/bin/sh' \
		'# notebook-ai launcher (installed by make install).' \
		'# Uses bundled models unless NOTEBOOK_AI_MODELS overrides them.' \
		'APP_DIR="$(PREFIX)/lib/notebook-ai"' \
		'if [ -z "$$NOTEBOOK_AI_MODELS" ]; then' \
		'  export NOTEBOOK_AI_MODELS="$$APP_DIR/models"' \
		'fi' \
		'if [ -z "$$JAVA_HOME" ] && [ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]; then' \
		'  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64' \
		'fi' \
		'exec "$$APP_DIR/bin/notebook-ai" "$$@"' \
		> "$(BIN_DIR)/notebook-ai"
	chmod 755 "$(BIN_DIR)/notebook-ai"
	@echo "Installed: $(BIN_DIR)/notebook-ai  (ensure $(PREFIX)/bin is on PATH)"
	@first="$$(command -v notebook-ai 2>/dev/null)"; \
	if [ -n "$$first" ] && [ "$$first" != "$(BIN_DIR)/notebook-ai" ] && [ -z "$(DESTDIR)" ]; then \
		echo "WARNING: 'notebook-ai' on your PATH resolves to $$first, NOT the copy just installed."; \
		echo "         That older copy will keep running. Update or remove it, e.g.:"; \
		echo "           make install PREFIX=$$(dirname $$(dirname $$first))"; \
	fi

uninstall:
	rm -rf "$(APP_DIR)" "$(BIN_DIR)/notebook-ai"
	@echo "Removed notebook-ai from $(DESTDIR)$(PREFIX)"
