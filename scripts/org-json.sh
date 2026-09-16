#!/usr/bin/env bash

# Compile-time dependency: org.json.
#
# Every Android device ships a real org.json implementation, but the SDK's
# android.jar is a stub archive that no longer contains those classes, so javac
# cannot resolve org.json.* from it. We therefore resolve the reference
# implementation for compilation only and never dex it - at runtime the
# platform's own copy is used.
#
# Usage: prepare_org_json <output-dir>   (prints the jar path on stdout)

ORG_JSON_VERSION="20240303"
ORG_JSON_SHA256="3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"

prepare_org_json() {
    local output_root="$1"
    local version="$ORG_JSON_VERSION"
    local sha="$ORG_JSON_SHA256"
    local cache_root="${DEEKSEEP_DEPS_DIR:-${GRADLE_USER_HOME:-$HOME/.gradle}/deekseep-deps}"
    local cached="$cache_root/json-$version.jar"
    local result="$output_root/json-$version.jar"

    if [[ -z "$output_root" || "$output_root" == "/" ]]; then
        echo "Invalid org.json staging directory" >&2
        return 1
    fi

    mkdir -p "$output_root"
    output_root="$(cd "$output_root" && pwd)"
    result="$output_root/json-$version.jar"

    # Reuse whatever the regression suites already downloaded.
    if [[ ! -f "$cached" || ! -f "$result" ]]; then
        mkdir -p "$cache_root"
        if [[ ! -f "$cached" ]] \
                || ! printf '%s  %s\n' "$sha" "$cached" | sha256sum -c - >/dev/null 2>&1; then
            local tmp="$cached.tmp"
            rm -f "$tmp"
            local downloaded=false
            local base
            for base in \
                https://repo.maven.apache.org/maven2 \
                https://repo1.maven.org/maven2 \
                https://maven.aliyun.com/repository/public; do
                if curl -fsSL --connect-timeout 15 --max-time 60 \
                        "$base/org/json/json/$version/json-$version.jar" -o "$tmp" \
                        && printf '%s  %s\n' "$sha" "$tmp" \
                            | sha256sum -c - >/dev/null 2>&1; then
                    mv "$tmp" "$cached"
                    downloaded=true
                    break
                fi
                rm -f "$tmp"
            done
            if [[ "$downloaded" != true ]]; then
                echo "Could not download a verified org.json dependency" >&2
                return 1
            fi
        fi
        cp "$cached" "$result"
    fi

    printf '%s\n' "$result"
}
