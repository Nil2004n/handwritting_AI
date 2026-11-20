#!/bin/sh
# NotebookOCR Android environment helper (idempotent — safe to source twice).
# Usage: . /home/kali/Desktop/handwritting_AI/env/android.sh
# (Adjust the ANDROID_HOME path if your SDK lives elsewhere.)

if [ -z "$ANDROID_HOME" ]; then
  export ANDROID_HOME="$HOME/Android/Sdk"
fi
if [ -z "$ANDROID_SDK_ROOT" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
case ":$PATH:" in
  *":$ANDROID_HOME/cmdline-tools/latest/bin:"*) ;;
  *) export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH" ;;
esac
# Gradle daemon JVM: JDK 21 (project needs 17+; system default stays Java 25).
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
