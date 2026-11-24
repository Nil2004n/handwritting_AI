#!/bin/sh
# notebook-ai installer for Linux (POSIX sh).
#
# Installs an already-built, validated distribution (it never builds or downloads unless you pass --build,
# and never needs root for the default per-user install).
#
#   sh install.sh                      per-user install (no sudo)
#   sh install.sh --uninstall          remove the owned install
#   sh install.sh --help               all options
#
# Per-user layout (default):
#   application   ${XDG_DATA_HOME:-$HOME/.local/share}/notebook-ai/{dist,models,...}
#   command       $HOME/.local/bin/notebook-ai
# Prefix layout (--prefix P, e.g. /usr/local, copy-only; root allowed only here):
#   application   P/lib/notebook-ai        command   P/bin/notebook-ai
#
# Exit codes: 0 ok, 1 failure (nothing replaced unless stated), 2 usage error.

set -u

SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd -P) || { echo "install.sh: cannot resolve its own directory" >&2; exit 1; }
MARKER=.notebook-ai-install
LAUNCHER_MARK='# notebook-ai-launcher: owned-by=notebook-ai-installer format=1'
LEGACY_MARK='# notebook-ai launcher (installed by make install).'
CMD_NAME=notebook-ai
MIN_JAVA=21

usage() {
    cat <<'EOF'
Usage: sh install.sh [install|uninstall|status] [options]

Installs notebook-ai for the current user from an already built distribution.
No sudo/root is needed for the default install; nothing is downloaded or built unless --build is given.

Options:
  --app-dir DIR          application directory (default: ${XDG_DATA_HOME:-$HOME/.local/share}/notebook-ai)
  --bin-dir DIR          directory that receives the 'notebook-ai' command (default: $HOME/.local/bin)
  --prefix DIR           classic layout instead: DIR/lib/notebook-ai and DIR/bin (e.g. /usr/local,
                         copy only; the only mode that may run as root). Cannot be combined with
                         --app-dir/--bin-dir.
  --destdir DIR          packaging root prepended to --prefix paths (launcher keeps the final path)
  --dist DIR             staged distribution (default: ./build/install/notebook-ai next to this script)
  --models DIR           verified models directory (default: ./models next to this script)
  --build                opt-in: run './gradlew installDist' first (uses the network; refused as root)
  --replace-legacy       allow replacing (install) or removing (--uninstall --prefix P) a command or
                         application directory left by the OLD 'make install' layout. Only the exact old
                         structure (bin/ lib/ models/ and nothing else) is ever touched. A per-user install
                         with this flag also removes the user-owned old directory ~/.local/lib/notebook-ai
                         (system locations such as /usr/local are never touched without sudo and --prefix).
  --uninstall            remove the installation selected by the same options (also: 'uninstall')
  --status               show and verify the selected installation (also: 'status')
  -h, --help             this text

Preparation commands (run as your normal user, they are NOT run by the installer):
  make build models      or:  ./gradlew installDist  and  sh tools/download-models.sh

After installing: if the command directory is not on PATH the installer prints the exact line to add;
it never edits your shell startup files. Project files (.nbai) and exports are never touched.
EOF
}

die() { printf 'install.sh: error: %s\n' "$*" >&2; exit 1; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
info() { printf '%s\n' "$*"; }

# ---------------------------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------------------------

# Single-quote a string for safe inclusion in a generated sh script.
sq() { printf "'"; printf '%s' "$1" | sed "s/'/'\\\\''/g"; printf "'"; }

has_ctl() { # newline / carriage return / NUL-ish characters in a path are refused
    case "$1" in
        *'
'*|*"$(printf '\r')"*) return 0 ;;
    esac
    return 1
}

abs_ok() { case "$1" in /*) return 0 ;; *) return 1 ;; esac; }

no_dotdot() {
    case "$1" in
        */../*|*/..|../*|..) return 1 ;;
        */./*|*/.) return 1 ;;
    esac
    return 0
}

java_major() { # prints the Java feature version of the given java executable, or nothing
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | sed -n 1p
}

find_java() { # prints the java executable to use: JAVA_HOME, then PATH, then the Debian JDK 21 location
    if [ -n "${JAVA_HOME:-}" ]; then
        [ -x "$JAVA_HOME/bin/java" ] && printf '%s' "$JAVA_HOME/bin/java"
        return 0
    fi
    j=$(command -v java 2>/dev/null) || j=
    if [ -n "$j" ]; then
        m=$(java_major "$j")
        case "$m" in ''|*[!0-9]*) m=0 ;; esac
        if [ "$m" -ge "$MIN_JAVA" ]; then printf '%s' "$j"; return 0; fi
    fi
    fb=${NOTEBOOK_AI_JDK_FALLBACK:-/usr/lib/jvm/java-21-openjdk-amd64}
    if [ -x "$fb/bin/java" ]; then
        printf '%s' "$fb/bin/java"
        return 0
    fi
    [ -n "$j" ] && printf '%s' "$j"
    return 0
}

