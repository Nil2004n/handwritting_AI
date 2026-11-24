#!/bin/sh
# End-to-end test of the Linux RELEASE ARCHIVE (release-output/notebook-ai-<ver>-linux-x64.zip), as a recipient would use it:
# extract into a fresh path containing spaces, run from another directory, install into a temporary user-owned HOME,
# verify the installed payload, delete the extracted package, check the installed command still works, upgrade, uninstall.
#
# It needs no Gradle, Make or Git to RUN the package; this script itself is a developer tool and also uses python3 (zip/JSON
# inspection) and, for the optional GUI smoke test, Xvfb + xdotool. Everything happens under one temporary directory with a
# private HOME and PATH; the real HOME, ~/.local, /usr/local and PATH are never modified. No sudo, no network.
#
# Usage: sh tools/test-dist.sh [path/to/notebook-ai-<ver>-linux-x64.zip]      (make test-dist)
# Build the archive first:  make dist-linux package-linux   (or ./gradlew packageLinux)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
ZIP=${1:-$(ls "$ROOT"/release-output/notebook-ai-*-linux-x64.zip 2>/dev/null | head -1)}
[ -f "$ZIP" ] || { echo "archive not found; run 'make dist-linux package-linux' first (or pass its path)"; exit 2; }
TOP=$(basename "$ZIP" .zip)
T=$(mktemp -d "${TMPDIR:-/tmp}/nbai-dtest-XXXXXX") || exit 2
cleanup() { [ -n "${WPID:-}" ] && kill "$WPID" 2>/dev/null; [ -n "${XPID:-}" ] && kill "$XPID" 2>/dev/null; chmod -R u+rwX "$T" 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT

PASS=0 FAIL=0 SKIP=0
ok()   { PASS=$((PASS+1)); printf 'PASS  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf 'FAIL  %s\n' "$1"; [ -n "${2:-}" ] && printf '      %s\n' "$2"; }
skip() { SKIP=$((SKIP+1)); printf 'SKIP  %s\n' "$1"; }
t()  { if [ "$2" = 0 ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }
has() { case "$1" in *"$2"*) return 0 ;; esac; return 1; }
tt() { if has "$2" "$3"; then ok "$1"; else bad "$1" "expected to contain: $3"; fi; }
tn() { if has "$2" "$3"; then bad "$1" "must NOT contain: $3"; else ok "$1"; fi; }

MINBIN=$T/minbin; mkdir -p "$MINBIN"
for tool in sh sed grep sort find sha256sum cmp cat date uname id dirname basename chmod wc cut mkdir cp mv rm rmdir ls tr head mktemp readlink touch ln env printf true java xargs; do
    p=$(command -v "$tool" 2>/dev/null) && [ -x "$p" ] && ln -sf "$p" "$MINBIN/$tool"
done
mkjava() { mkdir -p "$1/bin"; printf '#!/bin/sh\necho "openjdk version \\"%s.0.1\\" 2026-01-01" >&2\n' "$2" > "$1/bin/java"; chmod 755 "$1/bin/java"; }
mkjava "$T/j17" 17
RP=$MINBIN                                   # PATH with the real java, minimal tools
newhome() { N=$((${N:-0}+1)); H=$T/home$N; mkdir -p "$H"; }
extract() { rm -rf "$1"; mkdir -p "$1"; ( cd "$1" && python3 -c "
import zipfile,sys,os
z=zipfile.ZipFile(sys.argv[1])
for i in z.infolist():
    z.extract(i)
    m=(i.external_attr>>16)&0o777
    if m: os.chmod(i.filename,m)
" "$ZIP" ) || return 1; }
snap() { ( cd "$1" && find . -type f -exec sha256sum {} + | sort -k2 ); }

echo "== 1. archive"
( cd "$(dirname "$ZIP")" && sha256sum -c --quiet "$(basename "$ZIP").sha256" >/dev/null 2>&1 ); t "archive matches its .sha256 sidecar" $?
python3 - "$ZIP" "$TOP" <<'PY'
import sys,zipfile
z=zipfile.ZipFile(sys.argv[1]); top=sys.argv[2]
n=[i.filename for i in z.infolist()]
errs=[]
if {x.split('/')[0] for x in n}!={top}: errs.append("not a single top-level directory named "+top)
if any(x.startswith('/') or '..' in x.split('/') for x in n): errs.append("unsafe entry names")
exe={i.filename for i in z.infolist() if (i.external_attr>>16)&0o111 and not i.filename.endswith('/')}
want={top+'/'+x for x in ('install.sh','uninstall.sh','run-notebook-ai.sh','app/bin/notebook-ai')}
if exe!=want: errs.append("executable bits differ: %s"%sorted(exe^want))
if {i.date_time[:3] for i in z.infolist()}!={(1980,2,1)}: errs.append("timestamps not normalised")
bad=[x for x in n if any(p in x.split('/') for p in ('.git','src','tests','test','__pycache__','build','eval-local','cand','build-old-rootowned'))]
if bad: errs.append("forbidden entries: %s"%bad[:5])
print('\n'.join(errs) if errs else 'OK'); sys.exit(1 if errs else 0)
PY
t "single top-level directory, safe names, exec bits kept, normalised timestamps, nothing forbidden" $?
mkdir -p "$T/repack"; cp "$ZIP" "$T/repack/original.zip"

echo "== 2. extract into a fresh path containing spaces; static payload checks"
X="$T/ex tract ed/$TOP"; extract "$T/ex tract ed" ; [ -d "$X" ]; t "extracted" $?
for f in install.sh uninstall.sh run-notebook-ai.sh README.txt LICENSE THIRD-PARTY-NOTICES.txt distribution.json SHA256SUMS app/bin/notebook-ai app/PLATFORM app/models/det.onnx app/models/rec.onnx scripts/launcher-body.sh licenses/Apache-2.0.txt licenses/openjfx/LICENSE; do [ -e "$X/$f" ] || bad "missing $f"; done; ok "required files present (see FAIL lines above if any)"
( cd "$X" && sha256sum -c --quiet SHA256SUMS >/dev/null 2>&1 ); t "SHA256SUMS validates every shipped file" $?
n_listed=$(wc -l < "$X/SHA256SUMS"); n_files=$(cd "$X" && find . -type f ! -name SHA256SUMS | wc -l); [ "$n_listed" = "$n_files" ]; t "SHA256SUMS lists exactly the shipped files ($n_files)" $?
python3 - "$X" <<'PY'
import json,sys,hashlib,os,zipfile
x=sys.argv[1]; m=json.load(open(x+'/distribution.json')); errs=[]
sha=lambda p:hashlib.sha256(open(p,'rb').read()).hexdigest()
if m['targetPlatform']!='linux-x64' or m['target']!={'os':'linux','arch':'x64','platform':'linux-x64'}: errs.append('target')
if m['requiredJava']!=21 or m['javaRuntimeBundled'] is not False: errs.append('java fields')
if m['application']['version']!='0.1.0': errs.append('version')
if not isinstance(m['source']['commit'],str) or len(m['source']['commit'])<7 or not isinstance(m['source']['dirty'],bool): errs.append('source info')
libs=sorted(os.listdir(x+'/app/lib'))
if libs!=sorted(l['file'] for l in m['libraries']): errs.append('libraries list != app/lib')
for l in m['libraries']:
    if sha(x+'/app/lib/'+l['file'])!=l['sha256']: errs.append('hash '+l['file'])
for f in m['models']['files']:
    if sha(x+'/app/models/'+f['name'])!=f['sha256']: errs.append('model '+f['name'])
if any(l.startswith('javafx-') and not l.endswith('-linux.jar') for l in libs): errs.append('non-linux javafx jar')
z=zipfile.ZipFile(x+'/app/lib/onnxruntime-1.22.0.jar')
nat={n.split('/')[3] for n in z.namelist() if n.startswith('ai/onnxruntime/native/') and len(n.split('/'))>4}
if nat!={'linux-x64'}: errs.append('ORT natives %s'%nat)
if not m['licensingGaps']: errs.append('licensingGaps missing')
print('\n'.join(errs) if errs else 'OK'); sys.exit(1 if errs else 0)
PY
t "distribution.json matches the payload (platform, Java, version, library + model hashes, native trimming, gaps declared)" $?
if grep -rIl -e "$ROOT" -e "$HOME" -e '/home/' -e '/tmp/' -e '/build/install' "$X" --exclude-dir=licenses --exclude=install.sh --exclude=THIRD-PARTY-NOTICES.txt 2>/dev/null | grep -q .; then bad "developer-machine paths embedded (listed below)"; grep -rIl -e "$ROOT" -e "$HOME" -e '/home/' -e '/tmp/' -e '/build/install' "$X" --exclude-dir=licenses --exclude=install.sh 2>/dev/null | head -5; else ok "no absolute developer paths or build-directory references (install.sh excepted: relative source-checkout fallback)"; fi
[ ! -e "$X/.git" ] && [ -z "$(find "$X" -name '*.class' -o -name '*.nbai' -o -name '__pycache__' -o -name '.gradle' | head -1)" ]; t "no .git, class files, projects, caches" $?
grep -q 'Java 21' "$X/README.txt" && grep -q 'KNOWN GAPS' "$X/THIRD-PARTY-NOTICES.txt" && grep -q 'not bundled\|NOT included\|not included\|No Java runtime is bundled' "$X/README.txt"; t "README states the Java prerequisite; notices state the known gaps" $?
tn "README does not claim the package is fully standalone" "$(cat "$X/README.txt")" "fully standalone"

echo "== 3. direct run (no install) from another directory"
before=$(snap "$X")
OUT=$(cd / && env -i HOME="$T/h0" PATH="$RP" "$X/run-notebook-ai.sh" --self-check 2>&1); RC=$?
t "run-notebook-ai.sh --self-check exits 0 from '/'" $RC "$OUT"
tt "self-check reports JavaFX modules (module path configured)" "$OUT" "JavaFX module javafx.graphics: present"; tt "self-check verified the OCR setup (checksums, ONNX sessions)" "$OUT" "Native resources closed. Setup valid."
tt "models resolved inside the extracted package, not the cwd" "$OUT" "Models dir: $X/app/models"
[ "$before" = "$(snap "$X")" ]; t "running wrote nothing into the extracted directory (same files, same hashes)" $?
OUT=$(cd "$T" && env -i HOME="$T/h0" PATH="$T/j17/bin:$RP" NOTEBOOK_AI_JDK_FALLBACK=/nonexistent "$X/run-notebook-ai.sh" 2>&1); RC=$?
[ "$RC" = 1 ] && has "$OUT" "Java 17 found"; t "Java 17 refused with an actionable message" $? "$OUT"
mkdir -p "$T/nojava"; for tl in sh sed grep dirname basename uname cat; do ln -sf "$(command -v $tl)" "$T/nojava/$tl"; done
OUT=$(cd "$T" && env -i HOME="$T/h0" PATH="$T/nojava" NOTEBOOK_AI_JDK_FALLBACK=/nonexistent "$X/run-notebook-ai.sh" 2>&1); RC=$?
[ "$RC" = 1 ] && has "$OUT" "Java 21 or newer was not found"; t "missing Java reported with guidance" $? "$OUT"
mkdir -p "$T/badmodels"; echo not-a-model > "$T/badmodels/det.onnx"
OUT=$(cd / && env -i HOME="$T/h0" PATH="$RP" NOTEBOOK_AI_MODELS="$T/badmodels" "$X/run-notebook-ai.sh" --self-check 2>&1); RC=$?
[ "$RC" = 1 ] && has "$OUT" "SELF-CHECK FAILED"; t "application exit status is preserved (bad models -> 1)" $? "rc=$RC"
OUT=$(cd / && env -i HOME="$T/h0" PATH="$RP" NOTEBOOK_AI_MODELS="$T/nonexistent" "$X/run-notebook-ai.sh" --self-check 2>&1); RC=$?
[ "$RC" = 1 ] && has "$OUT" "OCR model files were not found"; t "absent models: actionable error, no download attempted" $? "$OUT"

echo "== 4. install from the extracted package into a temporary user-owned HOME"
newhome; APP=$H/.local/share/notebook-ai; CMD=$H/.local/bin/notebook-ai
OUT=$(cd / && env -i HOME="$H" PATH="$RP" sh "$X/install.sh" 2>&1); RC=$?
t "install.sh works from the extracted package (no source, Gradle, Make or network)" $RC "$OUT"
tt "report names application and command" "$OUT" "application : $APP"
tt "installer verified the package first and reports PATH guidance" "$OUT" "is not on your PATH"
[ -f "$APP/.notebook-ai-install" ] && [ -x "$CMD" ] && [ -f "$APP/legal/LICENSE" ] && [ -f "$APP/legal/THIRD-PARTY-NOTICES.txt" ] && [ -f "$APP/legal/licenses/openjfx/LICENSE" ]; t "installed layout with license documents under legal/" $?
OUT=$(env -i HOME="$H" PATH="$RP" sh "$X/install.sh" --status 2>&1); RC=$?; t "installed payload verifies against its MANIFEST.sha256" $RC "$OUT"
( cd "$X/app" && find bin lib PLATFORM -type f -exec sha256sum {} + | sort -k2 | sed 's|  |  dist/|' ) > "$T/pkg.sum"
( cd "$APP" && find dist -type f -exec sha256sum {} + | sort -k2 ) > "$T/inst.sum"; cmp -s "$T/pkg.sum" "$T/inst.sum"; t "installed application files are byte-identical to the package's app/" $?
( cd "$X/app/models" && sha256sum det.onnx det.yml rec.onnx rec.yml checksums.sha256 | sed 's|  |  models/|' | sort -k2 ) > "$T/pm.sum"; ( cd "$APP" && find models -type f -exec sha256sum {} + | sort -k2 ) > "$T/im.sum"; cmp -s "$T/pm.sum" "$T/im.sum"; t "installed models identical to the bundled models" $?
OUT=$(cd / && env -i HOME="$H" PATH="$RP" "$CMD" --self-check 2>&1); RC=$?; t "installed command --self-check works from '/'" $RC "$OUT"; tt "installed command uses the installed models" "$OUT" "Models dir: $APP/models"
grep -rIl -e "$X" "$APP" "$CMD" >/dev/null 2>&1; [ $? -ne 0 ]; t "nothing installed refers to the extracted package path" $?

echo "== 5. move, then delete, the extracted package: the installed command must keep working"
mv "$X" "$T/moved away"; OUT=$(cd / && env -i HOME="$H" PATH="$RP" "$CMD" --self-check 2>&1); RC=$?; t "works after the extracted folder was moved" $RC "$OUT"
rm -rf "$T/moved away" "$T/ex tract ed"; OUT=$(cd / && env -i HOME="$H" PATH="$RP" "$CMD" --self-check 2>&1); RC=$?; t "works after the extracted folder was deleted" $RC "$OUT"

echo "== 6. real OCR through the INSTALLED files (trimmed ONNX Runtime, bundled models), cwd=/"
if [ -f "$ROOT/samples/handwriting-test.jpg" ]; then
    L=$APP/dist/lib
    OUT=$(cd / && env -i HOME="$H" PATH="$RP" NOTEBOOK_AI_MODELS="$APP/models" java -cp "$L/notebook-ai-0.1.0.jar:$L/onnxruntime-1.22.0.jar:$L/metadata-extractor-2.19.0.jar:$L/xmpcore-6.1.11.jar" notebookai.ocr.RecognizeCli "$ROOT/samples/handwriting-test.jpg" 2>&1); RC=$?
    t "RecognizeCli runs on the installed jars" $RC "$OUT"
    tt "OCR produced the known first line" "$OUT" "This is how it's done"; tt "3 regions detected" "$OUT" "Regions: 3"
else skip "sample image not available"; fi

echo "== 7. reinstall/upgrade and scoped uninstall preserve unrelated files"
extract "$T/ex2" ; X2="$T/ex2/$TOP"
mkdir -p "$H/Documents" "$H/.local/share/other-app"; echo project > "$H/Documents/work.nbai"; echo keep > "$H/.local/share/other-app/k"; printf '#!/bin/sh\n' > "$H/.local/bin/other"; chmod 755 "$H/.local/bin/other"
echo leftover > "$APP/dist/lib/stale-from-old.jar"
OUT=$(cd / && env -i HOME="$H" PATH="$RP" sh "$X2/install.sh" 2>&1); RC=$?; t "reinstall/upgrade from a fresh extraction succeeds" $RC "$OUT"; tt "upgrade reported" "$OUT" "upgraded an existing installation"
[ ! -e "$APP/dist/lib/stale-from-old.jar" ]; t "stale library did not survive the upgrade" $?
[ -f "$H/Documents/work.nbai" ] && [ -f "$H/.local/share/other-app/k" ] && [ -x "$H/.local/bin/other" ]; t "projects, other apps and other commands preserved" $?
[ -z "$(ls -A "$H/.local/share" | grep -e installing -e previous)" ]; t "no installer temp directories left" $?
OUT=$(cd / && env -i HOME="$H" PATH="$RP" "$X2/uninstall.sh" 2>&1); RC=$?; t "uninstall.sh succeeds" $RC "$OUT"
[ ! -e "$APP" ] && [ ! -e "$CMD" ] && [ -f "$H/Documents/work.nbai" ] && [ -f "$H/.local/share/other-app/k" ] && [ -x "$H/.local/bin/other" ] && [ -d "$H/.local/bin" ]; t "only the owned app and command were removed" $?

echo "== 8. damaged or unsuitable packages are refused before anything is installed"
extract "$T/ex3"; X3="$T/ex3/$TOP"; newhome
echo x >> "$X3/app/lib/xmpcore-6.1.11.jar"
OUT=$(cd / && env -i HOME="$H" PATH="$RP" sh "$X3/install.sh" 2>&1); RC=$?; [ "$RC" = 1 ] && has "$OUT" "SHA256SUMS integrity check" && [ ! -e "$H/.local" ]; t "a modified file is detected by SHA256SUMS; nothing installed" $? "$OUT"
extract "$T/ex4"; X4="$T/ex4/$TOP"; rm "$X4/SHA256SUMS"
OUT=$(cd / && env -i HOME="$H" PATH="$RP" sh "$X4/install.sh" 2>&1); RC=$?; [ "$RC" = 1 ] && has "$OUT" "SHA256SUMS is missing"; t "missing SHA256SUMS refused" $? "$OUT"
extract "$T/ex5"; X5="$T/ex5/$TOP"
OUT=$(cd / && env -i HOME="$H" PATH="$RP" sh "$X5/install.sh" --build 2>&1); RC=$?; [ "$RC" = 1 ] && has "$OUT" "not available in a packaged distribution"; t "--build refused in a packaged distribution" $? "$OUT"
OUT=$(cd / && env -i HOME="$H" PATH="$RP" sh "$X5/install.sh" --app-dir "$X5/inside" --bin-dir "$H/b" 2>&1); RC=$?; [ "$RC" != 0 ] && [ ! -e "$X5/inside" ]; t "installing into the extracted package itself is refused" $?
OUT=$(cd / && env -i HOME="$H" PATH="$RP" sh "$X5/install.sh" --bogus 2>&1); RC=$?; [ "$RC" = 2 ]; t "usage error exits 2" $?

echo "== 9. archive re-packaging is stable (same directory -> identical bytes)"
if [ -x "$ROOT/gradlew" ] && [ -d "$ROOT/linux-dist" ]; then
    cp "$ZIP" "$T/repack/before.zip"
    ( cd "$ROOT" && ./gradlew -q -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 packageLinux >/dev/null 2>&1 ); RC=$?
    if [ "$RC" = 0 ]; then cmp -s "$T/repack/before.zip" "$ZIP"; t "re-running packageLinux on the unchanged directory produced a byte-identical archive" $?; else skip "could not re-run packageLinux"; fi
else skip "gradlew / linux-dist not available"; fi

echo "== 10. GUI smoke test (private Xvfb display) - real application execution"
if command -v Xvfb >/dev/null 2>&1 && command -v xdotool >/dev/null 2>&1 && command -v xfwm4 >/dev/null 2>&1; then
    extract "$T/ex6"; X6="$T/ex6/$TOP"; D=:$((100 + $$ % 50))
    Xvfb "$D" -screen 0 1280x800x24 >/dev/null 2>&1 & XPID=$!
    sleep 2
    xfwm4 --display="$D" >/dev/null 2>&1 & WPID=$!
    sleep 2
    ( cd / && DISPLAY=$D env -i HOME="$T/h0" PATH="$RP" DISPLAY=$D LANG=C.UTF-8 "$X6/run-notebook-ai.sh" >"$T/gui.log" 2>&1 & echo $! > "$T/gui.pid" )
    title=
    for i in $(seq 1 40); do sleep 1; wid=$(DISPLAY=$D xdotool search --name 'notebook-ai' 2>/dev/null | head -1); [ -n "$wid" ] && title=$(DISPLAY=$D xdotool getwindowname "$wid" 2>/dev/null); [ -n "$title" ] && break; done
    has "$title" "notebook-ai"; t "direct-run launcher opened the application window ('$title' after ${i}s)" $? "$(tail -3 "$T/gui.log")"
    for p in $(pgrep -f 'notebookai.ui.Main' 2>/dev/null); do tr '\0' '\n' < /proc/$p/environ 2>/dev/null | grep -q "^DISPLAY=$D\$" && kill "$p" 2>/dev/null; done
    sleep 1; kill "$WPID" 2>/dev/null; WPID=; kill "$XPID" 2>/dev/null; XPID=
    tn "no exception in the application output" "$(cat "$T/gui.log")" "Exception"
else skip "Xvfb/xdotool/xfwm4 not available"; fi

echo
echo "RESULT: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" = 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
[ "$FAIL" = 0 ]
