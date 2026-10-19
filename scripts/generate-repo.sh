#!/usr/bin/env bash
# Generate the flat Termux-Kotlin package repository metadata.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${1:-$SCRIPT_DIR/../repo}"
GPG_KEY="${GPG_KEY:-}"
GENERATE_XZ="${GENERATE_XZ:-false}"
# Only the four architectures the flat feed actually publishes. A Debian "all" bucket is for
# architecture-independent packages; nothing stages any .deb into it, and a flat repository has no
# Packages-arch-all mechanism, so an all/ index is unreachable clutter that apt never reads.
ARCHITECTURES=(aarch64 arm x86_64 i686)

file_size() {
    stat -c%s "$1" 2>/dev/null || stat -f%z "$1"
}

extract_control() {
    local deb="$1"
    local control_archive
    local control_dir
    local tar_flag=""

    # dpkg-deb understands every deb compression, and deb 1.3+ ships a zstd control archive by
    # default, so prefer it over unpacking the ar member by hand.
    if command -v dpkg-deb >/dev/null 2>&1; then
        control_dir=$(mktemp -d "${TMPDIR:-/tmp}/debcontrol.XXXXXX")
        if dpkg-deb --control "$deb" "$control_dir" >/dev/null 2>&1 && [[ -f "$control_dir/control" ]]; then
            cat "$control_dir/control"
            rm -rf "$control_dir"
            return 0
        fi
        rm -rf "$control_dir"
        echo "ERROR: dpkg-deb failed to extract the control archive from $deb" >&2
        return 1
    fi

    control_archive=$(ar t "$deb" | sed -n '/^control\.tar/ { p; q; }')
    if [[ -z "$control_archive" ]]; then
        echo "ERROR: No control archive found in $deb" >&2
        return 1
    fi

    # GNU tar does not autodetect the member's compression the way bsdtar does, so pick the flag
    # from the member name; zstd control archives otherwise abort the whole run.
    case "$control_archive" in
        *.zst | *.zstd) tar_flag="--zstd" ;;
        *.xz) tar_flag="-J" ;;
        *.gz) tar_flag="-z" ;;
    esac

    ar p "$deb" "$control_archive" | tar -xO ${tar_flag:+$tar_flag} ./control
}

generate_packages() {
    local arch="$1"
    local arch_dir="$REPO_DIR/$arch"
    local deb
    local debs=()

    mkdir -p "$arch_dir"
    shopt -s nullglob
    debs=("$arch_dir"/*.deb)
    shopt -u nullglob

    echo "Generating Packages for $arch..."
    : > "$arch_dir/Packages"

    for deb in "${debs[@]}"; do
        extract_control "$deb" >> "$arch_dir/Packages"
        printf 'Filename: %s/%s\n' "$arch" "$(basename "$deb")" >> "$arch_dir/Packages"
        printf 'Size: %s\n' "$(file_size "$deb")" >> "$arch_dir/Packages"
        printf 'SHA256: %s\n\n' "$(sha256sum "$deb" | cut -d' ' -f1)" >> "$arch_dir/Packages"
    done

    gzip -9 -kf "$arch_dir/Packages"
    if [[ "$GENERATE_XZ" == "true" ]]; then
        if ! command -v xz >/dev/null 2>&1; then
            echo "ERROR: GENERATE_XZ=true but xz is unavailable" >&2
            return 1
        fi
        xz -9 -kf "$arch_dir/Packages"
    else
        rm -f "$arch_dir/Packages.xz"
    fi
}

generate_root_packages() {
    # One index at the repository root, holding every architecture. This is what makes the feed
    # consumable: apt resolves a flat repository's downloads as URI + Filename, and Filename is
    # root-relative here ("aarch64/foo.deb"), so only a URI at the repository root downloads to the
    # right place. Pointing apt at repo/aarch64/ instead would fetch a good index and then look for
    # repo/aarch64/aarch64/foo.deb.
    #
    # Devices pick their own rows with `deb [arch=aarch64] ... ./`; the Architecture field each stanza
    # already carries is what apt filters on. The per-architecture indexes stay, because Release
    # checksums list them and existing sources point at them.
    local arch
    : > "$REPO_DIR/Packages"
    for arch in "${ARCHITECTURES[@]}"; do
        if [[ -f "$REPO_DIR/$arch/Packages" ]]; then
            cat "$REPO_DIR/$arch/Packages" >> "$REPO_DIR/Packages"
        fi
    done
    gzip -9 -kf "$REPO_DIR/Packages"
    if [[ "$GENERATE_XZ" == "true" ]] && command -v xz >/dev/null 2>&1; then
        xz -9 -kf "$REPO_DIR/Packages"
    else
        rm -f "$REPO_DIR/Packages.xz"
    fi
}

generate_release() {
    local arch
    local file

    echo "Generating Release file..."
    # Date must be UTC. apt answers a local offset (what `date -R` prints) with
    # "W: Invalid 'Date' entry in Release file", which silently disables Valid-Until style checks.
    # The format below matches what apt-ftparchive itself emits. Note that nothing may be commented
    # inside the heredoc: a heredoc body is literal file content, and `#` lines here end up inside
    # the Release file, where apt has no concept of a comment - and a `$(...)` in such a comment gets
    # expanded into it as well.
    cat > "$REPO_DIR/Release" <<EOF_RELEASE
Origin: Termux-Kotlin
Label: Termux-Kotlin
Suite: stable
Codename: termux-kotlin
Architectures: aarch64 arm x86_64 i686
Components: main
Description: Termux-Kotlin Package Repository
Date: $(LC_ALL=C date -u '+%a, %d %b %Y %H:%M:%S UTC')
SHA256:
EOF_RELEASE

    for arch in "${ARCHITECTURES[@]}"; do
        for file in "$arch/Packages" "$arch/Packages.gz" "$arch/Packages.xz"; do
            if [[ -f "$REPO_DIR/$file" ]]; then
                printf ' %s %s %s\n' \
                    "$(sha256sum "$REPO_DIR/$file" | cut -d' ' -f1)" \
                    "$(file_size "$REPO_DIR/$file")" \
                    "$file" >> "$REPO_DIR/Release"
            fi
        done
    done

    # The flat root index, so a device can use one line for every architecture.
    for file in "Packages" "Packages.gz" "Packages.xz"; do
        if [[ -f "$REPO_DIR/$file" ]]; then
            printf ' %s %s %s\n' \
                "$(sha256sum "$REPO_DIR/$file" | cut -d' ' -f1)" \
                "$(file_size "$REPO_DIR/$file")" \
                "$file" >> "$REPO_DIR/Release"
        fi
    done

    rm -f "$REPO_DIR/Release.gpg" "$REPO_DIR/InRelease"
    if [[ -n "$GPG_KEY" ]]; then
        echo "Signing Release..."
        gpg --batch --yes --default-key "$GPG_KEY" --armor --detach-sign \
            -o "$REPO_DIR/Release.gpg" "$REPO_DIR/Release"
        gpg --batch --yes --default-key "$GPG_KEY" --clearsign \
            -o "$REPO_DIR/InRelease" "$REPO_DIR/Release"
    fi
}

mkdir -p "$REPO_DIR"
REPO_DIR="$(cd "$REPO_DIR" && pwd)"

echo "=== Termux-Kotlin Repository Generator ==="
echo "Repository: $REPO_DIR"

for arch in "${ARCHITECTURES[@]}"; do
    generate_packages "$arch"
done

generate_root_packages
generate_release

echo "Repository generated successfully."
