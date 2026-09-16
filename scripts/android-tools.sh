#!/usr/bin/env bash

# Shared Android SDK discovery for both Termux/ARM and conventional desktop CI.
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"

# Termux SDK bootstrap installs under $PREFIX/tmp so it remains usable even when
# callers do not export Android-specific variables in every shell.
if [ ! -d "$SDK_ROOT" ] && [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/tmp/android-sdk" ]; then
    SDK_ROOT="$PREFIX/tmp/android-sdk"
fi

if [ ! -d "$SDK_ROOT" ]; then
    echo "Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
    return 1 2>/dev/null || exit 1
fi

if [ -z "${ANDROID_JAR:-}" ]; then
    if [ -f "$SDK_ROOT/platforms/android-35/android.jar" ]; then
        ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
    else
        ANDROID_JAR="$(find "$SDK_ROOT/platforms" -mindepth 2 -maxdepth 2 \
            -name android.jar -type f 2>/dev/null | sort -V | tail -n 1)"
    fi
fi

if [ ! -f "$ANDROID_JAR" ]; then
    echo "No Android platform android.jar found under $SDK_ROOT/platforms." >&2
    return 1 2>/dev/null || exit 1
fi

resolve_android_tool() {
    local name="$1"
    local candidate

    if [ -n "${PREFIX:-}" ]; then
        candidate="$PREFIX/bin/$name"
        if [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi

    candidate="$(command -v "$name" 2>/dev/null || true)"
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
    fi

    if [ "$name" = "d8" ]; then
        candidate="$SDK_ROOT/cmdline-tools/latest/bin/d8"
        if [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi

    # The SDK ships Windows tools with an extension (aapt2.exe, d8.bat), so a
    # bare -name match silently finds nothing on that platform.
    candidate="$(find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f \
        \( -name "$name" -o -name "$name.exe" -o -name "$name.bat" \) \
        2>/dev/null | sort -V | tail -n 1)"
    # Existence is enough here. A .bat wrapper in the Windows SDK carries no
    # executable bit, so -x would reject a perfectly usable tool.
    if [ -n "$candidate" ] && [ -f "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
    fi

    echo "Required Android tool not found: $name" >&2
    return 1
}

AAPT2="${AAPT2:-$(resolve_android_tool aapt2)}"
D8="${D8:-$(resolve_android_tool d8)}"
ZIPALIGN="${ZIPALIGN:-$(resolve_android_tool zipalign)}"
APKSIGNER="${APKSIGNER:-$(resolve_android_tool apksigner)}"

# javac/java are native Windows binaries under Git Bash and MSYS2, and they
# expect ';' between classpath entries. Hardcoding ':' silently drops every
# entry after the first on those platforms, which surfaces as a flood of
# "package android.app does not exist" errors.
CP_SEP=":"
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*|Windows_NT) CP_SEP=";" ;;
esac

export SDK_ROOT ANDROID_JAR AAPT2 D8 ZIPALIGN APKSIGNER CP_SEP
