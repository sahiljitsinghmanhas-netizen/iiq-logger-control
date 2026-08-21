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

# The page greys the controls out and the API refuses the call, in two
# different languages. They have to agree on what a pattern means, so both are
# run against the same cases rather than read side by side.
echo "Checking untouchable-logger matching..."
"$JAVAC" -nowarn -cp "$CLASSES_DIR:$IIQ_LIB/*" -d "$OUT_DIR" "$HERE/tools/GlobTest.java" \
    || { echo "ERROR: could not compile tools/GlobTest.java." >&2; exit 1; }
"${JAVA_HOME:-}/bin/java" -cp "$OUT_DIR:$CLASSES_DIR:$IIQ_LIB/*" GlobTest \
    || { echo "ERROR: untouchable-logger matching failed on the Java side." >&2; exit 1; }

# Execute the page script against a stub DOM before packaging. A parse-only
# check is not enough - 2.2.0-2.4.0 shipped a script that never parsed.
JJS="${JAVA_HOME:-}/bin/jjs"
[[ -x "$JJS" ]] || JJS="$(command -v jjs || true)"
if [[ -x "$JJS" ]]; then
    echo "Running render check..."
    "$JJS" "$HERE/tools/render-check.js" -- "$HERE/ui/js/turnOnLoggers.js" "$HERE/tools/state-fixture.json" "$HERE/tools/state-fixture-logs.json"         || { echo "ERROR: render check failed - the page would not load. Build aborted." >&2; exit 1; }
    "$JJS" "$HERE/tools/glob-check.js" -- "$HERE/ui/js/turnOnLoggers.js" \
        || { echo "ERROR: untouchable-logger matching failed on the page side." >&2; exit 1; }
    "$JJS" "$HERE/tools/nav-check.js" -- "$HERE/ui/js/snippets/header.js" \
        || { echo "ERROR: header-icon snippet failed its checks." >&2; exit 1; }
else
    echo "WARNING: jjs not found, skipping render check." >&2
fi

# The help page ships inside the zip and reads its images from ui/img, but the
# screenshot tool writes to docs/screenshots. Keeping those in step by hand
# meant they were not. Copy on every build instead.
echo "Syncing help-page images..."
mkdir -p "$HERE/ui/img"
cp -f "$HERE"/docs/screenshots/*.png "$HERE/ui/img/"

echo "Packaging $JAR_NAME..."
(cd "$CLASSES_DIR" && "$JAR_EXE" cf "$LIB_DIR/$JAR_NAME" io)

# jar, not zip -r: keeps forward-slash entry paths, which is what IIQ's
# PluginsCache looks up.
echo "Packaging $ZIP_NAME..."
(cd "$HERE" && "$JAR_EXE" cfM "$ZIP_NAME" manifest.xml ui lib import)

echo
echo "Done: $HERE/$ZIP_NAME"
echo "Install: gear icon -> Plugins -> New -> upload the zip"
