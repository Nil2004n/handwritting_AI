# Handoff (toolchain provisioned 2026-11-20 — real debug APK built)

## Completed this session
- Audited host (Kali x86_64 VM, 5 CPU, ~9 GB RAM) and project requirements
  (AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.0, compileSdk 37, buildTools 37.0.0).
- Reused existing full JDK 21 for the Gradle daemon (user-scoped
  `~/.gradle/gradle.properties`); system Java still 25; no global change.
- Installed user-scoped Android SDK (`~/Android/Sdk`, checksum-verified
  cmdline-tools 22.0 + platform-tools + android-37.2 + build-tools 37.0.0;
  licenses accepted with owner approval; no sudo).
- `android/local.properties` created (git-ignored); `env/android.sh`
  activation helper added (idempotent).
- Full check suite green: tsc, eslint, `jest --runInBand` 38/38.
- `./gradlew assembleDebug` → BUILD SUCCESSFUL; APK verified via aapt +
  SHA-256 (see TEST_MATRIX.md). Emulator ruled out (no nested virt).

## Files changed
- New: `env/android.sh`, `docs/ENVIRONMENT_SETUP.md`
- Updated: `docs/KNOWN_ISSUES.md`, `docs/TEST_MATRIX.md`, this HANDOFF
- Machine-local / ignored (never committed): `~/Android/Sdk`,
  `~/.gradle/gradle.properties`, `android/local.properties`, `android/app/build/`
- No RN/Gradle/Kotlin versions changed; no generated native code edited.

## Commands actually run
- `./gradlew --version` / `--stop` (daemon JVM now JDK 21)
- `sdkmanager --licenses` (accepted), install platform-tools,
  `platforms;android-37.2`, `build-tools;37.0.0`
- `npm run typecheck`, `npm run lint`, `npx jest --runInBand`
- `cd android && ./gradlew assembleDebug` → BUILD SUCCESSFUL in 5m 46s
- `aapt dump badging …/app-debug.apk`; `sha256sum` → b5efbf06…79429

## Tests passed / not run
- Passed: typecheck, lint, 38 jest tests, Gradle debug build, APK metadata
  + non-empty + hash verification.
- Not run: install/launch on device or emulator (no target available).

## Known failures
- None in the build. Only remaining gap is on-device verification.

## Next exact task
On a KVM-capable host or physical device: `. env/android.sh`,
`adb install -r android/app/build/outputs/apk/debug/app-debug.apk`,
launch NotebookOCR, and walk the TEST_MATRIX manual flows. Do NOT start
PaddleOCR integration in this step.

## Commit
Toolchain + docs commit on `main` (see `git log`). SDK/JDK/caches/APK and
machine-local paths excluded. Nothing pushed.
