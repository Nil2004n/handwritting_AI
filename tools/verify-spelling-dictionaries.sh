#!/bin/sh
# Verifies the bundled spelling word lists: (1) every resource matches SHA256SUMS; (2) optionally, byte-for-byte against the Debian
# packages they were taken from. Usage: sh tools/verify-spelling-dictionaries.sh [DIR containing the six *_2020.12.07-4_all.deb files]
# Read-only: extracts into a temporary directory that is removed on exit. No network, no sudo.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
R="$ROOT/spelling/src/main/resources/notebookai/spelling/dict"
FAIL=0
if ( cd "$R" && sha256sum -c SHA256SUMS >/dev/null 2>&1 ); then echo "PASS  resources match SHA256SUMS"; else echo "FAIL  resources differ from SHA256SUMS"; FAIL=1; fi
for f in en-US en-GB en-CA; do
    for k in "" .common; do
        [ -s "$R/$f$k.txt" ] || { echo "FAIL  missing $f$k.txt"; FAIL=1; }
    done
done
if [ -n "${1:-}" ]; then
    T=$(mktemp -d "${TMPDIR:-/tmp}/nbai-dict-XXXXXX"); trap 'rm -rf "$T"' EXIT
    while read -r pkg list res; do
        deb="$1/${pkg}_2020.12.07-4_all.deb"
        if [ ! -f "$deb" ]; then echo "FAIL  $deb not found"; FAIL=1; continue; fi
        dpkg-deb -x "$deb" "$T/$pkg" || { echo "FAIL  cannot extract $deb"; FAIL=1; continue; }
        if cmp -s "$T/$pkg/usr/share/dict/$list" "$R/$res.txt"; then echo "PASS  $res.txt is byte-identical to $pkg: $list"; else echo "FAIL  $res.txt differs from $pkg: $list"; FAIL=1; fi
        if cmp -s "$T/$pkg/usr/share/doc/$pkg/copyright" "$R/SCOWL-COPYRIGHT.txt"; then echo "PASS  $pkg copyright is byte-identical to SCOWL-COPYRIGHT.txt"; else echo "FAIL  $pkg copyright differs"; FAIL=1; fi
    done <<LIST
wamerican american-english en-US
wamerican-small american-english-small en-US.common
wbritish british-english en-GB
wbritish-small british-english-small en-GB.common
wcanadian canadian-english en-CA
wcanadian-small canadian-english-small en-CA.common
LIST
fi
[ "$FAIL" = 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $FAIL
