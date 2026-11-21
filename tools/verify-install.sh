#!/bin/sh
# Verifies the build/install safeguards WITHOUT sudo and WITHOUT touching /usr/local or ~/.local:
# everything installs into a throwaway user-owned prefix. Requires a staged build (`make build`).
# Usage: sh tools/verify-install.sh
set -u
cd "$(dirname "$0")/.." || exit 1
fail=0
ok()   { printf 'PASS  %s\n' "$1"; }
bad()  { printf 'FAIL  %s\n' "$1"; fail=1; }
check() { if [ "$2" = 0 ]; then ok "$1"; else bad "$1"; fi; }

[ -x build/install/notebook-ai/bin/notebook-ai ] || { echo "staged build missing: run 'make build' first"; exit 2; }
T=$(mktemp -d "${TMPDIR:-/tmp}/nbai-install-XXXXXX") || exit 2
trap 'rm -rf "$T"' EXIT
P="$T/prefix"; mkdir -p "$P/lib/other-app"; echo keep > "$P/lib/other-app/keep.txt"

# 1. install is a pure copy: it must never invoke Gradle.
out=$(make install PREFIX="$P" GRADLE='echo GRADLE-INVOKED' 2>&1); rc=$?
check "install into temporary prefix succeeds" $rc
case "$out" in *GRADLE-INVOKED*) bad "install must not invoke Gradle"; ;; *) ok "install did not invoke Gradle";; esac

# 2. installed jar identity equals the staged build
a=$(sha256sum build/install/notebook-ai/lib/notebook-ai-0.1.0.jar | cut -d' ' -f1)
b=$(sha256sum "$P/lib/notebook-ai/lib/notebook-ai-0.1.0.jar" | cut -d' ' -f1)
[ "$a" = "$b" ]; check "installed jar == staged jar" $?
[ -x "$P/bin/notebook-ai" ]; check "launcher installed and executable" $?
[ "$(stat -c %u "$P/lib/notebook-ai/lib/notebook-ai-0.1.0.jar")" = "$(id -u)" ]; check "installed files owned by the invoking user" $?

# 3. stale-artifact cleanup is narrow: obsolete app jar removed, sibling apps and models untouched
touch "$P/lib/notebook-ai/lib/obsolete-0.0.9.jar"
echo model-note > "$P/lib/notebook-ai/models/keep-model-note.txt"
make install PREFIX="$P" >/dev/null 2>&1
[ ! -e "$P/lib/notebook-ai/lib/obsolete-0.0.9.jar" ]; check "stale jar removed on reinstall" $?
[ -f "$P/lib/other-app/keep.txt" ]; check "sibling app directory untouched" $?
[ -f "$P/lib/notebook-ai/models/keep-model-note.txt" ]; check "models directory not wiped" $?

# 4. unsafe APP_DIR is refused before anything is deleted
mkdir -p "$T/victim/lib"; echo precious > "$T/victim/lib/file.txt"
make install PREFIX="$P" APP_DIR="$T/victim" >/dev/null 2>&1; rc=$?
[ $rc -ne 0 ] && [ -f "$T/victim/lib/file.txt" ]; check "unsafe APP_DIR refused, nothing deleted" $?

# 5. Gradle-calling targets refuse to run as root (guard exercised via CURRENT_UID, no real root needed)
for t in build models test verify run clean; do
  out=$(make "$t" CURRENT_UID=0 GRADLE='echo GRADLE-INVOKED' 2>&1); rc=$?
  case "$out" in *GRADLE-INVOKED*) bad "make $t as root must not reach Gradle";; *) [ $rc -ne 0 ] && ok "make $t refuses as root" || bad "make $t did not fail as root";; esac
done

# 6. missing staged build: actionable message, and NO build is started (even as root)
E=$(mktemp -d "$T/empty-XXXXXX"); mkdir "$E/models"
for uid in "" 0; do
  out=$(cd "$E" && make -f "$OLDPWD/Makefile" install PREFIX="$P" ${uid:+CURRENT_UID=$uid} GRADLE='echo GRADLE-INVOKED' 2>&1); rc=$?
  case "$out" in *GRADLE-INVOKED*) bad "missing build must not trigger a build";; *) [ $rc -ne 0 ] && echo "$out" | grep -q "make build" && ok "missing staged app: fails with normal-user build instructions${uid:+ (uid=$uid)}" || bad "missing staged app message";; esac
done

# 7. shadowed launcher warning still works
F="$T/fakebin"; mkdir "$F"; printf '#!/bin/sh\necho old\n' > "$F/notebook-ai"; chmod 755 "$F/notebook-ai"
out=$(PATH="$F:$PATH" make install PREFIX="$P" 2>&1)
echo "$out" | grep -q "WARNING: 'notebook-ai' on your PATH resolves to $F/notebook-ai"; check "shadowed launcher warning" $?

[ $fail = 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $fail
