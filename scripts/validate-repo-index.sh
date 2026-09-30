#!/usr/bin/env bash
# Validate that a flat repository publishes the integrated epoch termux-api package.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${1:-$SCRIPT_DIR/../repo}"
EXPECTED_VERSION="1:0.59.1-1"
INTEGRATED_RECEIVER="com.termux/.api.TermuxApiReceiver"
SEPARATE_RECEIVER="com.termux.api/.TermuxApiReceiver"
ARCHITECTURES=(aarch64 arm x86_64 i686)

if [[ ! -d "$REPO_DIR" ]]; then
    echo "ERROR: Repository directory does not exist: $REPO_DIR" >&2
    exit 1
fi
REPO_DIR="$(cd "$REPO_DIR" && pwd)"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/termux-repo-index.XXXXXX")
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

    package_count=$(awk '
        BEGIN { RS = ""; count = 0 }
        $0 ~ /(^|\n)Package: termux-api(\n|$)/ { count++ }
        END { print count }
    ' "$packages")
    if [[ "$package_count" -ne 1 ]]; then
        echo "ERROR: Expected exactly one termux-api stanza in $packages, found $package_count" >&2
        exit 1
    fi

    version=$(awk '
        BEGIN { RS = ""; FS = "\n" }
        $0 ~ /(^|\n)Package: termux-api(\n|$)/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^Version: /) {
                    sub(/^Version: /, "", $i)
                    print $i
                }
            }
        }
    ' "$packages")
    if [[ "$version" != "$EXPECTED_VERSION" ]]; then
        echo "ERROR: termux-api in $packages has Version: $version; expected $EXPECTED_VERSION" >&2
        exit 1
    fi

    filename=$(awk '
        BEGIN { RS = ""; FS = "\n" }
        $0 ~ /(^|\n)Package: termux-api(\n|$)/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^Filename: /) {
                    sub(/^Filename: /, "", $i)
                    print $i
                }
            }
        }
    ' "$packages")
    if [[ -z "$filename" || "$filename" = /* || "$filename" == *".."* || "$filename" != "$arch/"* ]]; then
        echo "ERROR: Invalid termux-api Filename in $packages: $filename" >&2
        exit 1
    fi

    deb="$REPO_DIR/$filename"
    # An index that points at a package which is not in the tree is exactly how a stale
    # published repository breaks a device, so its absence is an error rather than a skip.
    if [[ ! -f "$deb" ]]; then
        echo "ERROR: $packages indexes $filename but the file is missing from $REPO_DIR" >&2
        exit 1
    fi

    index_size=$(awk 'BEGIN{RS="";FS="\n"} $0 ~ /(^|\n)Package: termux-api(\n|$)/ { for (i=1;i<=NF;i++) if ($i ~ /^Size: /) { sub(/^Size: /,"",$i); print $i } }' "$packages")
    index_sha256=$(awk 'BEGIN{RS="";FS="\n"} $0 ~ /(^|\n)Package: termux-api(\n|$)/ { for (i=1;i<=NF;i++) if ($i ~ /^SHA256: /) { sub(/^SHA256: /,"",$i); print $i } }' "$packages")
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

    if [[ -f "$deb" ]]; then
        deb_version=$(dpkg-deb -f "$deb" Version)
        deb_arch=$(dpkg-deb -f "$deb" Architecture)
        if [[ "$deb_version" != "$EXPECTED_VERSION" ]]; then
            echo "ERROR: $deb has Version: $deb_version; expected $EXPECTED_VERSION" >&2
            exit 1
        fi
        if [[ "$deb_arch" != "$arch" ]]; then
            echo "ERROR: $deb has Architecture: $deb_arch; expected $arch" >&2
            exit 1
        fi

        extract_dir="$temp_dir/$arch"
        mkdir -p "$extract_dir"
        dpkg-deb -x "$deb" "$extract_dir"
        binary="$extract_dir/data/data/com.termux/files/usr/libexec/termux-api-broadcast"
        if [[ ! -f "$binary" ]]; then
            echo "ERROR: Missing termux-api-broadcast in $deb" >&2
            exit 1
        fi
        if ! LC_ALL=C grep -aFq -- "$INTEGRATED_RECEIVER" "$binary"; then
            echo "ERROR: $deb does not target $INTEGRATED_RECEIVER" >&2
            exit 1
        fi
        if LC_ALL=C grep -aFq -- "$SEPARATE_RECEIVER" "$binary"; then
            echo "ERROR: $deb still targets $SEPARATE_RECEIVER" >&2
            exit 1
        fi
    else
        echo "NOTE: $filename is not present; validated metadata only."
    fi

    echo "Validated termux-api $EXPECTED_VERSION for $arch."
done

echo "Repository termux-api indexes are valid."
