# Known issues (updated 2026-11-20 — toolchain provisioned)

## Resolved
1. ~~No Android SDK~~ — FIXED: user-scoped SDK at `~/Android/Sdk`
   (cmdline-tools 22.0, platform-tools, android-37.2, build-tools 37.0.0);
   `./gradlew assembleDebug` → BUILD SUCCESSFUL, APK verified (see
   TEST_MATRIX.md). `sdk.dir` in git-ignored `android/local.properties`.
2. ~~JDK mismatch~~ — FIXED without touching the system default: Gradle
   daemon JVM pinned to existing JDK 21 via `~/.gradle/gradle.properties`
   (`java -version` still 25; `./gradlew --version` daemon JVM is 21).
3. ~~npm 12 `--allow-scripts` vs RN CLI init~~ — unchanged workaround, still
   documented: init with `--skip-install --skip-git-init`, then plain
   `npm install` inside the project.

## Still open
4. **No emulator / device testing — verified impossible on this host
   (2026-11-20).** Guest VM without nested virtualization (no vmx/svm,
   no /dev/kvm, `emulator -accel-check`: "KVM requires a CPU that supports
   vmx or svm"). Installed `emulator` 37.3.10.0 +
   `system-images;android-36;default;x86_64` and created AVD
   `notebookocr-test` (Pixel, x86_64, swiftshader, 1536 MB) to attempt a
   bounded headless boot. Two hard refusals, both exit=1, no device:
   `-accel off` → "Software CPU emulation is not supported… (or set
   ANDROID_I_WANT_MY_TCG=yes)"; with `ANDROID_I_WANT_MY_TCG=yes` →
   "x86_64 emulation currently requires hardware acceleration!" Full logs:
   `/tmp/opencode/emulator.log`, `/tmp/opencode/emulator-tcg.log`
   (outside repo). ARM-ABI emulation on this x86_64 host was not attempted
   (forbidden: unusably slow, proves nothing about the x86_64 APK). AVD
   definition kept at `~/.android/avd/notebookocr-test.*` for reuse on a
   KVM-capable host. Alternative: `adb install` the debug APK on a physical
   device. APK build success stands on its own.
5. Test renderer counts: the RN jest preset duplicates `testID` on composite
   + host nodes; UI tests query composite nodes via
   `src/testUtils/queries.ts`. Revisit if the preset changes its mocks.
6. Equation preview is a deliberate subset (`\frac \sqrt \bar ^ _ \pm \times
   \cdot \leq \geq \neq` + groups); anything else shows the local error box.
7. No persistence: edits live for the app session only (Phase 0 design).
