# Test matrix (updated 2026-11-20 — first real build)

## Automated (all run, all pass)
- `npx tsc --noEmit` — clean (exit 0).
- `npx eslint .` — clean (0 errors, 0 warnings).
- `npx jest --runInBand` — 6 suites / 38 tests pass (fixtures, coordinates,
  mock engine, editing, equation preview, App extract-edit flow).
- `cd android && ./gradlew assembleDebug` — BUILD SUCCESSFUL (5m 46s,
  93 tasks), Gradle 9.4.1 wrapper, daemon JVM JDK 21.

## APK verification (built 2026-11-20, `aapt dump badging`)
- File: `android/app/build/outputs/apk/debug/app-debug.apk`
  (git-ignored build output, 125,320,135 bytes — non-empty)
- SHA-256: `b5efbf0684652dd17d106d037d3a2b17136f1db8ec326c10ba3e7f12e4797429`
- applicationId `com.notebookocr`, versionCode 1, versionName `1.0`,
  label `NotebookOCR`, `application-debuggable`
- `sdkVersion:'24'` (minSdk 24), `targetSdkVersion:'36'`, compiled against 37
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

## Manual flows (still pending — no device/emulator)
- Open each sample, extract, select blocks, verify highlight alignment on a
  real screen size; edit flows; session persistence across navigation;
  equation error box; disabled import buttons.

## Emulator attempt (2026-11-20 — failed at boot, host restriction)
- Installed: `emulator` 37.3.10.0, `system-images;android-36;default;x86_64`
  rev 2 (stable, default tag, host-arch x86_64; APK contains x86_64 — ABI
  compatible). AVD `notebookocr-test` (Pixel, swiftshader_indirect,
  no-window/no-audio, 1536 MB) created manually after `avdmanager create`
  failed on a missing `devices.xml` in the image dir (tooling quirk,
  non-fatal — AVD listed fine via `emulator -list-avds`).
- Boot refused twice (exit 1, zero adb devices); see KNOWN_ISSUES.md #4.
- Consequence: install, launch, Metro, and all 11 UI checks below are
  UNVERIFIED on-device. The jest App flow test remains the only executed
  UI verification.

## On-device UI checks (all unverified — no target)
1. Demo list appears — UNVERIFIED
2. Demo-mode banner visible — UNVERIFIED
3. All five samples open — UNVERIFIED
4. Extract shows loading then results — UNVERIFIED
5. Block selection highlights correct regions — UNVERIFIED
6. Text edits persist across navigation — UNVERIFIED
7. Table cells editable, blank cell intact — UNVERIFIED
8. Equation preview with network disabled — UNVERIFIED
9. Invalid equation input shows readable error — UNVERIFIED
10. Reset requires confirmation — UNVERIFIED
11. Raw recognition data unchanged — UNVERIFIED

## Debug-vs-standalone note
Not reached: no on-device run happened, so no claim is made either way.
For the record — a debug APK served by Metro does NOT prove a standalone
offline APK; that requires a release (or at least bundled-JS) build plus a
network-isolated launch test. No production signing configured or claimed.

## Device checks pending
- `adb install` + launch on a physical device or KVM-capable host, then the
  11 checks above (disable device network for check 8).

## Fixture coverage (unchanged)
prose (transcription error) / numericals (ambiguity flag, units, decimal,
negative, exponent) / table (4x3, blank cell, decimal) / equation
(frac, sup, sub, sqrt) / mixed (text+table+equation order).
