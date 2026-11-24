# notebook-ai launcher body (POSIX sh). SINGLE SOURCE for the installed command and run-notebook-ai.sh.
# The including script sets, before this text:
#   NBAI_START   path of the application start script (JavaFX module configuration lives there)
#   NBAI_MODELS  default models directory
#   NBAI_HINT    what to tell the user when the application files are missing
nbai_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | sed -n 1p; }
nbai_fail() { printf 'notebook-ai: %s\n' "$1" >&2; exit 1; }
if [ ! -x "$NBAI_START" ]; then
    nbai_fail "the application files are missing or damaged ($NBAI_START); $NBAI_HINT"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    NBAI_JAVA=$JAVA_HOME/bin/java
    [ -x "$NBAI_JAVA" ] || nbai_fail "JAVA_HOME is set to '$JAVA_HOME' but '$NBAI_JAVA' is not executable"
else
    NBAI_JAVA=$(command -v java 2>/dev/null) || NBAI_JAVA=
    NBAI_M=0
    [ -n "$NBAI_JAVA" ] && NBAI_M=$(nbai_major "$NBAI_JAVA")
    case "$NBAI_M" in ''|*[!0-9]*) NBAI_M=0 ;; esac
    NBAI_FB=${NOTEBOOK_AI_JDK_FALLBACK:-/usr/lib/jvm/java-21-openjdk-amd64}
    if [ "$NBAI_M" -lt 21 ] && [ -x "$NBAI_FB/bin/java" ]; then
        JAVA_HOME=$NBAI_FB
        export JAVA_HOME
        NBAI_JAVA=$JAVA_HOME/bin/java
    fi
    [ -n "$NBAI_JAVA" ] || nbai_fail "Java 21 or newer was not found. Install a Java 21+ runtime or set JAVA_HOME."
fi
NBAI_M=$(nbai_major "$NBAI_JAVA")
case "$NBAI_M" in ''|*[!0-9]*) nbai_fail "could not determine the Java version of '$NBAI_JAVA'" ;; esac
[ "$NBAI_M" -ge 21 ] || nbai_fail "Java $NBAI_M found at '$NBAI_JAVA'; Java 21 or newer is required (set JAVA_HOME to a newer JDK/JRE)."
if [ -z "${NOTEBOOK_AI_MODELS:-}" ]; then
    NOTEBOOK_AI_MODELS=$NBAI_MODELS
    export NOTEBOOK_AI_MODELS
fi
[ -f "$NOTEBOOK_AI_MODELS/det.onnx" ] || nbai_fail "OCR model files were not found in '$NOTEBOOK_AI_MODELS'; $NBAI_HINT"
# Newer JDKs warn on every start about things this application legitimately does: JDK 23+ about sun.misc.Unsafe (JavaFX's Marlin renderer) and JDK 24+ about
# restricted native access (JavaFX and ONNX Runtime load their native libraries). The flags below state that this is intended and silence the warnings. They are
# added ONLY for the JDKs that know them (a JDK 21 rejects --sun-misc-unsafe-memory-access), and any JAVA_OPTS you set yourself are kept.
NBAI_EXTRA=
[ "$NBAI_M" -ge 23 ] && NBAI_EXTRA="--sun-misc-unsafe-memory-access=allow"
[ "$NBAI_M" -ge 24 ] && NBAI_EXTRA="$NBAI_EXTRA --enable-native-access=javafx.graphics,ALL-UNNAMED"
if [ -n "$NBAI_EXTRA" ]; then
    JAVA_OPTS="${JAVA_OPTS:-} $NBAI_EXTRA"
    export JAVA_OPTS
fi
exec "$NBAI_START" "$@"
