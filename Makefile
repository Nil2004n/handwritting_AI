# notebook-ai — install, test and run the offline Java OCR desktop app.
#
# Usage:
#   make build                  compile + installDist (no root needed)
#   make test                   full clean build + unit tests
#   make verify                 load ONNX sessions, print I/O metadata
#   make recognize IMAGE=...    OCR one image via the CLI
#   make install                install globally (PREFIX=/usr/local;
#                               rerun with sudo if the prefix is not writable)
#   make install PREFIX=$HOME/.local   user-local install, no root needed
#   make uninstall               remove installed files
#   make system-deps             install the Java 21 runtime via apt (asks sudo)
#   make run | make clean | make help
#
# Installed layout: $(PREFIX)/lib/notebook-ai/{bin,lib,models},
# launcher at $(PREFIX)/bin/notebook-ai (ensure it is on PATH).
# Models are bundled at install time; the app never downloads at runtime.

PREFIX ?= /usr/local
DESTDIR ?=
APP_DIR = $(DESTDIR)$(PREFIX)/lib/notebook-ai
BIN_DIR = $(DESTDIR)$(PREFIX)/bin

.PHONY: help build models test verify recognize run clean install uninstall system-deps

help:
	@echo "Targets: build test verify recognize run clean install uninstall system-deps"
	@echo "  make build                  compile + stage installDist"
	@echo "  make test                   clean build + unit tests"
	@echo "  make verify                 ./gradlew verifyOcrSetup"
	@echo "  make recognize IMAGE=path   OCR one image (JPG/PNG)"
	@echo "  make install [PREFIX=...]   install app + models + launcher"
	@echo "  make uninstall [PREFIX=...] remove installed files"
	@echo "  make system-deps            apt-install openjdk-21-jre (sudo)"
	@echo "  make run                    launch the JavaFX UI (needs display)"
	@echo "  make clean                  gradle clean"

build:
	./gradlew installDist

models:
	sh tools/download-models.sh

test:
	./gradlew clean build

verify:
	./gradlew verifyOcrSetup

recognize:
	@if [ -z "$(IMAGE)" ]; then echo "Usage: make recognize IMAGE=/path/to.jpg"; exit 2; fi
	./gradlew recognizeImage -Pimage="$(IMAGE)"

run:
	./gradlew run

clean:
	./gradlew clean

# System Java 21 runtime (explicit opt-in; prompts for sudo).
system-deps:
	@if command -v apt-get >/dev/null 2>&1; then \
		sudo apt-get update && sudo apt-get install -y openjdk-21-jre; \
	else \
		echo "No apt-get found. Install a Java 21+ runtime (JRE) manually."; exit 1; \
	fi

install: build models
	@if [ ! -w "$(DESTDIR)$(PREFIX)" ]; then \
		echo "error: $(DESTDIR)$(PREFIX) is not writable; rerun with sudo, e.g.:"; \
		echo "  sudo make install"; \
		exit 1; \
	fi
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

uninstall:
	rm -rf "$(APP_DIR)" "$(BIN_DIR)/notebook-ai"
	@echo "Removed notebook-ai from $(DESTDIR)$(PREFIX)"