host_platform() {
    os=$(uname -s 2>/dev/null)
    arch=$(uname -m 2>/dev/null)
    case "$os" in
        Linux) ;;
        *) die "unsupported operating system '$os'. This installer supports Linux x86-64 only (Windows: use install.cmd)." ;;
    esac
    case "$arch" in
        x86_64|amd64) printf 'linux-x64' ;;
        *) die "unsupported architecture '$arch'. Only x86-64 is supported and verified; ARM and other architectures are not interchangeable." ;;
    esac
}

manifest_of_tree() { # relative sha256 list of regular files under $1, names prefixed with $2/
    ( cd "$1" && find . -type f -exec sha256sum {} + ) | sed "s|  \./|  $2/|" | sort -k2
}

# ---------------------------------------------------------------------------------------------
# argument parsing
# ---------------------------------------------------------------------------------------------
MODE=install
APP_ARG= BIN_ARG= PREFIX_ARG= DESTDIR_ARG= DIST_ARG= MODELS_ARG=
DO_BUILD=0 REPLACE_LEGACY=0

need_val() { [ "$2" -ge 2 ] || { echo "install.sh: option $1 needs a value (see --help)" >&2; exit 2; }; }

while [ $# -gt 0 ]; do
    case "$1" in
        install|uninstall|status) MODE=$1 ;;
        --uninstall) MODE=uninstall ;;
        --status) MODE=status ;;
        --app-dir=*) APP_ARG=${1#--app-dir=} ;;
        --app-dir) need_val "$1" $#; APP_ARG=$2; shift ;;
        --bin-dir=*) BIN_ARG=${1#--bin-dir=} ;;
        --bin-dir) need_val "$1" $#; BIN_ARG=$2; shift ;;
        --prefix=*) PREFIX_ARG=${1#--prefix=} ;;
        --prefix) need_val "$1" $#; PREFIX_ARG=$2; shift ;;
        --destdir=*) DESTDIR_ARG=${1#--destdir=} ;;
        --destdir) need_val "$1" $#; DESTDIR_ARG=$2; shift ;;
        --dist=*) DIST_ARG=${1#--dist=} ;;
        --dist) need_val "$1" $#; DIST_ARG=$2; shift ;;
        --models=*) MODELS_ARG=${1#--models=} ;;
        --models) need_val "$1" $#; MODELS_ARG=$2; shift ;;
        --build) DO_BUILD=1 ;;
        --replace-legacy) REPLACE_LEGACY=1 ;;
        -h|--help|help) usage; exit 0 ;;
        *) echo "install.sh: unknown option or argument: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

if [ -n "$PREFIX_ARG" ] && { [ -n "$APP_ARG" ] || [ -n "$BIN_ARG" ]; }; then
    echo "install.sh: --prefix cannot be combined with --app-dir/--bin-dir" >&2; exit 2
fi
if [ -n "$DESTDIR_ARG" ] && [ -z "$PREFIX_ARG" ]; then
    echo "install.sh: --destdir is only valid together with --prefix" >&2; exit 2
fi

: "${HOME:=}"
UID_NOW=$(id -u 2>/dev/null) || die "cannot determine the current user id"

# ---------------------------------------------------------------------------------------------
# target resolution + safety validation
# ---------------------------------------------------------------------------------------------
if [ -n "$PREFIX_ARG" ]; then
    LAYOUT=prefix
    abs_ok "$PREFIX_ARG" || die "--prefix must be an absolute path: $PREFIX_ARG"
    PREFIX_FINAL=${PREFIX_ARG%/}
    [ -n "$PREFIX_FINAL" ] || die "refusing --prefix '/'"
    APP_FINAL=$PREFIX_FINAL/lib/notebook-ai
    BIN_FINAL=$PREFIX_FINAL/bin
    APP=$DESTDIR_ARG$APP_FINAL
    BIN=$DESTDIR_ARG$BIN_FINAL
else
    LAYOUT=user
    [ -n "$HOME" ] || die "HOME is not set; pass --app-dir and --bin-dir explicitly"
    if [ -n "$APP_ARG" ]; then APP_FINAL=$APP_ARG; else APP_FINAL=${XDG_DATA_HOME:-$HOME/.local/share}/notebook-ai; fi
    if [ -n "$BIN_ARG" ]; then BIN_FINAL=$BIN_ARG; else BIN_FINAL=$HOME/.local/bin; fi
    APP_FINAL=${APP_FINAL%/}
    BIN_FINAL=${BIN_FINAL%/}
    APP=$APP_FINAL
    BIN=$BIN_FINAL
fi

for p in "$APP" "$BIN" "$DIST_ARG" "$MODELS_ARG"; do
    [ -z "$p" ] && continue
    has_ctl "$p" && die "refusing a path containing a line break: it cannot be handled safely"
