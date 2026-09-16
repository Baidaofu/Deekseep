#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

# Regression coverage for the open-source Local API.
#
# Only the classes that carry real logic are exercised, and they run on the
# desktop JVM: model routing, key validation, bearer parsing, and transport
# cycling. Anything needing a live Context or the Android JSON implementation is
# intentionally out of scope here.

source ../scripts/android-tools.sh
OUT="build/local-api-test"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

SOURCES=(src/com/dsmod/probe/localapi/*.java
         ../module-universal/src/com/dsmod/probe/HostCompat.java
         tests/com/dsmod/probe/localapi/LocalApiProtocolRegressionTest.java)

# The universal build ships its own HostCompat copy; prefer the module source.
SOURCES[1]="src/com/dsmod/probe/HostCompat.java"

if ! javac -source 8 -target 8 -encoding UTF-8 \
        -cp "$ANDROID_JAR" -d "$OUT/classes" "${SOURCES[@]}" 2> "$OUT/javac.err"; then
    cat "$OUT/javac.err"
    exit 1
fi

java -cp "$OUT/classes${CP_SEP}$ANDROID_JAR" \
    com.dsmod.probe.localapi.LocalApiProtocolRegressionTest
