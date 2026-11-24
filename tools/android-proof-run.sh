#!/bin/sh
# Runtime acceptance of the Android engine proof on a CONNECTED device or an ALREADY RUNNING emulator. It never starts an emulator.
# Needs: adb on PATH (or ANDROID_HOME), and the APK from
#   ANDROID_HOME=$HOME/Android/Sdk ./gradlew -p android-proof :app:assembleDebug
# Usage: sh tools/android-proof-run.sh [serial]     (timeout via NBAI_PROOF_TIMEOUT seconds, default 300; bounded, no unbounded polling)
# Exit status: 0 = every check passed, 1 = a check failed, 2 = setup problem (no device, no APK, timeout).
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
APK="$ROOT/android-proof/app/build/outputs/apk/debug/app-debug.apk"
ADB=$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")
SER=${1:+-s $1}
[ -x "$ADB" ] || { echo "adb not found"; exit 2; }
[ -f "$APK" ] || { echo "APK not built: $APK"; exit 2; }
n=$($ADB devices | awk 'NR>1 && $2=="device"' | wc -l)
[ "$n" -ge 1 ] || { echo "no connected device/emulator in state 'device' (this script does not start one)"; exit 2; }
$ADB $SER install -r "$APK" >/dev/null || { echo "install failed"; exit 2; }
$ADB $SER shell run-as ai.notebookai.proof rm -f files/proof-result.json >/dev/null 2>&1
$ADB $SER shell am start -n ai.notebookai.proof/.ProofActivity >/dev/null || { echo "could not start the proof activity"; exit 2; }
TIMEOUT=${NBAI_PROOF_TIMEOUT:-300}; waited=0; OUT=
while [ "$waited" -lt "$TIMEOUT" ]; do
    OUT=$($ADB $SER shell run-as ai.notebookai.proof cat files/proof-result.json 2>/dev/null)
    case "$OUT" in '{'*) break ;; esac
    OUT=; sleep 5; waited=$((waited + 5))
done
[ -n "$OUT" ] || { echo "no result after ${TIMEOUT}s (see: adb logcat -s NBAI_PROOF)"; exit 2; }
echo "$OUT"
case "$OUT" in *'"allPassed":true'*) echo "ANDROID PROOF: all checks passed"; exit 0 ;; *) echo "ANDROID PROOF: FAILED"; exit 1 ;; esac