done
abs_ok "$APP" || die "the application directory must be an absolute path: $APP"
abs_ok "$BIN" || die "the command directory must be an absolute path: $BIN"
no_dotdot "$APP" || die "the application directory must not contain '.' or '..' segments: $APP"
no_dotdot "$BIN" || die "the command directory must not contain '.' or '..' segments: $BIN"
[ -n "$APP" ] && [ -n "$BIN" ] || die "empty target path"

REAL_HOME=
[ -n "$HOME" ] && [ -d "$HOME" ] && REAL_HOME=$(cd "$HOME" && pwd -P)

unsafe_target() { # refuses system directories, the home directory and its ancestors, and the repository
    t=$1
    case "$t" in
        /|/bin|/boot|/dev|/etc|/home|/lib|/lib32|/lib64|/media|/mnt|/opt|/proc|/root|/run|/sbin|/srv|/sys|/tmp|/usr|/usr/bin|/usr/lib|/usr/local|/usr/local/bin|/usr/local/lib|/usr/share|/var|/var/tmp)
            return 0 ;;
    esac
    if [ -n "$REAL_HOME" ]; then
        case "$REAL_HOME/" in "$t"/*) return 0 ;; esac    # the target is HOME or one of its ancestors
        case "$t" in
            "$HOME"|"$HOME/.local"|"$HOME/.local/share"|"$HOME/.local/bin"|"$HOME/.local/lib"|"$HOME/.config"|"$HOME/.cache"|\
            "$HOME/Desktop"|"$HOME/Downloads"|"$HOME/Documents"|"$HOME/Pictures") return 0 ;;
        esac
    fi
    case "$t/" in "$SELF_DIR"/*) return 0 ;; esac      # inside (or equal to) the source repository
    case "$SELF_DIR/" in "$t"/*) return 0 ;; esac      # an ancestor of the repository
    return 1
}

unsafe_target "$APP" && die "refusing to use '$APP' as the application directory (system, home, or repository location)"
if [ "$LAYOUT" = user ]; then
    # the command directory is shared with other programs, so it is never deleted: only the command file is touched
    case "$BIN/" in "$APP"/*) die "the command directory must not be inside the application directory" ;; esac
    case "$APP/" in "$BIN"/*) die "the application directory must not be inside the command directory" ;; esac
    case "$BIN" in /|/bin|/usr|/usr/bin|/etc|/sbin|/lib|/var) die "refusing '$BIN' as the command directory" ;; esac
else
    case "$APP" in */lib/notebook-ai) ;; *) die "refusing unsafe application directory '$APP' (must end in /lib/notebook-ai)" ;; esac
fi
CMD=$BIN/$CMD_NAME
CMD_FINAL=$BIN_FINAL/$CMD_NAME
[ ! -d "$CMD" ] || die "'$CMD' is a directory; cannot install the command there"
[ ! -L "$APP" ] || die "'$APP' is a symbolic link; refusing (choose the real directory)"

if [ "$UID_NOW" = 0 ] && [ "$LAYOUT" = user ]; then
    die "refusing a per-user install as root (it would go into root's home). Run it as your normal user, or use --prefix PREFIX for a system-wide copy-only install."
fi

owned_app() { [ -f "$1/$MARKER" ]; }
legacy_app() { # the layout written by the old 'make install': bin/, lib/, models/ and nothing else
    [ -x "$1/bin/notebook-ai" ] && [ -d "$1/lib" ] && [ -d "$1/models" ] && [ ! -e "$1/$MARKER" ] || return 1
    others=$(cd "$1" && ls -A | grep -v -x -e bin -e lib -e models | wc -l)
    [ "$others" -eq 0 ]
}
owned_launcher() { [ -f "$1" ] && sed -n 2p "$1" 2>/dev/null | grep -qxF "$LAUNCHER_MARK"; }
legacy_launcher() { [ -f "$1" ] && grep -qxF "$LEGACY_MARK" "$1" 2>/dev/null; }
launcher_app() { # the application directory baked into an owned launcher
    sed -n "s/^NBAI_APP=//p" "$1" | sed -n 1p | sed "s/^'//; s/'\$//; s/'\\\\''/'/g"
}

# ---------------------------------------------------------------------------------------------
# status
# ---------------------------------------------------------------------------------------------
do_status() {
    info "Application directory : $APP"
    info "Command               : $CMD"
    if owned_app "$APP"; then
        info "Installation          : present (owned by notebook-ai installer)"
        [ -f "$APP/INSTALL.info" ] && sed 's/^/  /' "$APP/INSTALL.info"
        if [ -f "$APP/MANIFEST.sha256" ]; then
            if ( cd "$APP" && sha256sum -c --quiet MANIFEST.sha256 >/dev/null 2>&1 ); then
                info "Integrity             : OK (every installed file matches the recorded SHA-256)"
            else
                info "Integrity             : FAILED (files changed or missing; reinstall)"
                return 1
            fi
        fi
    elif legacy_app "$APP"; then
        info "Installation          : OLD-layout installation (not managed by this installer)"
    elif [ -e "$APP" ]; then
        info "Installation          : directory exists but is not a notebook-ai installation"
    else
        info "Installation          : not installed"
    fi
    if owned_launcher "$CMD"; then info "Command ownership     : owned launcher -> $(launcher_app "$CMD")"
    elif legacy_launcher "$CMD"; then info "Command ownership     : launcher from the old 'make install'"
    elif [ -e "$CMD" ]; then info "Command ownership     : $CMD exists but was not written by notebook-ai"
    else info "Command ownership     : no command file"; fi
    return 0
}

