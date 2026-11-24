#!/bin/sh
# Linux installer / Makefile test suite for install.sh.
#
# Isolation: everything happens under one temporary directory. A private HOME, a private PATH (a minimal tool
# directory plus fake `java`, `uname`, `id`, `cp`, `mv` where a scenario needs them) and fixture distributions are
# used; the real HOME, ~/.local, /usr/local and PATH are never read or modified. No sudo, no network, no Gradle.
# Most scenarios use a tiny fixture distribution whose stub start script echoes its arguments and exits with a
# chosen status; one scenario installs the real staged build (make build) when it exists.
#
# Usage: sh tools/verify-install.sh        (also: make test-install)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
T=$(mktemp -d "${TMPDIR:-/tmp}/nbai-itest-XXXXXX") || exit 2
trap 'chmod -R u+rwX "$T" 2>/dev/null; rm -rf "$T"' EXIT

PASS=0 FAIL=0 SKIP=0
ok()   { PASS=$((PASS+1)); printf 'PASS  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf 'FAIL  %s\n' "$1"; [ -n "${2:-}" ] && printf '      %s\n' "$2"; }
skip() { SKIP=$((SKIP+1)); printf 'SKIP  %s\n' "$1"; }
t() { if [ "$2" = 0 ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }       # t "name" <exit-status> [detail]
has() { case "$1" in *"$2"*) return 0 ;; esac; return 1; }               # has "$text" "needle"
tt() { if has "$2" "$3"; then ok "$1"; else bad "$1" "expected to contain: $3"; fi; }
tn() { if has "$2" "$3"; then bad "$1" "must NOT contain: $3"; else ok "$1"; fi; }

# ---------------------------------------------------------------------------------------------- fixtures
MINBIN=$T/minbin; mkdir -p "$MINBIN"
for tool in sh sed grep sort find sha256sum cmp cat date uname id dirname basename chmod wc cut mkdir cp mv rm rmdir ls tr head mktemp readlink touch ln env printf true; do
    p=$(command -v "$tool" 2>/dev/null) && [ -x "$p" ] && ln -sf "$p" "$MINBIN/$tool"
done
mkjava() { # mkjava DIR MAJOR : a fake java that reports the given version on stderr like the real one
    mkdir -p "$1/bin"
    printf '#!/bin/sh\necho "openjdk version \\"%s.0.1\\" 2026-01-01" >&2\necho "OpenJDK Runtime Environment (build %s.0.1+1)" >&2\n' "$2" "$2" > "$1/bin/java"
    chmod 755 "$1/bin/java"
}
mkjava "$T/j21" 21; mkjava "$T/j17" 17
FJ21=$T/j21/bin; FJ17=$T/j17/bin
BASEPATH=$FJ21:$MINBIN
IPATH=$BASEPATH FB=/nonexistent JH= XPATH=

mkfix() { # mkfix DIR [TAG] [PLATFORM] : a complete fixture distribution + models
    d=$1; tag=${2:-v1}; plat=${3:-linux-x64}
    rm -rf "$d"; mkdir -p "$d/dist/bin" "$d/dist/lib" "$d/models"
    printf 'platform=%s\napp=notebook-ai\nversion=0.0.0-%s\n' "$plat" "$tag" > "$d/dist/PLATFORM"
    cat > "$d/dist/bin/notebook-ai" <<'EOS'
#!/bin/sh
echo "STUB java_home=${JAVA_HOME:-} models=${NOTEBOOK_AI_MODELS:-}"
echo "STUBOPTS [${JAVA_OPTS:-}]"
for a in "$@"; do printf 'ARG[%s]\n' "$a"; done
exit "${NBAI_STUB_EXIT:-0}"
EOS
    chmod 755 "$d/dist/bin/notebook-ai"
    echo "main-$tag" > "$d/dist/lib/notebook-ai-0.0.0.jar"
    echo ort > "$d/dist/lib/onnxruntime-1.22.0.jar"
    jfx=linux; [ "$plat" = win-x64 ] && jfx=win
    echo jfx > "$d/dist/lib/javafx-graphics-21.0.7-$jfx.jar"
    [ "$tag" = v1 ] && echo stale > "$d/dist/lib/stale-only-in-v1.jar"
    [ "$tag" = v2 ] && echo fresh > "$d/dist/lib/new-in-v2.jar"
    for f in det.onnx det.yml rec.onnx rec.yml; do echo "model-$f" > "$d/models/$f"; done
    ( cd "$d/models" && sha256sum det.onnx det.yml rec.onnx rec.yml > checksums.sha256 )
}
FX=$T/fx1; mkfix "$FX" v1
FX2=$T/fx2; mkfix "$FX2" v2

N=0
newhome() { N=$((N+1)); H=$T/home$N; mkdir -p "$H"; }
inst() { # isolated installer run; reads IPATH FB JH H INSTALLER (and SUDO_USER / NBAI_REFRESH_USER when a scenario sets them)
    env -i HOME="$H" PATH="$IPATH" TMPDIR="$T" SHELL=/bin/bash NOTEBOOK_AI_JDK_FALLBACK="$FB" ${JH:+JAVA_HOME=$JH} ${SUDO_USER:+SUDO_USER=$SUDO_USER} ${NBAI_REFRESH_USER:+NBAI_REFRESH_USER=$NBAI_REFRESH_USER} \
        sh "${INSTALLER:-$ROOT/install.sh}" "$@"
}
run() { OUT=$(inst "$@" 2>&1); RC=$?; }
fixargs() { printf '%s\n' --dist "${1:-$FX}/dist" --models "${1:-$FX}/models"; }
snapshot() { ( cd "$1" 2>/dev/null && find . | sort ) 2>/dev/null; }

echo "== 1. usage"
OUT=$(sh "$ROOT/install.sh" --help 2>&1); RC=$?; t "--help exits 0" $RC; tt "--help documents --uninstall and --prefix" "$OUT" "--uninstall"; tt "--help names the prep commands" "$OUT" "make build models"
OUT=$(sh "$ROOT/install.sh" --bogus 2>&1); RC=$?; [ "$RC" = 2 ]; t "unknown option exits 2" $?
OUT=$(sh "$ROOT/install.sh" --prefix /x --app-dir /y 2>&1); RC=$?; [ "$RC" = 2 ]; t "--prefix with --app-dir exits 2" $?

echo "== 2. fresh per-user install, run from another directory, repository path with spaces"
newhome
RC_DIR="$T/repo copy"; mkdir -p "$RC_DIR/build/install" "$RC_DIR/tools"; cp "$ROOT/install.sh" "$RC_DIR/install.sh"; mkdir -p "$RC_DIR/packaging"; cp "$ROOT/packaging/launcher-body.sh" "$RC_DIR/packaging/launcher-body.sh"
cp -R "$FX/dist" "$RC_DIR/build/install/notebook-ai"; cp -R "$FX/models" "$RC_DIR/models"
before=$(snapshot "$RC_DIR")
OUT=$(cd / && inst_default=1 env -i HOME="$H" PATH="$IPATH" TMPDIR="$T" SHELL=/bin/bash NOTEBOOK_AI_JDK_FALLBACK=/nonexistent sh "$RC_DIR/install.sh" 2>&1); RC=$?
t "install from '/' using resources next to install.sh" $RC "$OUT"
APP=$H/.local/share/notebook-ai; CMD=$H/.local/bin/notebook-ai
[ -f "$APP/.notebook-ai-install" ] && [ -f "$APP/MANIFEST.sha256" ] && [ -f "$APP/INSTALL.info" ] && [ -x "$APP/dist/bin/notebook-ai" ] && [ -f "$APP/models/det.onnx" ]; t "default layout: app dir under XDG data dir with dist/ models/ marker manifest" $?
[ -x "$CMD" ]; t "command installed at ~/.local/bin/notebook-ai" $?
[ "$before" = "$(snapshot "$RC_DIR")" ]; t "nothing written into the repository directory" $?
tt "report names application and command" "$OUT" "application : $APP"
tt "report: bin dir not on PATH -> exact instruction for the user's shell (bash)" "$OUT" "export PATH=\"$H/.local/bin:\$PATH\""
tt "report never edits startup files (says so)" "$OUT" "never edits shell startup files"
[ ! -e "$H/.bashrc" ] && [ ! -e "$H/.profile" ] && [ ! -e "$H/.zshrc" ]; t "no shell startup file was created or modified" $?
OUT=$(inst --status 2>&1); RC=$?; t "--status verifies integrity" $RC "$OUT"; tt "--status says integrity OK" "$OUT" "Integrity             : OK"

echo "== 3. launcher: any cwd, exact argument forwarding, exit status, models, ownership marker"
mkdir -p "$T/some where"
OUT=$(cd "$T/some where" && env -i HOME="$H" PATH="$IPATH" NBAI_STUB_EXIT=7 "$CMD" "a b" "c'd" '$x' '*' '' '--flag=v w' 2>&1); RC=$?
[ "$RC" = 7 ]; t "launcher preserves the application's exit status (7)" $? "got $RC"
tt "arg with a space forwarded intact" "$OUT" "ARG[a b]"; tt "arg with a quote forwarded intact" "$OUT" "ARG[c'd]"
tt "arg with a dollar not expanded" "$OUT" 'ARG[$x]'; tt "glob arg not expanded" "$OUT" 'ARG[*]'; tt "empty arg forwarded" "$OUT" "ARG[]"; tt "option with space forwarded" "$OUT" "ARG[--flag=v w]"
tt "models resolved inside the installation (not the cwd or repo)" "$OUT" "models=$APP/models"
mkdir -p "$T/custom models"; : > "$T/custom models/det.onnx"
OUT=$(cd / && env -i HOME="$H" PATH="$IPATH" NOTEBOOK_AI_MODELS="$T/custom models" "$CMD" 2>&1); tt "user NOTEBOOK_AI_MODELS is respected" "$OUT" "models=$T/custom models"
OUT=$(cd / && env -i HOME="$H" PATH="$IPATH" NOTEBOOK_AI_MODELS="$T/no-such-models" "$CMD" 2>&1); RC=$?; [ "$RC" = 1 ] && has "$OUT" "OCR model files were not found"; t "missing models: launcher fails with an actionable message (no silent download)" $? "$OUT"
sed -n 2p "$CMD" | grep -qx '# notebook-ai-launcher: owned-by=notebook-ai-installer format=1'; t "launcher carries the ownership marker" $?
grep -q "$RC_DIR" "$CMD" "$APP/INSTALL.info" 2>/dev/null; [ $? -ne 0 ]; t "installed files do not reference the repository/build directory" $?

echo "== 4. Java checks (install time and launch time)"
newhome; IPATH=$FJ17:$MINBIN; run $(fixargs); IPATH=$BASEPATH
[ "$RC" = 1 ] && has "$OUT" "Java 17 found"; t "Java 17 refused at install, actionable message" $? "$OUT"; [ ! -e "$H/.local" ]; t "nothing created after the Java refusal" $?
IPATH=$MINBIN; run $(fixargs); IPATH=$BASEPATH
[ "$RC" = 1 ] && has "$OUT" "no Java found"; t "no Java at all refused" $? "$OUT"
JH=$T/does-not-exist; run $(fixargs); JH=
[ "$RC" = 1 ] && has "$OUT" "JAVA_HOME is set to"; t "invalid JAVA_HOME refused" $? "$OUT"
newhome; run $(fixargs); t "install with Java 21 works" $RC
CMD=$H/.local/bin/notebook-ai
OUT=$(env -i HOME="$H" PATH="$FJ17:$MINBIN" NOTEBOOK_AI_JDK_FALLBACK=/nonexistent "$CMD" 2>&1); RC=$?
[ "$RC" = 1 ] && has "$OUT" "Java 17 found"; t "launcher refuses Java 17 at launch with guidance" $? "$OUT"
OUT=$(env -i HOME="$H" PATH="$MINBIN" NOTEBOOK_AI_JDK_FALLBACK=/nonexistent "$CMD" 2>&1); RC=$?
[ "$RC" = 1 ] && has "$OUT" "Java 21 or newer was not found"; t "launcher reports missing Java" $? "$OUT"
OUT=$(env -i HOME="$H" PATH="$FJ17:$MINBIN" NOTEBOOK_AI_JDK_FALLBACK="$T/j21" "$CMD" x 2>&1); RC=$?
[ "$RC" = 0 ] && has "$OUT" "java_home=$T/j21"; t "old default Java but a JDK 21 fallback exists: launcher switches JAVA_HOME" $? "$OUT"

for jm in 21 23 24 25; do mkjava "$T/jm$jm" $jm; done
launch_opts() { env -i HOME="$H" PATH="$T/jm$1/bin:$MINBIN" ${2:+JAVA_OPTS="$2"} NOTEBOOK_AI_JDK_FALLBACK=/nonexistent "$CMD" 2>&1; }
OUT=$(launch_opts 21); tn "Java 21: no JVM flags are added (it would reject the Unsafe flag)" "$OUT" "--sun-misc-unsafe-memory-access"; tn "Java 21: no native-access flag" "$OUT" "--enable-native-access"
OUT=$(launch_opts 23); tt "Java 23: the Unsafe flag is added" "$OUT" "--sun-misc-unsafe-memory-access=allow"; tn "Java 23: no native-access flag yet" "$OUT" "--enable-native-access"
OUT=$(launch_opts 24); tt "Java 24: Unsafe and native-access flags are added" "$OUT" "--sun-misc-unsafe-memory-access=allow --enable-native-access=javafx.graphics,ALL-UNNAMED"
OUT=$(launch_opts 25); tt "Java 25: Unsafe and native-access flags are added" "$OUT" "--enable-native-access=javafx.graphics,ALL-UNNAMED"
OUT=$(launch_opts 25 "-Xmx3g"); tt "the user's own JAVA_OPTS are kept in front of the added flags" "$OUT" "STUBOPTS [-Xmx3g --sun-misc-unsafe-memory-access=allow"

echo "== 5. upgrade replaces the whole app, removes stale libs, keeps unrelated files, no temp leftovers"
newhome; run $(fixargs "$FX"); APP=$H/.local/share/notebook-ai; CMD=$H/.local/bin/notebook-ai
mkdir -p "$H/.local/share/other-app"; echo keep > "$H/.local/share/other-app/keep.txt"; printf '#!/bin/sh\necho other\n' > "$H/.local/bin/other-cmd"; chmod 755 "$H/.local/bin/other-cmd"
[ -f "$APP/dist/lib/stale-only-in-v1.jar" ]; t "v1 install contains the soon-stale library" $?
old_info=$(cat "$APP/INSTALL.info")
run $(fixargs "$FX2"); t "upgrade to v2 succeeds" $RC "$OUT"; tt "upgrade is reported" "$OUT" "upgraded an existing installation"
[ ! -e "$APP/dist/lib/stale-only-in-v1.jar" ] && [ -f "$APP/dist/lib/new-in-v2.jar" ]; t "stale library removed, new library present (no old/new mixing)" $?
[ "$old_info" != "$(cat "$APP/INSTALL.info")" ]; t "INSTALL.info reflects the new build" $?
[ -z "$(ls -A "$H/.local/share" | grep -e 'installing' -e 'previous')" ]; t "no installer temp/previous directories left behind" $?
[ -f "$H/.local/share/other-app/keep.txt" ] && [ -x "$H/.local/bin/other-cmd" ]; t "unrelated app dir and command preserved" $?
run $(fixargs "$FX2"); t "re-running an identical install is idempotent" $RC

echo "== 6. custom directories with spaces and non-ASCII characters"
newhome; CA="$T/Ünï dir/my app"; CB="$T/Ünï dir/b in"; mkdir -p "$T/Ünï dir"; echo mine > "$T/Ünï dir/user-file.txt"
run --app-dir "$CA" --bin-dir "$CB" $(fixargs); t "install with --app-dir/--bin-dir containing spaces and non-ASCII" $RC "$OUT"
OUT=$(cd / && env -i PATH="$IPATH" NBAI_STUB_EXIT=3 "$CB/notebook-ai" "x y" 2>&1); RC=$?
[ "$RC" = 3 ] && has "$OUT" "ARG[x y]" && has "$OUT" "models=$CA/models"; t "launcher works for those paths (args, exit status, models)" $? "$OUT"
run --app-dir "$CA" --bin-dir "$CB" --uninstall; t "uninstall with the same custom paths" $RC "$OUT"
[ ! -e "$CA" ] && [ ! -e "$CB/notebook-ai" ] && [ -d "$CB" ] && [ -f "$T/Ünï dir/user-file.txt" ]; t "only the owned app and command were removed; directories and user file stay" $?

echo "== 7. missing staged distribution / models leave an existing installation untouched"
newhome; run $(fixargs); APP=$H/.local/share/notebook-ai; info=$(cat "$APP/INSTALL.info")
run --dist "$T/nope" --models "$FX/models"; [ "$RC" = 1 ] && has "$OUT" "make build"; t "missing dist: exit 1 with the exact normal-user command" $? "$OUT"
tt "message says not to use sudo" "$OUT" "do NOT use sudo"
[ "$info" = "$(cat "$APP/INSTALL.info")" ] && sh "$H/.local/bin/notebook-ai" >/dev/null 2>&1; t "previous installation unchanged and still launches" $?
run --dist "$FX/dist" --models "$T/nomodels"; [ "$RC" = 1 ] && has "$OUT" "make models"; t "missing models: exit 1 with the preparation command" $? "$OUT"
cp -R "$FX/models" "$T/badmodels"; echo tampered >> "$T/badmodels/det.onnx"
run --dist "$FX/dist" --models "$T/badmodels"; [ "$RC" = 1 ] && has "$OUT" "checksum verification failed"; t "tampered model refused (checksums preserved, not invented)" $? "$OUT"
echo broken > "$T/nodist-file"; mkdir -p "$T/emptydist"
run --dist "$T/emptydist" --models "$FX/models"; [ "$RC" = 1 ]; t "incomplete distribution directory refused" $?
[ "$info" = "$(cat "$APP/INSTALL.info")" ]; t "installation still unchanged after all refusals" $?

echo "== 8. platform / architecture selection"
newhome; mkfix "$T/fxwin" v1 win-x64
run $(fixargs "$T/fxwin"); [ "$RC" = 1 ] && has "$OUT" "is for 'win-x64'"; t "a Windows distribution is rejected on Linux" $? "$OUT"
mkfix "$T/fxmix" v1 linux-x64; echo x > "$T/fxmix/dist/lib/javafx-base-21.0.7-win.jar"
run $(fixargs "$T/fxmix"); [ "$RC" = 1 ] && has "$OUT" "another operating system"; t "mixed-platform JavaFX jars rejected" $? "$OUT"
mkdir -p "$T/fakebin-arm"; printf '#!/bin/sh\ncase "$1" in -m) echo aarch64;; -s) echo Linux;; *) echo Linux;; esac\n' > "$T/fakebin-arm/uname"; chmod 755 "$T/fakebin-arm/uname"
IPATH=$T/fakebin-arm:$BASEPATH; run $(fixargs); IPATH=$BASEPATH
[ "$RC" = 1 ] && has "$OUT" "unsupported architecture 'aarch64'"; t "aarch64 reported as unsupported (not treated as x86-64)" $? "$OUT"
printf '#!/bin/sh\ncase "$1" in -m) echo x86_64;; *) echo Darwin;; esac\n' > "$T/fakebin-arm/uname"
IPATH=$T/fakebin-arm:$BASEPATH; run $(fixargs); IPATH=$BASEPATH
[ "$RC" = 1 ] && has "$OUT" "unsupported operating system 'Darwin'"; t "macOS reported as unsupported" $? "$OUT"

echo "== 9. unsafe targets are refused before anything is changed"
newhome
unsafe() { # unsafe "name" args... ; expects non-zero exit, a refusal message and an unchanged filesystem snapshot
    nm=$1; shift; snap1=$(snapshot "$T/home$N"); run "$@" $(fixargs); snap2=$(snapshot "$T/home$N")
    [ "$RC" != 0 ] && [ "$snap1" = "$snap2" ]; t "$nm refused" $? "rc=$RC $OUT"
}
unsafe "root '/'" --app-dir / --bin-dir "$H/b"
unsafe "HOME itself" --app-dir "$H" --bin-dir "$H/b"
unsafe "~/.local" --app-dir "$H/.local" --bin-dir "$H/b"
unsafe "/usr/local" --app-dir /usr/local --bin-dir "$H/b"
unsafe "/tmp" --app-dir /tmp --bin-dir "$H/b"
unsafe "ancestor of HOME" --app-dir "$T" --bin-dir "$H/b"
unsafe "relative app dir" --app-dir rel/dir --bin-dir "$H/b"
unsafe "'..' segments" --app-dir "$T/x/../y" --bin-dir "$H/b"
unsafe "the repository" --app-dir "$ROOT" --bin-dir "$H/b"
unsafe "inside the repository" --app-dir "$ROOT/build/app" --bin-dir "$H/b"
unsafe "ancestor of the repository" --app-dir "$(dirname "$ROOT")" --bin-dir "$H/b"
unsafe "~/Desktop" --app-dir "$H/Desktop" --bin-dir "$H/b"
unsafe "bin dir inside app dir" --app-dir "$H/app" --bin-dir "$H/app/bin"
unsafe "app dir inside bin dir" --app-dir "$H/b/app" --bin-dir "$H/b"
unsafe "path containing a newline" --app-dir "$H/line
break" --bin-dir "$H/b"
mkdir -p "$H/occupied"; echo precious > "$H/occupied/file.txt"
run --app-dir "$H/occupied" --bin-dir "$H/b" $(fixargs); [ "$RC" != 0 ] && [ "$(cat "$H/occupied/file.txt")" = precious ] && has "$OUT" "no ownership marker"; t "existing non-empty directory without marker refused and untouched" $? "$OUT"
mkdir -p "$H/b2"; printf '#!/bin/sh\necho foreign\n' > "$H/b2/notebook-ai"; chmod 755 "$H/b2/notebook-ai"
run --app-dir "$H/newapp" --bin-dir "$H/b2" $(fixargs); [ "$RC" != 0 ] && grep -q foreign "$H/b2/notebook-ai" && [ ! -e "$H/newapp" ]; t "another program's 'notebook-ai' command is never overwritten" $? "$OUT"
mkdir -p "$H/b3/notebook-ai"; run --app-dir "$H/newapp3" --bin-dir "$H/b3" $(fixargs); [ "$RC" != 0 ]; t "command path that is a directory refused" $?
mkdir -p "$H/real"; ln -s "$H/real" "$H/linkapp"; run --app-dir "$H/linkapp" --bin-dir "$H/b4" $(fixargs); [ "$RC" != 0 ] && [ -z "$(ls -A "$H/real")" ]; t "symlinked app dir refused" $?
IPATH=$BASEPATH; mkdir -p "$T/fakeroot"; printf '#!/bin/sh\necho 0\n' > "$T/fakeroot/id"; chmod 755 "$T/fakeroot/id"
IPATH=$T/fakeroot:$BASEPATH; run $(fixargs); [ "$RC" != 0 ] && has "$OUT" "as root"; t "per-user install refused as root" $? "$OUT"
run --prefix "$H/rootprefix" --build $(fixargs); [ "$RC" != 0 ] && has "$OUT" "refuses to run Gradle as root"; t "--build refused as root (never runs Gradle as root)" $? "$OUT"
IPATH=$BASEPATH

echo "== 9b. installing as root through sudo: guidance is about the invoking user, not root's PATH"
newhome; SH=$H/sudohome; mkdir -p "$SH/.local/bin" "$T/fakesudo"
printf '#!/bin/sh\n# notebook-ai-launcher: owned-by=notebook-ai-installer format=1\nNBAI_APP='"'"'/somewhere/per-user-app'"'"'\n' > "$SH/.local/bin/notebook-ai"; chmod 755 "$SH/.local/bin/notebook-ai"
printf '#!/bin/sh\necho 0\n' > "$T/fakesudo/id"; printf '#!/bin/sh\necho "kali:x:1000:1000::%s:/bin/sh"\n' "$SH" > "$T/fakesudo/getent"; chmod 755 "$T/fakesudo/id" "$T/fakesudo/getent"
IPATH=$T/fakesudo:$BASEPATH; SUDO_USER=kali; export SUDO_USER; run --prefix "$H/sysprefix" $(fixargs); unset SUDO_USER; IPATH=$BASEPATH
t "system-wide install as root via sudo succeeds" $RC "$OUT"
tt "it reports the invoking user's per-user installation that this install did not change" "$OUT" "kali also has a per-user installation (/somewhere/per-user-app)"
tt "...and says how to use only the system-wide one or refresh the per-user one" "$OUT" "sh install.sh --uninstall"
tn "no bogus warning about root's PATH ('resolves to')" "$OUT" "resolves to"
[ -e "$SH/.local/bin/notebook-ai" ] && grep -q per-user-app "$SH/.local/bin/notebook-ai"; t "the per-user command was not modified by the root install" $?
IPATH=$T/fakesudo:$BASEPATH; SUDO_USER=kali; export SUDO_USER; NBAI_REFRESH_USER=1 run --prefix "$H/sysprefix2" $(fixargs); unset SUDO_USER; IPATH=$BASEPATH
tn "when the per-user install is refreshed right after, it is not called 'unchanged'" "$OUT" "NOT changed by this system-wide install"
newhome; printf '#!/bin/sh\necho kali\n' > "$T/fakesudo/id-un-unused"
mkdir -p "$T/asuser"; printf '#!/bin/sh\ncase "$1" in -un) echo kali;; -u) echo 1000;; *) echo 1000;; esac\n' > "$T/asuser/id"; chmod 755 "$T/asuser/id"
IPATH=$T/asuser:$BASEPATH; SUDO_USER=kali; export SUDO_USER; run $(fixargs); unset SUDO_USER; IPATH=$BASEPATH
t "per-user install run as the user BY sudo succeeds" $RC "$OUT"
tn "...and makes no claim that its directory is not on PATH (sudo's PATH is not the user's)" "$OUT" "is not on your PATH"
tt "...and tells the user how to check in a fresh terminal" "$OUT" "command -v notebook-ai"
( eval "$(sed -n '/^system_bin_dir()/p' "$ROOT/install.sh")"; system_bin_dir /usr/local/bin && system_bin_dir /usr/bin && ! system_bin_dir /opt/x/bin ); t "standard system bin directories are not reported as 'not on PATH'" $?

echo "== 10. command shadowing and PATH reporting"
newhome; mkdir -p "$T/shadow"; printf '#!/bin/sh\necho shadow\n' > "$T/shadow/notebook-ai"; chmod 755 "$T/shadow/notebook-ai"
IPATH=$T/shadow:$BASEPATH; run $(fixargs); IPATH=$BASEPATH
tt "shadowing command detected and named" "$OUT" "resolves to $T/shadow/notebook-ai"; tt "explicit invocation path is shown" "$OUT" "Run this installation explicitly with: '$H/.local/bin/notebook-ai'"
grep -q shadow "$T/shadow/notebook-ai"; t "the shadowing command was not modified" $?
newhome; IPATH=$H/.local/bin:$T/shadow:$BASEPATH; mkdir -p "$H/.local/bin"; run $(fixargs); IPATH=$BASEPATH
tn "no PATH note when the command dir is already on PATH" "$OUT" "is not on your PATH"; tn "no shadow warning when this command comes first" "$OUT" "resolves to"

echo "== 11. old 'make install' layout: detected, never migrated automatically"
newhome; mkdir -p "$H/.local/bin" "$H/.local/lib/notebook-ai/bin" "$H/.local/lib/notebook-ai/lib" "$H/.local/lib/notebook-ai/models"
printf '#!/bin/sh\n# notebook-ai launcher (installed by make install).\necho legacy\n' > "$H/.local/bin/notebook-ai"; chmod 755 "$H/.local/bin/notebook-ai"
printf '#!/bin/sh\n' > "$H/.local/lib/notebook-ai/bin/notebook-ai"; chmod 755 "$H/.local/lib/notebook-ai/bin/notebook-ai"; echo old > "$H/.local/lib/notebook-ai/lib/old.jar"
run $(fixargs); [ "$RC" != 0 ] && has "$OUT" "OLD 'make install'" && grep -q legacy "$H/.local/bin/notebook-ai"; t "legacy command not replaced without --replace-legacy" $? "$OUT"
run --replace-legacy $(fixargs); t "legacy command replaced with explicit --replace-legacy" $RC "$OUT"
sed -n 2p "$H/.local/bin/notebook-ai" | grep -q 'owned-by=notebook-ai-installer'; t "the command is now an owned launcher" $?
[ ! -e "$H/.local/lib/notebook-ai" ]; t "per-user upgrade with consent replaces the user-owned OLD-layout directory (exact old structure)" $?
tt "the update is reported" "$OUT" "OLD-layout application directory $H/.local/lib/notebook-ai was replaced"
[ -x "$H/.local/share/notebook-ai/dist/bin/notebook-ai" ] || [ -d "$H/.local/share/notebook-ai" ]; t "the new installation is in place" $?
# not exactly the old structure (a user's own files inside): never removed, still reported
newhome; mkdir -p "$H/.local/bin" "$H/.local/lib/notebook-ai/bin" "$H/.local/lib/notebook-ai/lib" "$H/.local/lib/notebook-ai/models" "$H/.local/lib/notebook-ai/my-projects"
printf '#!/bin/sh\n# notebook-ai launcher (installed by make install).\n' > "$H/.local/bin/notebook-ai"; chmod 755 "$H/.local/bin/notebook-ai"
printf '#!/bin/sh\n' > "$H/.local/lib/notebook-ai/bin/notebook-ai"; chmod 755 "$H/.local/lib/notebook-ai/bin/notebook-ai"; echo p > "$H/.local/lib/notebook-ai/my-projects/p.nbai"
run --replace-legacy $(fixargs); [ -f "$H/.local/lib/notebook-ai/my-projects/p.nbai" ]; t "an old directory with extra files is NEVER removed by the upgrade" $?
tt "...and it is still reported with the removal instructions" "$OUT" "OLD-layout installation at $H/.local/lib/notebook-ai"
# without consent nothing about the old install changes
newhome; mkdir -p "$H/.local/bin" "$H/.local/lib/notebook-ai/bin" "$H/.local/lib/notebook-ai/lib" "$H/.local/lib/notebook-ai/models"
printf '#!/bin/sh\n' > "$H/.local/lib/notebook-ai/bin/notebook-ai"; chmod 755 "$H/.local/lib/notebook-ai/bin/notebook-ai"
run $(fixargs); [ -d "$H/.local/lib/notebook-ai" ]; t "without --replace-legacy the old directory is untouched and reported" $? "$OUT"
tt "...with the removal instructions" "$OUT" "OLD-layout installation at $H/.local/lib/notebook-ai"
# classic prefix layout (what 'make install PREFIX=...' used): replacing an old-layout dir needs the flag
P=$T/prefix; mkdir -p "$P/lib/notebook-ai/bin" "$P/lib/notebook-ai/lib" "$P/lib/notebook-ai/models" "$P/bin"
printf '#!/bin/sh\n' > "$P/lib/notebook-ai/bin/notebook-ai"; chmod 755 "$P/lib/notebook-ai/bin/notebook-ai"
run --prefix "$P" $(fixargs); [ "$RC" != 0 ] && has "$OUT" "OLD 'make install' layout"; t "prefix: old-layout app directory not replaced silently" $? "$OUT"
rm -f "$P/bin/notebook-ai"
run --prefix "$P" --replace-legacy $(fixargs); t "prefix: replaced with --replace-legacy" $RC "$OUT"
[ -f "$P/lib/notebook-ai/.notebook-ai-install" ] && [ -x "$P/bin/notebook-ai" ]; t "prefix layout: <prefix>/lib/notebook-ai + <prefix>/bin/notebook-ai" $?
DD=$T/destdir; run --prefix /opt/nbai-final --destdir "$DD" $(fixargs); t "--destdir staging install" $RC "$OUT"
[ -f "$DD/opt/nbai-final/lib/notebook-ai/.notebook-ai-install" ] && grep -q "NBAI_APP='/opt/nbai-final/lib/notebook-ai'" "$DD/opt/nbai-final/bin/notebook-ai"; t "launcher bakes the FINAL prefix path, not the DESTDIR" $?

# explicit legacy removal: exact old structure only, prefix mode only, flag required
newhome; LP=$H/.local; mkdir -p "$LP/bin" "$LP/lib/notebook-ai/bin" "$LP/lib/notebook-ai/lib" "$LP/lib/notebook-ai/models"
printf '#!/bin/sh\n# notebook-ai launcher (installed by make install).\n' > "$LP/bin/notebook-ai"; chmod 755 "$LP/bin/notebook-ai"; printf '#!/bin/sh\n' > "$LP/lib/notebook-ai/bin/notebook-ai"; chmod 755 "$LP/lib/notebook-ai/bin/notebook-ai"
echo mine > "$LP/bin/other-tool"; mkdir -p "$LP/lib/other-lib"; echo k > "$LP/lib/other-lib/k"
run --uninstall --prefix "$LP"; [ -d "$LP/lib/notebook-ai" ] && [ -f "$LP/bin/notebook-ai" ] && has "$OUT" "--replace-legacy"; t "uninstall of an old-layout install is refused without the flag and names the exact command" $? "$OUT"
run --uninstall --prefix "$LP" --replace-legacy; t "uninstall with --replace-legacy succeeds" $RC "$OUT"
[ ! -e "$LP/lib/notebook-ai" ] && [ ! -e "$LP/bin/notebook-ai" ] && [ -f "$LP/bin/other-tool" ] && [ -f "$LP/lib/other-lib/k" ]; t "only the old app directory and old command are removed; siblings stay" $?
mkdir -p "$LP/lib/notebook-ai/bin" "$LP/lib/notebook-ai/lib" "$LP/lib/notebook-ai/models" "$LP/lib/notebook-ai/my-projects"; printf '#!/bin/sh\n' > "$LP/lib/notebook-ai/bin/notebook-ai"; chmod 755 "$LP/lib/notebook-ai/bin/notebook-ai"; echo p > "$LP/lib/notebook-ai/my-projects/p.nbai"
run --uninstall --prefix "$LP" --replace-legacy; [ -f "$LP/lib/notebook-ai/my-projects/p.nbai" ]; t "a directory that is not exactly the old structure is never removed, even with the flag" $?

echo "== 12. failure handling keeps the previous installation usable"
newhome; run $(fixargs "$FX"); APP=$H/.local/share/notebook-ai; CMD=$H/.local/bin/notebook-ai; info=$(cat "$APP/INSTALL.info")
mkdir -p "$T/failcp"; printf '#!/bin/sh\ncase "$*" in *installing*) echo "simulated cp failure" >&2; exit 1;; esac\nexec /bin/cp "$@"\n' > "$T/failcp/cp"; chmod 755 "$T/failcp/cp"
IPATH=$T/failcp:$BASEPATH; run $(fixargs "$FX2"); IPATH=$BASEPATH
[ "$RC" = 1 ] && [ "$info" = "$(cat "$APP/INSTALL.info")" ] && [ -f "$APP/dist/lib/stale-only-in-v1.jar" ]; t "copy failure: nothing replaced, old files intact" $? "$OUT"
[ -z "$(ls -A "$H/.local/share" | grep -e installing -e previous)" ]; t "copy failure: installer temp directory cleaned up" $?
sh "$CMD" >/dev/null 2>&1; t "copy failure: old installation still launches" $?
mkdir -p "$T/failmv"; printf '#!/bin/sh\ncase "$*" in *.notebook-ai.new.*) echo "simulated mv failure" >&2; exit 1;; esac\nexec /bin/mv "$@"\n' > "$T/failmv/mv"; chmod 755 "$T/failmv/mv"
IPATH=$T/failmv:$BASEPATH; run $(fixargs "$FX2"); IPATH=$BASEPATH
[ "$RC" = 1 ] && has "$OUT" "previous installation was restored"; t "command-write failure: reported, previous installation restored" $? "$OUT"
[ "$info" = "$(cat "$APP/INSTALL.info")" ] && [ -f "$APP/dist/lib/stale-only-in-v1.jar" ] && [ ! -e "$APP/dist/lib/new-in-v2.jar" ]; t "after rollback the old files are back and no new files remain" $?
[ -z "$(ls -A "$H/.local/share" | grep -e installing -e previous)" ] && [ -z "$(ls -A "$H/.local/bin" | grep -e '\.new\.')" ]; t "rollback leaves no temporary files" $?
OUT=$(sh "$CMD" ok 2>&1); has "$OUT" "ARG[ok]"; t "old installation launches after rollback" $?
run --status; t "status integrity OK after rollback" $RC

echo "== 13. uninstall touches only what the installer owns"
newhome; run $(fixargs); APP=$H/.local/share/notebook-ai; CMD=$H/.local/bin/notebook-ai
mkdir -p "$H/Documents" "$H/.local/share/other-app"; echo project > "$H/Documents/work.nbai"; echo exp > "$H/Documents/export.txt"; echo k > "$H/.local/share/other-app/k"; printf '#!/bin/sh\n' > "$H/.local/bin/other"; chmod 755 "$H/.local/bin/other"
run --uninstall; t "uninstall succeeds" $RC "$OUT"
[ ! -e "$APP" ] && [ ! -e "$CMD" ]; t "app directory and owned command removed" $?
[ -f "$H/Documents/work.nbai" ] && [ -f "$H/Documents/export.txt" ] && [ -f "$H/.local/share/other-app/k" ] && [ -x "$H/.local/bin/other" ] && [ -d "$H/.local/bin" ] && [ -d "$H/.local/share" ]; t "projects, exports, other apps, other commands and parent directories retained" $?
tt "uninstall explains what is retained" "$OUT" "Retained: your projects"
run --uninstall; t "uninstall twice is harmless" $RC; tt "second uninstall says nothing to remove" "$OUT" "Nothing was removed"
mkdir -p "$H/notmine"; echo x > "$H/notmine/f"; run --app-dir "$H/notmine" --uninstall; [ -f "$H/notmine/f" ]; t "uninstall never removes a directory without the ownership marker" $?
run $(fixargs); echo different > "$T/otherlauncher"; printf '#!/bin/sh\n# notebook-ai-launcher: owned-by=notebook-ai-installer format=1\nNBAI_APP=%s\n' "'/some/other/place'" > "$H/.local/bin/notebook-ai"
run --uninstall; [ -f "$H/.local/bin/notebook-ai" ] && has "$OUT" "belongs to another notebook-ai installation"; t "a command pointing at a different installation is left alone" $? "$OUT"
newhome; run $(fixargs); echo tamper >> "$H/.local/share/notebook-ai/dist/lib/onnxruntime-1.22.0.jar"
run --status; [ "$RC" = 1 ] && has "$OUT" "FAILED"; t "--status detects a modified installed file" $? "$OUT"

echo "== 14. the real staged distribution (make build), when present"
if [ -f "$ROOT/build/install/notebook-ai/PLATFORM" ] && ( cd "$ROOT/models" 2>/dev/null && sha256sum -c --quiet checksums.sha256 >/dev/null 2>&1 ); then
    newhome; IPATH=$BASEPATH
    OUT=$(env -i HOME="$H" PATH="$IPATH" NOTEBOOK_AI_JDK_FALLBACK=/nonexistent sh "$ROOT/install.sh" 2>&1); RC=$?
    t "real distribution installs into a temporary HOME" $RC "$OUT"
    a=$(sha256sum "$ROOT"/build/install/notebook-ai/lib/notebook-ai-*.jar | cut -d' ' -f1); b=$(sha256sum "$H"/.local/share/notebook-ai/dist/lib/notebook-ai-*.jar | cut -d' ' -f1)
    [ "$a" = "$b" ]; t "installed application jar == staged jar (sha256)" $?
    ( cd "$ROOT/build/install/notebook-ai" && find . -type f -exec sha256sum {} + | sort -k2 | sed 's|  \./|  dist/|' ) > "$T/stg.sum"
    ( cd "$H/.local/share/notebook-ai" && find dist -type f -exec sha256sum {} + | sort -k2 ) > "$T/ins.sum"
    cmp -s "$T/stg.sum" "$T/ins.sum"; t "every file of the staged distribution is byte-identical in the installation" $?
    ( cd "$H/.local/share/notebook-ai/models" && sha256sum -c --quiet checksums.sha256 >/dev/null 2>&1 ); t "installed models verify against checksums.sha256" $?
    ls "$H"/.local/share/notebook-ai/dist/lib/javafx-graphics-*-linux.jar >/dev/null 2>&1; t "installed JavaFX jars are the Linux (module-path) artifacts" $?
    grep -q -- '--module-path' "$H/.local/share/notebook-ai/dist/bin/notebook-ai"; t "start script uses the JavaFX module path" $?
    run --status; t "real installation passes --status" $RC
else
    skip "real distribution not staged/models missing: run 'make build models' first"
fi

echo "== 15. Makefile: delegation, guards, environment detection"
cd "$ROOT" || exit 2
newhome
for tgt in build models test verify run clean dist-linux dist-windows dist package verify-dist dist-clean; do
    out=$(make "$tgt" CURRENT_UID=0 GRADLEW='echo GRADLE-INVOKED' MODELS_CMD='echo GRADLE-INVOKED' 2>&1); rc=$?
    if has "$out" "GRADLE-INVOKED"; then bad "make $tgt as root must not reach Gradle"; else [ $rc -ne 0 ]; t "make $tgt refuses as root" $?; fi
done
out=$(env -i HOME="$H" PATH="$IPATH:$(dirname "$(command -v make)")" make install GRADLEW='echo GRADLE-INVOKED' APP_DIR="$T/m a/app" BIN_DIR="$T/m a/bin" DIST="$FX/dist" MODELS_DIR="$FX/models" 2>&1); rc=$?
t "make install delegates to install.sh (custom dirs with spaces)" $rc "$out"; [ -x "$T/m a/bin/notebook-ai" ] && [ -f "$T/m a/app/.notebook-ai-install" ]; t "make install produced the owned layout in the given dirs" $?
tn "make install never invokes Gradle" "$out" "GRADLE-INVOKED"
env -i HOME="$H" PATH="$IPATH:$(dirname "$(command -v make)")" make uninstall APP_DIR="$T/m a/app" BIN_DIR="$T/m a/bin" >/dev/null 2>&1; [ ! -e "$T/m a/app" ] && [ ! -e "$T/m a/bin/notebook-ai" ]; t "make uninstall delegates and removes only the owned install" $?
out=$(make build NBAI_HOST=Darwin 2>&1); rc=$?; [ $rc -ne 0 ] && has "$out" "Unsupported system 'Darwin'"; t "unsupported OS refused with a clear message" $? "$out"
out=$(make -n build MSYSTEM=MINGW64 OS=Windows_NT 2>&1); tt "MSYS2/Git Bash make refused (static dry-run text)" "$out" "MSYS2 / Git Bash / Cygwin make is not a supported combination"
out=$(make -n clean 2>&1); tt "make clean runs only 'gradlew clean' (build output only)" "$out" "clean"; tn "make clean deletes nothing else (no rm)" "$out" "rm "
out=$(make -n build PLATFORM=win-x64 2>&1); tt "PLATFORM=win-x64 is passed to Gradle explicitly" "$out" "-PtargetPlatform=win-x64"
out=$(make -n dist-linux 2>&1); tt "dist-linux dispatches to the Gradle task distLinux" "$out" "distLinux"
out=$(make -n dist-windows 2>&1); tt "dist-windows dispatches to the Gradle task distWindows" "$out" "distWindows"
out=$(make -n package 2>&1); tt "package dispatches to the Gradle task package" "$out" " package"
out=$(make -n dist-clean 2>&1); tn "dist-clean uses Gradle cleanDist (no rm in the recipe)" "$out" "rm "
# STATIC dispatch text only: this is NOT Windows execution
out=$(make -n build OS=Windows_NT 2>&1); tt "[static] Windows build dispatches to gradlew.bat" "$out" "gradlew.bat"
out=$(make -n install OS=Windows_NT APP_DIR='C:\x y' ADD_TO_PATH=1 2>&1); tt "[static] Windows install dispatches to install.cmd" "$out" "install.cmd --app-dir"; tt "[static] ADD_TO_PATH becomes --add-to-path" "$out" "--add-to-path"

echo "== 16. static source checks"
# A FRESH checkout must have a clean status: .gitattributes line-ending rules that disagree with the committed blobs make every
# fresh clone (and every CI checkout) report modified files and mark built packages "dirty" (found by the first CI run).
if command -v git >/dev/null 2>&1 && git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    rm -rf "$T/fresh-clone"; git clone -q "$ROOT" "$T/fresh-clone" 2>/dev/null
    st=$(git -C "$T/fresh-clone" status --porcelain 2>/dev/null); [ -z "$st" ]; t "a fresh clone of the committed HEAD has a clean status (line-ending rules agree with the blobs)" $? "$st"
else skip "git not available for the fresh-clone check"; fi
if grep -nE '(^|[^a-z_])eval[ (]' "$ROOT/install.sh" "$ROOT/tools/install-windows.ps1" >/dev/null 2>&1; then bad "no eval in the installers"; else ok "no eval in the installers"; fi
if grep -nE 'Invoke-Expression|iex ' "$ROOT/tools/install-windows.ps1" >/dev/null 2>&1; then bad "no Invoke-Expression in the Windows helper"; else ok "no Invoke-Expression in the Windows helper"; fi
if grep -nE 'chmod 777|chown -R|setx |\[Environment\]::SetEnvironmentVariable' "$ROOT/install.sh" "$ROOT/tools/install-windows.ps1" "$ROOT/Makefile" >/dev/null 2>&1; then bad "no chmod 777 / chown -R / setx / SetEnvironmentVariable"; else ok "no chmod 777 / chown -R / setx / SetEnvironmentVariable"; fi
[ "$(head -c 2 "$ROOT/install.cmd" | od -An -c | tr -d ' ')" = '@e' ]; t "install.cmd is a batch file starting with @echo off" $?
if LC_ALL=C grep -q -P '[^\x00-\x7F]' "$ROOT/install.cmd"; then bad "install.cmd is ASCII-only"; else ok "install.cmd is ASCII-only"; fi
grep -q "$(printf '\r')" "$ROOT/install.cmd"; t "install.cmd uses CRLF line endings" $?

echo
echo "RESULT: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" = 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
[ "$FAIL" = 0 ]
