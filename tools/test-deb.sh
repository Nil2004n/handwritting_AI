#!/bin/sh
# Tests of the native Debian package (release-output/notebook-ai_<ver>_amd64.deb) that need NO root and change nothing on the host.
#
# Three evidence levels, reported separately:
#   A. STATIC inspection of the package (dpkg-deb): metadata, contents, no external-Java dependency, no developer paths.
#   B. EXTRACTED-PAYLOAD execution (dpkg-deb -x into a path with spaces; NOT a package-manager test): the bundled runtime runs
#      with no java on PATH or JAVA_HOME: --self-check, exit codes, model override, real OCR on a generated image, a real GUI launch.
#   C. REAL dpkg LIFECYCLE in a private, user-owned root (dpkg --root --force-not-root; maintainer scripts run chrootless with a stub
#      xdg-desktop-menu and a fake HOME): install, shadowing notice, upgrade without stale files, remove, purge, user data preserved.
#      This is real dpkg but NOT apt, NOT root and NOT a VM/container: see tools/test-deb-lifecycle.sh for the disposable-environment test.
#
# Usage: sh tools/test-deb.sh [path/to/notebook-ai_<ver>_amd64.deb]      (make test-deb)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
DEB=${1:-$(ls "$ROOT"/release-output/notebook-ai_*_amd64.deb 2>/dev/null | head -1)}
[ -f "$DEB" ] || { echo "no .deb found; run 'make package-deb' first (or pass its path)"; exit 2; }
command -v dpkg-deb >/dev/null 2>&1 || { echo "dpkg-deb not available (Debian/Ubuntu host needed)"; exit 2; }
T=$(mktemp -d "${TMPDIR:-/tmp}/nbai-debtest-XXXXXX") || exit 2
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
VER=$(dpkg-deb -f "$DEB" Version)

echo "== A. static inspection (dpkg-deb)"
( cd "$(dirname "$DEB")" && sha256sum -c --quiet "$(basename "$DEB").sha256" >/dev/null 2>&1 ); t "package matches its .sha256 sidecar" $?
[ "$(dpkg-deb -f "$DEB" Package)" = notebook-ai ]; t "Package: notebook-ai" $?
[ "$(dpkg-deb -f "$DEB" Architecture)" = amd64 ]; t "Architecture: amd64" $?
dpkg-deb -f "$DEB" Version | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; t "Version is a valid Debian version ($VER)" $?
M=$(dpkg-deb -f "$DEB" Maintainer); [ -n "$M" ]; t "Maintainer present ($M)" $?
case "$M" in *unconfigured@invalid*) echo "      NOTE: maintainer/vendor are the clearly marked placeholders: publisher fields are NOT configured";; esac
DEPS=$(dpkg-deb -f "$DEB" Depends); tn "Depends names no external Java" "$DEPS" "jre"; tn "Depends names no jdk/java package" "$DEPS" "openjdk"; tt "Depends covers the JavaFX GTK stack" "$DEPS" "libgtk-3-0"; tt "Depends covers libc6" "$DEPS" "libc6"
dpkg-deb -c "$DEB" > "$T/list.txt"
for p in ./opt/notebook-ai/bin/notebook-ai ./opt/notebook-ai/lib/runtime/bin/java ./opt/notebook-ai/lib/models/det.onnx ./opt/notebook-ai/lib/models/rec.onnx ./opt/notebook-ai/lib/legal/THIRD-PARTY-NOTICES.txt ./opt/notebook-ai/lib/legal/native-package.json ./usr/share/doc/notebook-ai/copyright; do grep -q " $p\( ->.*\)\?$" "$T/list.txt" || bad "missing from package: $p"; done; ok "required files present (see FAIL lines above if any)"
grep -q ' ./usr/bin/notebook-ai -> /opt/notebook-ai/bin/notebook-ai$' "$T/list.txt"; t "/usr/bin/notebook-ai is owned by the package (symlink to the launcher)" $?
grep -q '\.desktop$' "$T/list.txt"; t "desktop menu entry shipped" $?
[ "$(grep -c 'lib/fx/javafx-.*-linux\.jar$' "$T/list.txt")" = 3 ]; t "exactly the 3 Linux JavaFX jars, on the module path directory (not lib/app)" $?
grep -qE '(-win\.jar|\.dll|\.dylib|\.exe|\.nbai|\.zip|\.deb|/\.git/|/src/|linux-dist|win-dist|release-output|eval-local)' "$T/list.txt"; [ $? -ne 0 ]; t "no other-OS libraries, projects, archives, nested packages, sources or private data" $?
dpkg-deb -x "$DEB" "$T/static" && dpkg-deb -e "$DEB" "$T/static/DEBIAN"
for s in preinst postinst prerm postrm; do grep -nE 'curl|wget|gradle|apt-get|\.bashrc|\.profile|\.zshrc|java -|sudo ' "$T/static/DEBIAN/$s" >/dev/null 2>&1 && bad "maintainer script $s does something it must not"; done; ok "maintainer scripts do no downloads, builds, java runs, sudo or shell-startup edits"
grep -q 'xdg-desktop-menu install' "$T/static/DEBIAN/postinst" && ! grep -q 'launch\|notebook-ai --' "$T/static/DEBIAN/postinst"; t "postinst only registers the menu entry (and prints the shadowing notice); it does not launch the GUI" $?
if command -v lintian >/dev/null 2>&1; then OUT=$(lintian --no-tag-display-limit "$DEB" 2>&1); echo "$OUT" | grep -E '^E:' | head -5; echo "$OUT" | grep -q '^E:'; [ $? -ne 0 ]; t "lintian reports no errors (warnings shown by running it manually)" $?; else skip "lintian is not installed on this machine (package linting NOT performed)"; fi
if grep -rIl -e "$ROOT" -e "$HOME" "$T/static/DEBIAN" "$T/static/opt/notebook-ai/lib/app" "$T/static/opt/notebook-ai/lib/legal" "$T/static/usr" 2>/dev/null | grep -q .; then bad "developer-machine paths embedded"; else ok "no developer-machine paths in config, scripts, notices or metadata"; fi
python3 - "$T/static/opt/notebook-ai/lib/legal/native-package.json" "$VER" <<'PY'
import json,sys
m=json.load(open(sys.argv[1])); e=[]
if m['application']['version']!=sys.argv[2]: e.append('version')
if m['target']!={'os':'linux','arch':'x64'}: e.append('target')
if m['runtime']['bundled'] is not True or not m['runtime']['modules']: e.append('runtime')
if len(m['source']['commit'])<7 or not isinstance(m['source']['dirty'],bool): e.append('source identity')
if m['signing']['signed'] is not False: e.append('signing claimed')
if m['licensing']['unresolvedBlocking'] and not m['internalBuild']: e.append('unresolved licensing but not flagged internal')
print('runtime:',m['runtime']['vendor'],m['runtime']['runtimeVersion'],'| EA:',m['runtime']['earlyAccess'],'| internalBuild:',m['internalBuild'])
print('\n'.join(e) if e else 'OK'); sys.exit(1 if e else 0)
PY
t "native-package.json: version, target, bundled runtime, source identity, honest signing and licensing state" $?

