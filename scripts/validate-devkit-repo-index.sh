#!/usr/bin/env bash
# Validate that a flat repository publishes termux-devkit correctly.
#
# Usage:
#   ./scripts/validate-devkit-repo-index.sh [REPO_DIR]
#
# Environment:
#   TERMUX_DEBKIT_VERSION   expected version, default 0.1.0
#
# Exit codes:
#   0  every architecture indexes exactly one termux-devkit that matches its own bytes
#   1  an index, a package file, or the payload inside it is wrong or missing
#
# Deliberately separate from scripts/validate-repo-index.sh, which is pinned to termux-api and is
# part of the release gate. This package changes on its own cadence, and a shared validator would
# tie the two release trains together.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${1:-$SCRIPT_DIR/../repo}"
EXPECTED_VERSION="${TERMUX_DEBKIT_VERSION:-0.1.0}"
PREFIX="/data/data/com.termux/files/usr"
COMMANDS=(termux-reload termux-backup-prefix termux-restore-prefix)
ARCHITECTURES=(aarch64 arm x86_64 i686)

if [[ ! -d "$REPO_DIR" ]]; then
    echo "ERROR: Repository directory does not exist: $REPO_DIR" >&2
    exit 1
fi
REPO_DIR="$(cd "$REPO_DIR" && pwd)"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/termux-devkit-repo.XXXXXX")
trap 'rm -rf "$temp_dir"' EXIT

for arch in "${ARCHITECTURES[@]}"; do
    packages="$REPO_DIR/$arch/Packages"
    if [[ ! -f "$packages" ]]; then
        echo "ERROR: Missing index: $packages" >&2
        exit 1
    fi
    if [[ ! -f "$packages.gz" ]]; then
        echo "ERROR: Missing compressed index: $packages.gz" >&2
        exit 1
    fi
    if ! gzip -cd "$packages.gz" | cmp -s - "$packages"; then
        echo "ERROR: $packages.gz does not match $packages" >&2
        exit 1
    fi

    stanza='(^|\n)Package: termux-devkit(\n|$)'

    package_count=$(awk -v re="$stanza" 'BEGIN { RS = ""; count = 0 } $0 ~ re { count++ } END { print count }' "$packages")
    if [[ "$package_count" -ne 1 ]]; then
        echo "ERROR: Expected exactly one termux-devkit stanza in $packages, found $package_count" >&2
        exit 1
    fi

    field() {
        local name="$1"
        awk -v re="$stanza" -v field="$name" '
            BEGIN { RS = ""; FS = "\n" }
            $0 ~ re {
                for (i = 1; i <= NF; i++) {
                    if ($i ~ "^" field ": ") { sub("^" field ": ", "", $i); print $i }
                }
            }
        ' "$packages"
    }

    version=$(field Version)
    if [[ "$version" != "$EXPECTED_VERSION" ]]; then
        echo "ERROR: termux-devkit in $packages has Version: $version; expected $EXPECTED_VERSION" >&2
        exit 1
    fi

    filename=$(field Filename)
    if [[ -z "$filename" || "$filename" = /* || "$filename" == *".."* || "$filename" != "$arch/"* ]]; then
        echo "ERROR: Invalid termux-devkit Filename in $packages: $filename" >&2
        exit 1
    fi

    depends=$(field Depends)
    # termux-reload talks to the am socket through the termux-am client. A package that installs
    # but cannot deliver anything is worse than one that apt refuses to install, so the dependency
    # is asserted here rather than discovered on the handset.
    if ! printf '%s' "$depends" | tr ',' '\n' | sed 's/^ *//' | grep -qx "termux-am"; then
        echo "ERROR: termux-devkit in $packages does not depend on termux-am (Depends: $depends)" >&2
        exit 1
    fi

    deb="$REPO_DIR/$filename"
    if [[ ! -f "$deb" ]]; then
        echo "ERROR: $packages indexes $filename but the file is missing from $REPO_DIR" >&2
        exit 1
    fi

    index_size=$(field Size)
    index_sha256=$(field SHA256)
    actual_size=$(wc -c < "$deb" | tr -d '[:space:]')
    actual_sha256=$(sha256sum "$deb" | cut -d' ' -f1)
    if [[ -n "$index_size" && "$index_size" != "$actual_size" ]]; then
        echo "ERROR: $packages declares Size: $index_size but $filename is $actual_size bytes" >&2
        exit 1
    fi
    if [[ -n "$index_sha256" && "$index_sha256" != "$actual_sha256" ]]; then
        echo "ERROR: $packages declares SHA256: $index_sha256 but $filename is $actual_sha256" >&2
        exit 1
    fi

    deb_arch=$(dpkg-deb -f "$deb" Architecture)
    if [[ "$deb_arch" != "$arch" ]]; then
        echo "ERROR: $filename has Architecture: $deb_arch; expected $arch" >&2
        exit 1
    fi

    extract_dir="$temp_dir/$arch"
    mkdir -p "$extract_dir"
    dpkg-deb -x "$deb" "$extract_dir"
    for command_name in "${COMMANDS[@]}"; do
        installed="$extract_dir$PREFIX/bin/$command_name"
        if [[ ! -f "$installed" ]]; then
            echo "ERROR: $filename does not ship bin/$command_name" >&2
            exit 1
        fi
        # apt preserves the mode from the payload, so a non-executable command ships broken and
        # nothing later would notice until a human ran it under pressure.
        if [[ ! -x "$installed" ]]; then
            echo "ERROR: bin/$command_name in $filename is not executable" >&2
            exit 1
        fi
        if ! head -1 "$installed" | grep -q "^#!$PREFIX"; then
            echo "ERROR: bin/$command_name in $filename lacks the $PREFIX shebang" >&2
            exit 1
        fi
    done

    echo "Validated termux-devkit $EXPECTED_VERSION for $arch."
done

echo "Repository termux-devkit indexes are valid."
