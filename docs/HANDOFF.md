# Handoff (emulator attempt 2026-11-20 — host-blocked, no on-device run)

## Completed this session
- Re-read all Phase 0/toolchain docs; verified git clean at 19a4ff8,
  JDK 21 daemon, SDK present, APK current (rebuild: BUILD SUCCESSFUL in 4s,
  up-to-date, same 125,320,135-byte artifact).
- Installed `emulator` 37.3.10.0 + `system-images;android-36;default;x86_64`
  (stable, host-arch, ABI-compatible with the APK's x86_64 slice).
- Created AVD `notebookocr-test` manually (avdmanager hits a `devices.xml`
  tooling quirk); `emulator -list-avds` confirms it.
- Attempted ONE bounded headless boot with logging. Refused twice, exit 1:
  `-accel off` → software CPU emulation unsupported; with
  `ANDROID_I_WANT_MY_TCG=yes` → "x86_64 emulation currently requires
  hardware acceleration!" No adb devices ever appeared. No ARM emulation
  attempted (explicitly out of scope). No repeated AVD creation.
- No PaddleOCR work started. No native code touched. No AVD wiped
  (it is newly created, unused).

## Files changed
- Updated: `docs/KNOWN_ISSUES.md` (issue #4 with hard evidence + log paths),
  `docs/TEST_MATRIX.md` (attempt record, 11 checks marked UNVERIFIED,
  debug-vs-standalone note), `docs/ENVIRONMENT_SETUP.md` (emulator pkgs/AVD),
  this HANDOFF.
- Outside repo (never committed): `~/Android/Sdk` additions,
  `~/.android/avd/notebookocr-test.*`, `/tmp/opencode/emulator*.log`.

## Commands actually run
- `./gradlew assembleDebug` (up-to-date, BUILD SUCCESSFUL)
- `sdkmanager "emulator" "system-images;android-36;default;x86_64"`
- `emulator -accel-check` → KVM requires vmx/svm
- `emulator -list-avds` → notebookocr-test
- `emulator -avd notebookocr-test -no-window -no-audio -no-boot-anim
  -gpu swiftshader_indirect -no-snapshot` → exit 1 (log kept)
- `adb devices` → empty (daemon started fine)

## Verification results
- Emulator/system-image version + ABI: 37.3.10.0 / android-36 default x86_64
- Device serial: none (no device ever attached)
- Install/launch: NOT PERFORMED (no target) — no crash logs exist
- UI checks 1–11: ALL UNVERIFIED on-device; jest App flow test is the only
  executed UI verification
- Screenshots/logs: none from device; emulator logs at
  `/tmp/opencode/emulator.log` and `/tmp/opencode/emulator-tcg.log`

## Known failures + first actionable causes
- Boot refusal: host lacks nested virtualization (no vmx/svm, no /dev/kvm);
  current emulator mandates hardware accel for x86_64 guests. Fix requires
  a KVM-capable host or physical device — not an in-guest package install.

## Next exact task
On a KVM-capable host (reuse this AVD definition there): boot
`notebookocr-test`, `adb install -r
android/app/build/outputs/apk/debug/app-debug.apk`, discover the launchable
activity via `aapt dump badging` (`com.notebookocr`), launch, and walk
TEST_MATRIX checks 1–11 with device network disabled for check 8. Then do
a release/bundled-JS build to prove standalone offline behavior. Do NOT
start PaddleOCR integration in that step.

## Commit
Docs-only commit on `main` (see `git log`). Nothing pushed.