echo "== B. extracted-payload execution (NOT a package-manager test): bundled runtime, no Java on PATH"
X="$T/ex tract ed"; mkdir -p "$X" && dpkg-deb -x "$DEB" "$X"
APP=$X/opt/notebook-ai; L=$APP/bin/notebook-ai
snap() { ( cd "$1" && find . -type f -exec sha256sum {} + | sort -k2 ); }
before=$(snap "$APP")
OUT=$(cd / && env -i HOME="$T/h0" PATH=/nonexistent "$L" --self-check 2>&1); RC=$?
t "--self-check exits 0 with NO java on PATH and no JAVA_HOME (from '/')" $RC "$OUT"
tt "the running Java is the BUNDLED runtime" "$OUT" "Java home: $APP/lib/runtime"
tt "JavaFX modules resolved from the module path" "$OUT" "JavaFX module javafx.graphics: present"
tt "ONNX Runtime natives loaded and OCR models verified" "$OUT" "Native resources closed. Setup valid."
tt "models resolved from the installed location, not the working directory" "$OUT" "lib/app/../models"
OUT=$(cd / && env -i HOME="$T/h0" PATH=/nonexistent JAVA_HOME=/nonexistent/jdk "$L" --self-check 2>&1); RC=$?; t "a bogus JAVA_HOME is ignored (the launcher uses its bundled runtime)" $RC
mkdir -p "$T/badmodels"; : > "$T/badmodels/det.onnx"
OUT=$(cd / && env -i HOME="$T/h0" PATH=/nonexistent NOTEBOOK_AI_MODELS="$T/badmodels" "$L" --self-check 2>&1); RC=$?
[ "$RC" = 1 ] && has "$OUT" "SELF-CHECK FAILED"; t "an explicit NOTEBOOK_AI_MODELS override wins and the application's exit status (1) is returned" $? "rc=$RC"
[ "$before" = "$(snap "$APP")" ]; t "running wrote nothing into the installed application directory" $?
# real OCR through the bundled runtime on a generated, non-private image (the launcher starts the GUI, so the CLI class is run with the bundled java)
if python3 -c 'import PIL' 2>/dev/null; then
    python3 - "$T/ocr.png" <<'PY'
