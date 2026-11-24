#!/bin/sh
# Package-manager lifecycle test of the .deb with apt: install (with dependency resolution), run, upgrade, remove, purge.
#
# *** DISPOSABLE ENVIRONMENT ONLY ***  It runs apt as root and installs the package system-wide, so it REFUSES to run unless
# NBAI_DISPOSABLE_ENV=1 is set to confirm that this is a throw-away VM/container/CI runner. Never run it on a developer machine.
#
#   docker run --rm -v "$PWD:/src:ro" -e NBAI_DISPOSABLE_ENV=1 ubuntu:22.04 sh /src/tools/test-deb-lifecycle.sh /src/release-output/notebook-ai_0.1.0_amd64.deb
#   (this suite is no longer run by CI; the fast .github/workflows/installer-smoke.yml covers build/install/self-check only)
set -u
[ "${NBAI_DISPOSABLE_ENV:-}" = 1 ] || { echo "refusing: this test installs packages as root. Set NBAI_DISPOSABLE_ENV=1 ONLY inside a disposable VM/container/CI runner."; exit 2; }
[ "$(id -u)" = 0 ] || { echo "run as root inside the disposable environment"; exit 2; }
DEB=${1:-$(ls "$(dirname "$0")"/../release-output/notebook-ai_*_amd64.deb 2>/dev/null | head -1)}
[ -f "$DEB" ] || { echo "no .deb found: pass its path"; exit 2; }
PASS=0 FAIL=0
ok() { PASS=$((PASS+1)); printf 'PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf 'FAIL  %s\n' "$1"; [ -n "${2:-}" ] && printf '      %s\n' "$2"; }
t() { if [ "$2" = 0 ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }
has() { case "$1" in *"$2"*) return 0 ;; esac; return 1; }
export DEBIAN_FRONTEND=noninteractive
W=$(mktemp -d); cp "$DEB" "$W/notebook-ai.deb"; chmod 755 "$W"

echo "== prerequisites of the environment (a clean machine must have NO Java)"
! command -v java >/dev/null 2>&1; t "no java on this machine before the test" $?
useradd -m -s /bin/sh tester 2>/dev/null; mkdir -p /home/tester/Documents /home/tester/.local/bin
echo project > /home/tester/Documents/work.nbai; printf '#!/bin/sh\necho user-own\n' > /home/tester/.local/bin/notebook-ai; chmod 755 /home/tester/.local/bin/notebook-ai; chown -R tester /home/tester
apt-get update -qq >/dev/null 2>&1

echo "== apt install (dependencies resolved by apt)"
OUT=$(apt-get install -y "$W/notebook-ai.deb" 2>&1); RC=$?; t "apt-get install ./notebook-ai.deb succeeds" $RC "$(echo "$OUT" | tail -5)"
has "$OUT" "may shadow /usr/bin/notebook-ai"; t "installer printed the shadowing notice for the user's ~/.local command" $?
command -v notebook-ai | grep -q '^/usr/bin/notebook-ai$'; t "notebook-ai is on the normal system PATH (/usr/bin)" $?
OUT=$(su tester -c 'cd / && env -i PATH=/usr/bin:/bin /usr/bin/notebook-ai --self-check' 2>&1); RC=$?; t "notebook-ai --self-check works as a normal user with no Java installed" $RC "$OUT"
has "$OUT" "Java home: /opt/notebook-ai/lib/runtime"; t "it ran on the bundled runtime" $?
! command -v java >/dev/null 2>&1; t "installing the package did not pull in a system Java" $?
dpkg -s notebook-ai | grep -q 'Status: install ok installed'; t "dpkg status: installed" $?
[ "$(cat /home/tester/Documents/work.nbai)" = project ] && [ "$(/home/tester/.local/bin/notebook-ai)" = user-own ]; t "user data and the user's own ~/.local command untouched by the install" $?

echo "== upgrade"
mkdir -p "$W/u" && dpkg-deb -R "$W/notebook-ai.deb" "$W/u/p" && sed -i 's/^Version: .*/Version: 99.0.0/' "$W/u/p/DEBIAN/control" && echo fresh > "$W/u/p/opt/notebook-ai/lib/added-in-99.txt" && rm -f "$W/u/p/DEBIAN/md5sums" && dpkg-deb --root-owner-group -b "$W/u/p" "$W/new.deb" >/dev/null 2>&1
OUT=$(apt-get install -y "$W/new.deb" 2>&1); RC=$?; t "apt upgrades to the next version" $RC "$(echo "$OUT" | tail -3)"
[ "$(dpkg-query -W -f='${Version}' notebook-ai)" = 99.0.0 ] && [ -f /opt/notebook-ai/lib/added-in-99.txt ]; t "new version and new file installed" $?
[ "$(dpkg -l | grep -c ' notebook-ai ')" = 1 ]; t "exactly one notebook-ai installed after the upgrade" $?

echo "== remove and purge"
OUT=$(apt-get remove -y notebook-ai 2>&1); RC=$?; t "apt-get remove" $RC "$OUT"
[ ! -e /opt/notebook-ai ] && [ ! -e /usr/bin/notebook-ai ]; t "application and command removed" $?
OUT=$(apt-get purge -y notebook-ai 2>&1); RC=$?; t "apt-get purge" $RC "$OUT"
[ "$(cat /home/tester/Documents/work.nbai)" = project ] && [ "$(/home/tester/.local/bin/notebook-ai)" = user-own ]; t "user projects and ~/.local command preserved through remove and purge" $?
echo; echo "RESULT: $PASS passed, $FAIL failed"; [ "$FAIL" = 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"; [ "$FAIL" = 0 ]