# ---------------------------------------------------------------------------------------------
# uninstall
# ---------------------------------------------------------------------------------------------
do_uninstall() {
    removed=0
    if owned_app "$APP"; then
        case "$APP" in */*) ;; *) die "internal error: unsafe application path" ;; esac
        rm -rf -- "$APP" || die "could not remove '$APP' (is a file in it in use or read-only?)"
        info "Removed application directory: $APP"
        removed=1
    elif legacy_app "$APP"; then
        if [ "$REPLACE_LEGACY" = 1 ] && [ "$LAYOUT" = prefix ]; then
            rm -rf -- "$APP" || die "could not remove the old-layout directory '$APP'"
            info "Removed OLD-layout application directory: $APP"
            removed=1
        else
            info "Note: '$APP' is an OLD-layout installation. It is not touched. To remove exactly that structure deliberately:"
            info "      sh install.sh --uninstall --prefix $(sq "${PREFIX_FINAL:-<its prefix>}") --replace-legacy"
        fi
    elif [ -e "$APP" ]; then
        warn "'$APP' exists but is not a notebook-ai installation; left untouched."
    else
        info "No application directory at $APP"
    fi
    if legacy_launcher "$CMD" && [ "$REPLACE_LEGACY" = 1 ] && [ "$LAYOUT" = prefix ]; then
        rm -f -- "$CMD" || die "could not remove $CMD"
        info "Removed OLD-layout command: $CMD"
        removed=1
    elif owned_launcher "$CMD"; then
        baked=$(launcher_app "$CMD")
        if [ "$baked" = "$APP_FINAL" ]; then
            rm -f -- "$CMD" || die "could not remove $CMD"
            info "Removed command: $CMD"
            removed=1
        else
            info "Command '$CMD' belongs to another notebook-ai installation ($baked); left untouched."
        fi
    elif [ -e "$CMD" ]; then
        info "Command '$CMD' was not written by this installer; left untouched."
    fi
    info "Retained: your projects (.nbai files), exports, evaluation data and any shell configuration. The command directory '$BIN' itself is never removed."
    [ "$removed" = 1 ] || info "Nothing was removed."
    return 0
}

# ---------------------------------------------------------------------------------------------
# install
# ---------------------------------------------------------------------------------------------
TMP_DIR= TMP_CMD= PREV_DIR=
cleanup() {
    if [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ]; then
        if [ -f "$TMP_DIR/$MARKER" ]; then rm -rf -- "$TMP_DIR"; else rmdir -- "$TMP_DIR" 2>/dev/null; fi
    fi
    if [ -n "$TMP_CMD" ] && [ -f "$TMP_CMD" ]; then rm -f -- "$TMP_CMD"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

do_install() {
    PLAT=$(host_platform) || exit 1
    # Packaged mode: this script sits inside an extracted release distribution (distribution.json + app/).
    # It then installs only from those files: no source tree, no Gradle, no network.
    PKG=0
    if [ -f "$SELF_DIR/distribution.json" ] && [ -x "$SELF_DIR/app/bin/notebook-ai" ]; then PKG=1; fi
    if [ "$PKG" = 1 ]; then
        DIST=${DIST_ARG:-$SELF_DIR/app}
        MODELS=${MODELS_ARG:-$SELF_DIR/app/models}
        HINT_DIST="this extracted distribution is incomplete or damaged: extract the archive again (verify it against its .sha256 file first)"
        HINT_MODELS=$HINT_DIST
        REBUILD="Re-extract the archive."
        REMODEL="Re-extract the archive."
    else
        REBUILD="Rebuild: make build"
        REMODEL="Re-run: make models"
        DIST=${DIST_ARG:-$SELF_DIR/build/install/notebook-ai}
        MODELS=${MODELS_ARG:-$SELF_DIR/models}
        HINT_DIST="Build it first as your normal user (do NOT use sudo):
         make build        (or: ./gradlew installDist, or: sh install.sh --build)"
        HINT_MODELS="Prepare the models first (needs network once, as your normal user):
         make models        (or: sh tools/download-models.sh)"
    fi

    # ---- optional build (explicit opt-in; never as root) ----
    if [ "$DO_BUILD" = 1 ] && [ "$PKG" = 1 ]; then
        die "--build is not available in a packaged distribution (it contains no source code)."
    fi
    if [ "$DO_BUILD" = 1 ]; then
        [ "$UID_NOW" != 0 ] || die "--build refuses to run Gradle as root. Build as your normal user, then install."
        [ -x "$SELF_DIR/gradlew" ] || die "gradlew not found next to install.sh"
        info "Building (this runs Gradle and uses the network to fetch dependencies on first use)..."
        gopt=
        [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-21-openjdk-amd64 ] && gopt=-Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64
        if [ -n "$gopt" ]; then ( cd "$SELF_DIR" && ./gradlew "$gopt" installDist ) || die "the build failed"
        else ( cd "$SELF_DIR" && ./gradlew installDist ) || die "the build failed"; fi
    fi

    # ---- prerequisites (nothing is modified before all of these pass) ----
    if [ "$PKG" = 1 ]; then
        [ -f "$SELF_DIR/SHA256SUMS" ] || die "SHA256SUMS is missing from the distribution; $HINT_DIST"
        ( cd "$SELF_DIR" && sha256sum -c --quiet SHA256SUMS >/dev/null 2>&1 ) || die "the extracted distribution failed its SHA256SUMS integrity check; $HINT_DIST"
        pjson=$(sed -n 's/.*"targetPlatform"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SELF_DIR/distribution.json" | sed -n 1p)
        [ "$pjson" = "$PLAT" ] || die "this distribution is for '$pjson', but this machine is '$PLAT'. Use the matching distribution."
    fi
    [ -d "$DIST" ] || die "no staged distribution at '$DIST'. $HINT_DIST"
    [ -x "$DIST/bin/notebook-ai" ] || die "'$DIST' is not a complete distribution (bin/notebook-ai missing). $REBUILD"
    [ -f "$DIST/PLATFORM" ] || die "'$DIST' has no PLATFORM file; it was not built by this version of the build. $REBUILD"
    dplat=$(sed -n 's/^platform=//p' "$DIST/PLATFORM" | sed -n 1p)
    [ "$dplat" = "$PLAT" ] || die "the staged distribution is for '$dplat', but this machine is '$PLAT'. Rebuild on this machine: make build"
    njar=$(ls "$DIST"/lib/notebook-ai-*.jar 2>/dev/null | wc -l)
    [ "$njar" -eq 1 ] || die "'$DIST/lib' must contain exactly one notebook-ai-*.jar (found $njar). $REBUILD"
    [ -f "$DIST/lib/onnxruntime-1.22.0.jar" ] || ls "$DIST"/lib/onnxruntime-*.jar >/dev/null 2>&1 || die "ONNX Runtime jar missing from '$DIST/lib'. $REBUILD"
    ls "$DIST"/lib/javafx-graphics-*-linux.jar >/dev/null 2>&1 || die "no Linux JavaFX jars in '$DIST/lib' (wrong platform build?). $REBUILD"
    for other in win mac mac-aarch64 linux-aarch64; do
        if ls "$DIST"/lib/javafx-*-"$other".jar >/dev/null 2>&1; then die "'$DIST' contains JavaFX jars for another operating system ($other). $REBUILD"; fi
    done

    for f in det.onnx det.yml rec.onnx rec.yml checksums.sha256; do
        [ -f "$MODELS/$f" ] || die "model file '$MODELS/$f' is missing. $HINT_MODELS"
    done
    ( cd "$MODELS" && sha256sum -c --quiet checksums.sha256 >/dev/null 2>&1 ) || die "model checksum verification failed in '$MODELS'. $REMODEL"

    if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
        die "JAVA_HOME is set to '$JAVA_HOME' but '$JAVA_HOME/bin/java' is not executable. Fix or unset JAVA_HOME."
    fi
    JAVA_BIN=$(find_java)
    [ -n "$JAVA_BIN" ] || die "no Java found. Install a Java $MIN_JAVA or newer runtime (e.g. 'sudo apt-get install openjdk-21-jre') or set JAVA_HOME."
    JMAJ=$(java_major "$JAVA_BIN")
    case "$JMAJ" in ''|*[!0-9]*) die "could not determine the version of '$JAVA_BIN'" ;; esac
    [ "$JMAJ" -ge "$MIN_JAVA" ] || die "Java $JMAJ found at '$JAVA_BIN'; Java $MIN_JAVA or newer is required."

    # ---- existing targets: ownership decisions BEFORE modification ----
    APP_EXISTS=0 APP_LEGACY=0
    if [ -e "$APP" ]; then
        APP_EXISTS=1
        if owned_app "$APP"; then :
        elif legacy_app "$APP"; then
            [ "$LAYOUT" = prefix ] && [ "$REPLACE_LEGACY" = 1 ] || die "'$APP' holds an installation from the OLD 'make install' layout. It is not replaced automatically.
         Re-run with --replace-legacy to replace it in place, or install elsewhere (the default per-user location needs no flag)."
            APP_LEGACY=1
        elif [ -d "$APP" ] && [ -z "$(ls -A "$APP")" ]; then :
        else die "'$APP' exists and is not a notebook-ai installation (no ownership marker). Refusing to modify it; choose another --app-dir."
        fi
    fi
    if [ -e "$CMD" ]; then
        if owned_launcher "$CMD"; then :
        elif legacy_launcher "$CMD"; then
            [ "$REPLACE_LEGACY" = 1 ] || die "'$CMD' is the command from the OLD 'make install'. It is not replaced without consent.
         Re-run with --replace-legacy to point it at this installation (the old application files are left in place)."
        else die "'$CMD' exists and was not written by notebook-ai. Refusing to overwrite another program's file; use another --bin-dir."
        fi
    fi

    PARENT=$(dirname "$APP")
    mkdir -p -- "$PARENT" "$BIN" || die "cannot create '$PARENT' or '$BIN'${DESTDIR_ARG:+}"
    [ -w "$PARENT" ] || die "'$PARENT' is not writable. $( [ "$LAYOUT" = prefix ] && echo "Re-run this command with sudo (copy only, nothing is built as root)." || echo "Choose a writable --app-dir.")"
    [ -w "$BIN" ] || die "'$BIN' is not writable. $( [ "$LAYOUT" = prefix ] && echo "Re-run this command with sudo." || echo "Choose a writable --bin-dir.")"

    # ---- stage the candidate next to the target (installer-owned temp name) ----
    TMP_DIR=$PARENT/.notebook-ai.installing.$$
    PREV_DIR=$PARENT/.notebook-ai.previous.$$
    [ ! -e "$TMP_DIR" ] && [ ! -e "$PREV_DIR" ] || die "temporary path collision; retry"
    mkdir -- "$TMP_DIR" || die "cannot create the staging directory '$TMP_DIR'"
    : > "$TMP_DIR/$MARKER" || die "cannot write into '$TMP_DIR'"
    mkdir -- "$TMP_DIR/dist" "$TMP_DIR/models" || die "cannot create staging subdirectories"
    for e in bin lib PLATFORM; do
        cp -R -- "$DIST/$e" "$TMP_DIR/dist/$e" || die "copying the distribution failed (disk full or permission problem); the previous installation is untouched"
    done
    for f in det.onnx det.yml rec.onnx rec.yml checksums.sha256; do
        cp -- "$MODELS/$f" "$TMP_DIR/models/$f" || die "copying model file $f failed; the previous installation is untouched"
    done

    # identity check: every copied file must match the source byte for byte (by SHA-256)
    ( cd "$DIST" && find bin lib PLATFORM -type f -exec sha256sum {} + ) | sed 's|  |  dist/|' | sort -k2 > "$TMP_DIR/.src.manifest" || die "cannot hash the source distribution"
    ( cd "$MODELS" && sha256sum det.onnx det.yml rec.onnx rec.yml checksums.sha256 ) | sed 's|  |  models/|' | sort -k2 >> "$TMP_DIR/.src.manifest"
    sort -k2 -o "$TMP_DIR/.src.manifest" "$TMP_DIR/.src.manifest"
    ( cd "$TMP_DIR/dist" && find bin lib PLATFORM -type f -exec sha256sum {} + ) | sed 's|  |  dist/|' | sort -k2 > "$TMP_DIR/.copy.manifest"
    manifest_of_tree "$TMP_DIR/models" models >> "$TMP_DIR/.copy.manifest"
    sort -k2 -o "$TMP_DIR/.copy.manifest" "$TMP_DIR/.copy.manifest"
    cmp -s "$TMP_DIR/.src.manifest" "$TMP_DIR/.copy.manifest" || die "the copied files do not match the source distribution; nothing was replaced"
    # license documents (present in a release distribution; LICENSE only when running from a source checkout)
    mkdir -- "$TMP_DIR/legal" || die "cannot create the legal directory"
    [ -f "$SELF_DIR/LICENSE" ] && { cp -- "$SELF_DIR/LICENSE" "$TMP_DIR/legal/LICENSE" || die "cannot copy LICENSE"; }
    [ -f "$SELF_DIR/THIRD-PARTY-NOTICES.txt" ] && { cp -- "$SELF_DIR/THIRD-PARTY-NOTICES.txt" "$TMP_DIR/legal/THIRD-PARTY-NOTICES.txt" || die "cannot copy THIRD-PARTY-NOTICES.txt"; }
    [ -d "$SELF_DIR/licenses" ] && { cp -R -- "$SELF_DIR/licenses" "$TMP_DIR/legal/licenses" || die "cannot copy licenses"; }
    if [ -n "$(ls -A "$TMP_DIR/legal")" ]; then manifest_of_tree "$TMP_DIR/legal" legal >> "$TMP_DIR/.copy.manifest"; sort -k2 -o "$TMP_DIR/.copy.manifest" "$TMP_DIR/.copy.manifest"; else rmdir -- "$TMP_DIR/legal"; fi
    mv -- "$TMP_DIR/.copy.manifest" "$TMP_DIR/MANIFEST.sha256" && rm -f -- "$TMP_DIR/.src.manifest"
    [ -x "$TMP_DIR/dist/bin/notebook-ai" ] || chmod 755 "$TMP_DIR/dist/bin/notebook-ai" || die "cannot make the start script executable"

    jarsum=$(sha256sum "$TMP_DIR"/dist/lib/notebook-ai-*.jar | cut -d' ' -f1)
    {
        printf 'app=notebook-ai\n'
        sed -n 's/^version=/version=/p;s/^platform=/platform=/p' "$DIST/PLATFORM"
        printf 'main-jar-sha256=%s\n' "$jarsum"
        printf 'layout=%s\n' "$LAYOUT"
        printf 'installed-utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'java-at-install=%s (%s)\n' "$JMAJ" "$JAVA_BIN"
        printf 'command=%s\n' "$CMD_FINAL"
    } > "$TMP_DIR/INSTALL.info"

    # launcher candidate (written beside the final command so the final rename is atomic)
    TMP_CMD=$BIN/.notebook-ai.new.$$
    FRAG=$SELF_DIR/scripts/launcher-body.sh
    [ -f "$FRAG" ] || FRAG=$SELF_DIR/packaging/launcher-body.sh
    [ -f "$FRAG" ] || die "the shared launcher template (scripts/launcher-body.sh) is missing next to install.sh; $HINT_DIST"
    {
        printf '#!/bin/sh\n'
        printf '%s\n' "$LAUNCHER_MARK"
        printf '# Generated by install.sh; reinstall to regenerate. Runs the installed copy only.\n'
        printf 'NBAI_APP=%s\n' "$(sq "$APP_FINAL")"
        printf 'NBAI_START="$NBAI_APP/dist/bin/notebook-ai"\n'
        printf 'NBAI_MODELS="$NBAI_APP/models"\n'
        printf 'NBAI_HINT="reinstall it (sh install.sh)"\n'
        cat "$FRAG" || exit 1
    } > "$TMP_CMD" || die "cannot write the command file in '$BIN'"
    chmod 755 "$TMP_CMD" || die "cannot make the command executable"

    # ---- swap (previous installation kept until the new one is fully in place) ----
    if [ "$APP_EXISTS" = 1 ]; then
        mv -- "$APP" "$PREV_DIR" || die "cannot move the existing installation aside ('$APP'). Is it in use or read-only? The existing installation is unchanged."
    fi
    if ! mv -- "$TMP_DIR" "$APP"; then
        [ "$APP_EXISTS" = 1 ] && mv -- "$PREV_DIR" "$APP"
        die "could not put the new installation in place; the previous installation was restored"
    fi
    TMP_DIR=
    if ! mv -f -- "$TMP_CMD" "$CMD"; then
        rm -rf -- "$APP"
        [ "$APP_EXISTS" = 1 ] && mv -- "$PREV_DIR" "$APP"
        die "could not write the command '$CMD'; the previous installation was restored"
    fi
    TMP_CMD=
    if [ "$APP_EXISTS" = 1 ] && [ -d "$PREV_DIR" ]; then
        if [ -f "$PREV_DIR/$MARKER" ] || [ "$APP_LEGACY" = 1 ]; then rm -rf -- "$PREV_DIR"
        else warn "left '$PREV_DIR' in place (not recognised as an owned installation)"; fi
    fi

    # ---- report ----
    info ""
    info "notebook-ai installed."
    info "  application : $APP_FINAL"
    info "  command     : $CMD_FINAL"
    info "  platform    : $PLAT   java at install time: $JMAJ"
    [ -n "$DESTDIR_ARG" ] && info "  (staged under DESTDIR $DESTDIR_ARG)"
    [ "$APP_EXISTS" = 1 ] && info "  upgraded an existing installation (the old files were replaced as a whole)"
    [ "$APP_LEGACY" = 1 ] && info "  replaced an OLD-layout application directory in place"
    info "  verify      : sh install.sh --status"

    if [ -z "$DESTDIR_ARG" ]; then report_path_and_shadow; report_legacy; fi
    info ""
    info "Models: bundled in the application directory (no network is used at run time)."
    info "Uninstall: sh install.sh --uninstall${PREFIX_ARG:+ --prefix \"$PREFIX_ARG\"}${APP_ARG:+ --app-dir \"$APP_ARG\"}${BIN_ARG:+ --bin-dir \"$BIN_ARG\"}   (your projects and exports are never touched)"
}

on_path() { case ":${PATH:-}:" in *":$1:"*) return 0 ;; esac; return 1; }
system_bin_dir() { case "$1" in /usr/local/bin|/usr/bin|/bin) return 0 ;; esac; return 1; } # on the PATH of ordinary login shells
# When this installer runs as root through sudo, PATH is ROOT's (often without the user's directories), so it says nothing about the invoking user's shell.
# Prints the invoking user's home directory in that case, else fails.
sudo_user_home() {
    [ "$(id -u 2>/dev/null)" = 0 ] && [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != root ] || return 1
    case "$SUDO_USER" in *[!A-Za-z0-9._-]*) return 1 ;; esac
    uh=$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6)
    [ -n "$uh" ] && [ -d "$uh" ] && printf '%s' "$uh"
}

report_path_and_shadow() {
    resolved=$(command -v notebook-ai 2>/dev/null) || resolved=
    if SUDO_HOME=$(sudo_user_home); then
        # Running as root via sudo: judge the invoking user's setup, not root's PATH.
        ucmd=$SUDO_HOME/.local/bin/notebook-ai
        if [ -e "$ucmd" ] && ! [ "$ucmd" -ef "$CMD_FINAL" ] 2>/dev/null; then
            info ""
            if owned_launcher "$ucmd"; then
                info "NOTE: $SUDO_USER also has a per-user installation ($(launcher_app "$ucmd")) with its own command $ucmd."
            else
                info "NOTE: $SUDO_USER has another command at $ucmd."
            fi
            info "      It was NOT changed by this system-wide install, and when '$SUDO_HOME/.local/bin' comes first on PATH it is the one that runs."
            info "      To use only the system-wide installation:  sh install.sh --uninstall      (run as $SUDO_USER, without sudo)"
            info "      To refresh the per-user one instead:        make install               (run as $SUDO_USER, without sudo)"
        fi
        if ! system_bin_dir "$BIN_FINAL" && ! on_path "$BIN_FINAL"; then
            info ""
            info "NOTE: '$BIN_FINAL' may not be on $SUDO_USER's PATH. Run it with: $(sq "$CMD_FINAL")"
        fi
        return 0
    fi
    if ! on_path "$BIN_FINAL"; then
        info ""
        info "NOTE: '$BIN_FINAL' is not on your PATH, so typing 'notebook-ai' will not find this installation yet."
        info "      Run it now with:  $(sq "$CMD_FINAL")"
        shname=$(basename "${SHELL:-sh}")
        case "$shname" in
            fish) info "      To add it permanently (fish):  fish_add_path $(sq "$BIN_FINAL")" ;;
            zsh)  info "      To add it permanently, append to ~/.zshrc:   export PATH=\"$BIN_FINAL:\$PATH\"" ;;
            bash) info "      To add it permanently, append to ~/.bashrc:  export PATH=\"$BIN_FINAL:\$PATH\"" ;;
            *)    info "      To add it permanently, append to ~/.profile: export PATH=\"$BIN_FINAL:\$PATH\"" ;;
        esac
        info "      (This installer never edits shell startup files. Then open a new terminal.)"
    fi
    if [ -n "$resolved" ] && ! [ "$resolved" -ef "$CMD_FINAL" ] 2>/dev/null; then
        warn "'notebook-ai' on your PATH currently resolves to $resolved, NOT the command just installed."
        warn "  Run this installation explicitly with: $(sq "$CMD_FINAL")"
        warn "  or put '$BIN_FINAL' earlier on PATH. The other command was not modified."
    fi
}

report_legacy() {
    for lp in "${HOME:-/nonexistent}/.local" /usr/local /usr; do
        old=$lp/lib/notebook-ai
        [ "$old" = "$APP_FINAL" ] && continue
        # Per-user upgrade: with consent (the default of the per-user 'make install'), the user-owned OLD-layout directory is replaced by this
        # installation, i.e. removed, but ONLY if it is exactly the old structure (bin/ lib/ models/ and nothing else) and writable. Its command
        # was the same path as the new command, which has already been replaced. Anything else (extra files, no consent, system locations) is left alone.
        if [ "$lp" = "${HOME:-/nonexistent}/.local" ] && [ "$LAYOUT" = user ] && [ "$REPLACE_LEGACY" = 1 ] && [ -z "$DESTDIR_ARG" ] \
                && [ -d "$old" ] && legacy_app "$old" && [ -w "$lp/lib" ]; then
            if rm -rf -- "$old"; then
                info ""
                info "Updated: the OLD-layout application directory $old was replaced by this installation and removed."
                continue
            fi
            warn "could not remove the old application directory $old; it was left in place"
        fi
        if [ -d "$old" ] && [ ! -f "$old/$MARKER" ] && [ -x "$old/bin/notebook-ai" ]; then
            info ""
            info "Found an OLD-layout installation at $old (not touched, not migrated)."
            info "  Its command: $lp/bin/notebook-ai"
            info "  To remove it deliberately:  sh install.sh --uninstall --prefix $(sq "$lp") --replace-legacy   (add sudo only for /usr/local or /usr)"
        fi
    done
}

case $MODE in
    install) do_install ;;
    uninstall) do_uninstall ;;
    status) do_status ;;
esac
exit $?