import sys
from PIL import Image, ImageDraw, ImageFont
f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',54) if __import__('os').path.exists('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf') else ImageFont.load_default()
im=Image.new('RGB',(1500,500),'white'); d=ImageDraw.Draw(im)
d.text((60,50),'Hello world 123',font=f,fill='black'); d.text((60,220),'Pack my box 456',font=f,fill='black')
im.save(sys.argv[1])
PY
    IMG=$T/ocr.png; WANT1="Hello world 123"; WANT2="Pack my box 456"
else IMG=$ROOT/samples/handwriting-test.jpg; WANT1="This is how it's done"; WANT2="Regions: 3"; echo "      (PIL unavailable: using the committed sample image)"; fi
OUT=$(cd / && env -i HOME="$T/h0" PATH=/nonexistent "$APP/lib/runtime/bin/java" -Dnotebookai.models="$APP/lib/models" -cp "$APP/lib/app/*" notebookai.ocr.RecognizeCli "$IMG" 2>&1); RC=$?
t "RecognizeCli runs on the bundled runtime + shipped jars/models/natives" $RC "$OUT"
tt "OCR recognized the first line" "$OUT" "$WANT1"; tt "OCR recognized the second line / region count" "$OUT" "$WANT2"
# GUI smoke test
if command -v Xvfb >/dev/null 2>&1 && command -v xdotool >/dev/null 2>&1 && command -v xfwm4 >/dev/null 2>&1; then
    D=:$((60 + $$ % 20)); Xvfb "$D" -screen 0 1280x800x24 >/dev/null 2>&1 & XPID=$!; sleep 2; xfwm4 --display="$D" >/dev/null 2>&1 & WPID=$!; sleep 2
    ( cd / && env -i HOME="$T/h0" PATH=/nonexistent DISPLAY=$D LANG=C.UTF-8 "$L" >"$T/gui.log" 2>&1 & )
    title=; for i in $(seq 1 40); do sleep 1; wid=$(DISPLAY=$D xdotool search --name 'notebook-ai' 2>/dev/null | head -1); [ -n "$wid" ] && title=$(DISPLAY=$D xdotool getwindowname "$wid" 2>/dev/null); [ -n "$title" ] && break; done
    has "$title" "notebook-ai"; t "the installed launcher opened the application window ('$title' after ${i}s)" $? "$(tail -3 "$T/gui.log")"
    PID=; for p in $(pgrep -f "$L" 2>/dev/null); do tr '\0' '\n' < /proc/$p/environ 2>/dev/null | grep -q "^DISPLAY=$D\$" && PID=$p; done
    [ -n "$PID" ] && grep -q "$APP/lib/runtime/lib/server/libjvm.so" /proc/$PID/maps; t "the GUI process maps the BUNDLED libjvm.so (not a system JVM)" $?
    [ -n "$PID" ] && kill "$PID"; sleep 1; kill "$WPID" 2>/dev/null; WPID=; kill "$XPID" 2>/dev/null; XPID=
    tn "no Java exception in the GUI output" "$(cat "$T/gui.log")" "Exception"
else skip "GUI smoke test (Xvfb/xdotool/xfwm4 not available)"; fi

