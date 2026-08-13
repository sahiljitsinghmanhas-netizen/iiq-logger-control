#!/usr/bin/env bash
#
# Builds TurnOnLoggers.zip on Linux / macOS / Git Bash.
# Produces a byte-for-byte equivalent package to build.bat.
#
#   IIQ_LIB=/opt/iiq/WEB-INF/lib ./build.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_NAME="TurnOnLoggers"
JAR_NAME="turn-on-loggers.jar"
ZIP_NAME="${PLUGIN_NAME}.zip"

IIQ_LIB="${IIQ_LIB:-/opt/identityiq/WEB-INF/lib}"
JAVA_HOME="${JAVA_HOME:-}"

if [[ -n "$JAVA_HOME" ]]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAR_EXE="$JAVA_HOME/bin/jar"
else
    JAVAC="$(command -v javac || true)"
    JAR_EXE="$(command -v jar || true)"
fi

[[ -f "$IIQ_LIB/identityiq.jar" ]] || {
    echo "ERROR: identityiq.jar not found at $IIQ_LIB" >&2
    echo "Set IIQ_LIB to your IdentityIQ WEB-INF/lib folder." >&2
    exit 1
}
[[ -x "$JAVAC" ]] || { echo "ERROR: javac not found. Set JAVA_HOME to a JDK 11." >&2; exit 1; }

OUT_DIR="$HERE/build"
CLASSES_DIR="$OUT_DIR/classes"
LIB_DIR="$HERE/lib"

rm -rf "$OUT_DIR" "$LIB_DIR" "$HERE/$ZIP_NAME"
mkdir -p "$CLASSES_DIR" "$LIB_DIR"

echo "Compiling..."
find "$HERE/src" -name '*.java' > "$OUT_DIR/sources.txt"
"$JAVAC" -source 11 -target 11 -encoding UTF-8 -nowarn \
    -cp "$IIQ_LIB/*" \
    -d "$CLASSES_DIR" \
    "@$OUT_DIR/sources.txt"

echo "Packaging $JAR_NAME..."
(cd "$CLASSES_DIR" && "$JAR_EXE" cf "$LIB_DIR/$JAR_NAME" com)

# jar, not zip -r: keeps forward-slash entry paths, which is what IIQ's
# PluginsCache looks up.
echo "Packaging $ZIP_NAME..."
(cd "$HERE" && "$JAR_EXE" cfM "$ZIP_NAME" manifest.xml ui lib import)

echo
echo "Done: $HERE/$ZIP_NAME"
echo "Install: gear icon -> Plugins -> New -> upload the zip"
