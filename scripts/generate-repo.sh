#!/usr/bin/env bash
# Generate the flat Termux-Kotlin package repository metadata.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${1:-$SCRIPT_DIR/../repo}"
GPG_KEY="${GPG_KEY:-}"
GENERATE_XZ="${GENERATE_XZ:-false}"
ARCHITECTURES=(aarch64 arm x86_64 i686 all)

file_size() {
    stat -c%s "$1" 2>/dev/null || stat -f%z "$1"
}

extract_control() {
    local deb="$1"
    local control_archive

    control_archive=$(ar t "$deb" | sed -n '/^control\.tar/ { p; q; }')
    if [[ -z "$control_archive" ]]; then
        echo "ERROR: No control archive found in $deb" >&2
        return 1
    fi

    ar p "$deb" "$control_archive" | tar -xO ./control
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

generate_release() {
    local arch
    local file

    echo "Generating Release file..."
    cat > "$REPO_DIR/Release" <<EOF_RELEASE
Origin: Termux-Kotlin
Label: Termux-Kotlin
Suite: stable
Codename: termux-kotlin
Architectures: aarch64 arm x86_64 i686 all
Components: main
Description: Termux-Kotlin Package Repository
Date: $(date -R)
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

generate_release

echo "Repository generated successfully."