echo "== C. real dpkg lifecycle in a private user-owned root (NOT apt, NOT root, NOT a VM)"
R=$T/root; mkdir -p "$R/var/lib/dpkg/info" "$R/var/lib/dpkg/updates" "$R/var/lib/dpkg/triggers" "$R/etc/dpkg" "$R/var/log" "$T/stubbin" "$T/fakehome/Documents" "$T/fakehome/.local/bin"
: > "$R/var/lib/dpkg/status"; : > "$R/var/lib/dpkg/available"
printf '#!/bin/sh\necho "xdg-desktop-menu $*" >> "%s/xdg.log"\nexit 0\n' "$T" > "$T/stubbin/xdg-desktop-menu"; chmod 755 "$T/stubbin/xdg-desktop-menu"
echo "my project" > "$T/fakehome/Documents/work.nbai"; printf '#!/bin/sh\necho user-own\n' > "$T/fakehome/.local/bin/notebook-ai"; chmod 755 "$T/fakehome/.local/bin/notebook-ai"
DP() { env -i HOME="$T/fakehome" PATH="$T/stubbin:/usr/sbin:/usr/bin:/sbin:/bin" dpkg --root="$R" --force-not-root --force-script-chrootless --force-depends --no-triggers "$@" 2>&1; }
sleep 1; touch "$T/fixtures.done"; sleep 1
mkdir -p "$R/usr/local/bin"; printf '#!/bin/sh\n' > "$R/usr/local/bin/notebook-ai"; chmod 755 "$R/usr/local/bin/notebook-ai"
OUT=$(DP -i "$DEB"); RC=$?; t "dpkg installs the package into the private root" $RC "$OUT"
tt "the shadowing notice names the competing command" "$OUT" "/usr/local/bin/notebook-ai exists and may shadow /usr/bin/notebook-ai"
[ -x "$R/opt/notebook-ai/bin/notebook-ai" ] && [ -L "$R/usr/bin/notebook-ai" ] && [ -d "$R/opt/notebook-ai/lib/runtime" ]; t "files installed under /opt/notebook-ai plus the /usr/bin command" $?
[ "$(env -i PATH=/usr/bin:/bin dpkg --root="$R" -s notebook-ai 2>/dev/null | sed -n 's/^Status: //p')" = "install ok installed" ]; t "dpkg database records the package as installed" $?
OUT=$(cd / && env -i HOME="$T/fakehome" PATH=/nonexistent "$R/opt/notebook-ai/bin/notebook-ai" --self-check 2>&1); RC=$?; t "the installed application passes --self-check with no Java on PATH" $RC "$OUT"
grep -q 'xdg-desktop-menu install /opt/notebook-ai/lib/' "$T/xdg.log"; t "postinst called only xdg-desktop-menu install (stub log)" $?
# upgrade: a synthetic next version that drops a file and adds one
mkdir -p "$T/up"; dpkg-deb -R "$DEB" "$T/up/pkg" && echo "stale" > "$T/up/pkg/opt/notebook-ai/lib/stale-from-0.1.0.txt" && rm -f "$T/up/pkg/DEBIAN/md5sums" && dpkg-deb --root-owner-group -b "$T/up/pkg" "$T/up/old.deb" >/dev/null 2>&1
dpkg-deb -R "$DEB" "$T/up/new" && sed -i 's/^Version: .*/Version: 0.1.1/' "$T/up/new/DEBIAN/control" && echo "fresh" > "$T/up/new/opt/notebook-ai/lib/added-in-0.1.1.txt" && rm -f "$T/up/new/DEBIAN/md5sums" && dpkg-deb --root-owner-group -b "$T/up/new" "$T/up/new.deb" >/dev/null 2>&1
OUT=$(DP -i "$T/up/old.deb"); OUT=$(DP -i "$T/up/new.deb"); RC=$?; t "dpkg upgrades 0.1.0 -> 0.1.1" $RC "$OUT"
[ ! -e "$R/opt/notebook-ai/lib/stale-from-0.1.0.txt" ] && [ -f "$R/opt/notebook-ai/lib/added-in-0.1.1.txt" ]; t "the upgrade removed the old version's file and installed the new one (no stale files)" $?
[ "$(env -i PATH=/usr/bin:/bin dpkg --root="$R" -s notebook-ai 2>/dev/null | sed -n 's/^Version: //p')" = 0.1.1 ]; t "installed version is now 0.1.1" $?
OUT=$(DP -r notebook-ai); RC=$?; t "dpkg removes the package" $RC "$OUT"
[ ! -e "$R/opt/notebook-ai" ] && [ ! -e "$R/usr/bin/notebook-ai" ]; t "application files and the /usr/bin command are gone after removal" $?
grep -q 'xdg-desktop-menu uninstall' "$T/xdg.log"; t "prerm unregistered the menu entry" $?
OUT=$(DP -P notebook-ai); RC=$?; t "dpkg purges the package" $RC "$OUT"
[ -f "$T/fakehome/Documents/work.nbai" ] && [ "$(cat "$T/fakehome/Documents/work.nbai")" = "my project" ]; t "user projects (.nbai) preserved through install, upgrade, remove and purge" $?
[ -x "$T/fakehome/.local/bin/notebook-ai" ] && [ "$(sh "$T/fakehome/.local/bin/notebook-ai")" = user-own ] && [ -x "$R/usr/local/bin/notebook-ai" ]; t "a pre-existing ~/.local command and /usr/local command were left untouched" $?
[ -z "$(find "$T/fakehome" -newer "$T/fixtures.done" 2>/dev/null | head -1)" ]; t "nothing was written into the (fake) user's home" $?

echo
echo "RESULT: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" = 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
[ "$FAIL" = 0 ]
