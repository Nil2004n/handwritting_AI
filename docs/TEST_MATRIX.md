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

## Device checks pending
- `adb install` + launch on a physical device or KVM-capable host.
- Emulator: infeasible here (no nested virt — see KNOWN_ISSUES.md #4).

## Fixture coverage (unchanged)
prose (transcription error) / numericals (ambiguity flag, units, decimal,
negative, exponent) / table (4x3, blank cell, decimal) / equation
(frac, sup, sub, sqrt) / mixed (text+table+equation order).
