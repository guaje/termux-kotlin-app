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
COMMANDS=(termux-reload termux-backup-prefix termux-restore-prefix termux-ship)
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

# The root index, which is the one a device should be pointed at. Everything below is the contract
# apt actually relies on, measured on a handset rather than assumed: apt fetches only the index files
# that Release advertises, treats a suite ending in ./ as a flat repository, and resolves downloads
# as URI + Filename. So Filename must resolve from the repository root, not from an architecture
# directory, or apt fetches a good index and then asks for repo/aarch64/aarch64/foo.deb and 404s.
root_packages="$REPO_DIR/Packages"
if [[ ! -f "$root_packages" ]]; then
    echo "ERROR: Missing root index: $root_packages (a device cannot use one URI for all arches without it)" >&2
    exit 1
fi
if [[ ! -f "$root_packages.gz" ]]; then
    echo "ERROR: Missing compressed root index: $root_packages.gz" >&2
    exit 1
fi
if ! gzip -cd "$root_packages.gz" | cmp -s - "$root_packages"; then
    echo "ERROR: $root_packages.gz does not match $root_packages" >&2
    exit 1
fi

# apt discovers index files from Release; an unadvertised root index is never fetched, and the
# device reports "does not seem to provide it (sources.list entry misspelt?)".
if ! grep -q ' Packages$' "$REPO_DIR/Release"; then
    echo "ERROR: Release does not advertise Packages, so apt will never fetch it" >&2
    exit 1
fi
if ! grep -q ' Packages.gz$' "$REPO_DIR/Release"; then
    echo "ERROR: Release does not advertise Packages.gz, so apt will never fetch it" >&2
    exit 1
fi
# apt refuses a Date with a local offset: "W: Invalid 'Date' entry in Release file".
if ! grep -q '^Date: .* UTC$' "$REPO_DIR/Release"; then
    echo "ERROR: Release Date is not UTC, which apt reports as an invalid Date entry" >&2
    exit 1
fi
# Release has no concept of comments. This check exists because a comment written inside the
# generate-repo.sh heredoc lands in the published file verbatim, and apt then reports the Date as
# invalid even though the Date itself is fine.
if grep -q '^#' "$REPO_DIR/Release"; then
    echo "ERROR: Release contains '#' lines; apt has no comments and rejects the surrounding fields" >&2
    exit 1
fi

for arch in "${ARCHITECTURES[@]}"; do
    if [[ ! -f "$REPO_DIR/$arch/Packages" ]]; then
        continue
    fi
    if ! awk -v RS='' -v arch="$arch" '
        $0 ~ /(^|\n)Package: termux-devkit(\n|$)/ && $0 ~ "(^|\\n)Architecture: " arch "(\\n|$)" { found++ }
        END { exit found == 1 ? 0 : 1 }
    ' "$root_packages"; then
        echo "ERROR: root index is missing exactly one termux-devkit stanza for $arch" >&2
        exit 1
    fi
done

# Every Filename must exist relative to the repository root, which is where a single-root URI sends
# apt to look for it.
while read -r filename; do
    if [[ ! -f "$REPO_DIR/$filename" ]]; then
        echo "ERROR: root index resolves $filename against the repository root, and it is not there" >&2
        exit 1
    fi
done < <(awk -v RS='' -v FS='\n' '/Package: termux-devkit/ { for (i = 1; i <= NF; i++) if ($i ~ /^Filename: /) { sub("^Filename: ", "", $i); print $i } }' "$root_packages")

# The root index is a concatenation of the per-architecture ones, so a device and a per-arch reader
# can never disagree about what is published.
cat_root="$temp_dir/root-concat"
: > "$cat_root"
for arch in "${ARCHITECTURES[@]}"; do
    if [[ -f "$REPO_DIR/$arch/Packages" ]]; then
        cat "$REPO_DIR/$arch/Packages" >> "$cat_root"
    fi
done
if ! cmp -s "$cat_root" "$root_packages"; then
    echo "ERROR: $root_packages is not the concatenation of the per-architecture indexes" >&2
    exit 1
fi

echo "Validated root index: $(grep -c '^Package: ' "$root_packages") stanzas, resolvable from one URI."
echo "Repository termux-devkit indexes are valid."
