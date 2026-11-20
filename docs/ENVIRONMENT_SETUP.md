# Environment setup (provisioned 2026-11-20)

## Host (detected, not assumed)
- OS: Kali GNU/Linux Rolling 2026.2 (kernel 6.19.14+kali-amd64), x86_64, 5 CPUs
- RAM: ~9 GB; disk: 20 GB free at provision time
- Virt: guest VM (`hypervisor` CPU flag, `systemd-detect-virt` = oracle);
  NO vmx/svm flags, NO /dev/kvm → no hardware-accelerated emulator possible
- Shell: zsh; user `kali` (uid 1000); no root used anywhere below

## Project requirements (from the repo, not a tutorial)
- React Native 0.88.1, AGP 9.2.1, Gradle 9.4.1 (checked-in wrapper),
  Kotlin 2.2.0, compileSdk 37, targetSdk 36, minSdk 24,
  buildTools 37.0.0, NDK 27.1.12297006 (declared; not needed for this build)
- Node >= 22.11 (env: 24.18.0, npm 12.0.1); JDK 17+ required by AGP 9.x

## What was installed vs reused
- REUSED: full JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (javac
  included). System default untouched (still Java 25). Gradle daemon JVM
  pinned via `~/.gradle/gradle.properties`:
  `org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64`
- INSTALLED (user-scoped, no sudo): Android SDK at `~/Android/Sdk`
  - `cmdline-tools;latest` 22.0 from the official
    `commandlinetools-linux-15859902_latest.zip`
    (SHA-256 `4e4c464f…74eedf583`, verified against developer.android.com)
  - `platform-tools` (adb etc.), `platforms;android-37.2` (final; satisfies
    compileSdk 37), `build-tools;37.0.0`
  - Correct nesting: `cmdline-tools/latest/{bin,lib,…}` (not double-nested)
  - SDK licenses accepted via `sdkmanager --licenses` (owner-approved)
- NOT installed: NDK/CMake (build did not require them), full Android
  Studio (unnecessary headless)
- Emulator packages (installed 2026-11-20 for a boot attempt, unusable here):
  `emulator` 37.3.10.0, `system-images;android-36;default;x86_64` rev 2;
  AVD `notebookocr-test` defined at `~/.android/avd/` (Pixel, x86_64,
  swiftshader_indirect, 1536 MB). `avdmanager create avd` fails on current
  tooling (missing `devices.xml` in image dir) — AVD was written manually
  and lists fine under `emulator -list-avds`. Reusable on a KVM host.

## Activation (per shell)
```sh
. /home/kali/Desktop/handwritting_AI/env/android.sh
# sets ANDROID_HOME/ANDROID_SDK_ROOT=$HOME/Android/Sdk,
# adds cmdline-tools + platform-tools to PATH, JAVA_HOME=jdk-21 for builds
```
`android/local.properties` contains `sdk.dir=/home/kali/Android/Sdk` and is
git-ignored (repo `.gitignore` line 30). Do not commit machine-local paths.

## Reproduce the build
```sh
. env/android.sh
npm ci            # or npm install; restores node_modules from the lockfile
npm run typecheck && npm run lint && npx jest --runInBand
cd android && ./gradlew assembleDebug
```
APK: `android/app/build/outputs/apk/debug/app-debug.apk` (git-ignored).
