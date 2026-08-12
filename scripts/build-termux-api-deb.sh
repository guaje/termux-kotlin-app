#!/bin/bash
# Rebuild termux-api .deb packages with the compiled C binary.
#
# Prerequisites:
#   Run scripts/build-termux-api-binary.sh first to compile the binaries.
#
# Usage:
#   ./scripts/build-termux-api-deb.sh
#
# Output:
#   Updates termux-api .deb files in app/src/main/assets/bootstrap-packages/.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BINARY_DIR="$PROJECT_ROOT/build/termux-api-binaries"
ASSETS_DIR="$PROJECT_ROOT/app/src/main/assets/bootstrap-packages"
WORK_DIR="$PROJECT_ROOT/build/termux-api-deb-work"

PREFIX="/data/data/com.termux/files/usr"
OLD_VERSION="0.59.1-1"
API_EPOCH_VERSION="1:0.59.1-1"
INTEGRATED_RECEIVER="com.termux/.api.TermuxApiReceiver"
SEPARATE_RECEIVER="com.termux.api/.TermuxApiReceiver"

# Architecture mapping: termux arch name → deb arch name
declare -A DEB_ARCHS=(
    [aarch64]="aarch64"
    [arm]="arm"
    [x86_64]="x86_64"
    [i686]="i686"
)

echo "=== Termux API .deb Rebuilder ==="
echo ""

verify_receiver_target() {
    local binary="$1"
    if ! LC_ALL=C grep -aFq -- "$INTEGRATED_RECEIVER" "$binary"; then
        echo "ERROR: $binary does not target $INTEGRATED_RECEIVER" >&2
        return 1
    fi
    if LC_ALL=C grep -aFq -- "$SEPARATE_RECEIVER" "$binary"; then
        echo "ERROR: $binary still targets $SEPARATE_RECEIVER" >&2
        return 1
    fi
}

# Verify binaries exist and target the integrated receiver.
for arch in aarch64 arm x86_64 i686; do
    binary="$BINARY_DIR/$arch/termux-api-broadcast"
    if [[ ! -f "$binary" ]]; then
        echo "ERROR: Missing binary for $arch: $binary"
        echo "Run scripts/build-termux-api-binary.sh first."
        exit 1
    fi
    verify_receiver_target "$binary"
done

# Clean work directory
rm -rf "$WORK_DIR"

rebuild_deb() {
    local arch="$1"
    local deb_arch="${DEB_ARCHS[$arch]}"
    local source_deb_name="termux-api_${OLD_VERSION}_${deb_arch}.deb"
    local output_deb_name="termux-api_1%3a0.59.1-1_${deb_arch}.deb"
    local old_source_deb="$ASSETS_DIR/$arch/$source_deb_name"
    local output_asset_deb="$ASSETS_DIR/$arch/$output_deb_name"
    local source_deb

    # Use the currently bundled old asset for the initial epoch migration. On
    # subsequent rebuilds, use the epoch output as the source.
    if [[ -f "$old_source_deb" ]]; then
        source_deb="$old_source_deb"
    elif [[ -f "$output_asset_deb" ]]; then
        source_deb="$output_asset_deb"
    else
        echo "  ERROR: Source .deb not found: $old_source_deb or $output_asset_deb" >&2
        return 1
    fi

    echo "--- Rebuilding $output_deb_name from $(basename "$source_deb") ---"

    local work="$WORK_DIR/$arch"
    mkdir -p "$work/extract" "$work/build"

    dpkg-deb -x "$source_deb" "$work/extract/"
    dpkg-deb -e "$source_deb" "$work/extract/DEBIAN/"

    local libexec="$work/extract${PREFIX}/libexec"
    local callback_content=""
    if [[ -f "$libexec/termux-api/termux-callback" ]]; then
        callback_content=$(cat "$libexec/termux-api/termux-callback")
    fi

    rm -rf "$libexec/termux-api"
    cp "$BINARY_DIR/$arch/termux-api-broadcast" "$libexec/termux-api-broadcast"
    chmod 755 "$libexec/termux-api-broadcast"
    ln -sf termux-api-broadcast "$libexec/termux-api"

    if [[ -n "$callback_content" ]]; then
        printf '%s\n' "$callback_content" > "$libexec/termux-callback"
        chmod 755 "$libexec/termux-callback"
    fi

    local control="$work/extract/DEBIAN/control"
    local version_count
    version_count=$(grep -c '^Version: ' "$control" || true)
    if [[ "$version_count" -ne 1 ]]; then
        echo "  ERROR: Expected exactly one control Version field, found $version_count" >&2
        return 1
    fi
    awk -v version="$API_EPOCH_VERSION" '
        /^Version: / { print "Version: " version; next }
        { print }
    ' "$control" > "$control.new"
    mv "$control.new" "$control"
    if ! grep -qx "Version: $API_EPOCH_VERSION" "$control"; then
        echo "  ERROR: Failed to set control Version to $API_EPOCH_VERSION" >&2
        return 1
    fi

    sed -i.bak 's/Depends: bash, util-linux, termux-am (>= 0.8.0)/Depends: bash, util-linux/' "$control"
    sed -i.bak 's/Description: Termux API commands (install also the Termux:API app)/Description: Termux API commands (integrated into Termux-Kotlin app)/' "$control"
    sed -i.bak '/Requires the Termux:API app/d' "$control"
    sed -i.bak 's|to be installed from Google Play or F-Droid\.|The API receiver is built into the main Termux-Kotlin app.|' "$control"

    local installed_size
    installed_size=$(du -sk --exclude=DEBIAN "$work/extract" 2>/dev/null | cut -f1 || du -sk "$work/extract" | cut -f1)
    sed -i.bak "s/Installed-Size: .*/Installed-Size: $installed_size/" "$control"
    rm -f "$control.bak"

    local output_deb="$work/$output_deb_name"
    dpkg-deb --build "$work/extract" "$output_deb" >/dev/null

    echo "  Verifying package..."
    local verify_dir="$work/verify"
    mkdir -p "$verify_dir"
    dpkg-deb -x "$output_deb" "$verify_dir/"
    local output_control
    output_control=$(dpkg-deb -f "$output_deb" Version)
    if [[ "$output_control" != "$API_EPOCH_VERSION" ]]; then
        echo "  ERROR: Output Version is $output_control, expected $API_EPOCH_VERSION" >&2
        return 1
    fi
    verify_receiver_target "$verify_dir${PREFIX}/libexec/termux-api-broadcast"

    if [[ ! -x "$verify_dir${PREFIX}/libexec/termux-api" ]]; then
        echo "  ERROR: $PREFIX/libexec/termux-api is not executable" >&2
        return 1
    fi
    if [[ ! -L "$verify_dir${PREFIX}/libexec/termux-api" ]]; then
        echo "  ERROR: $PREFIX/libexec/termux-api is not a symlink" >&2
        return 1
    fi
    if [[ -d "$verify_dir${PREFIX}/libexec/termux-api" ]]; then
        echo "  ERROR: termux-api is still a directory" >&2
        return 1
    fi

    cp "$output_deb" "$output_asset_deb"
    # Do not remove the old source asset until the epoch output is built and verified.
    rm -f "$old_source_deb"

    local size
    size=$(stat -c%s "$output_deb" 2>/dev/null || stat -f%z "$output_deb")
    echo "  ✓ $output_deb_name ($size bytes)"
}

FAILED=0
for arch in aarch64 arm x86_64 i686; do
    if ! rebuild_deb "$arch"; then
        echo "  ✗ Failed for $arch"
        FAILED=1
    fi
    echo ""
done

rm -rf "$WORK_DIR"

if [[ $FAILED -eq 0 ]]; then
    echo "=== All .deb packages rebuilt successfully ==="
    echo ""
    echo "Updated packages:"
    for arch in aarch64 arm x86_64 i686; do
        ls -lh "$ASSETS_DIR/$arch/termux-api_1%3a0.59.1-1_"*.deb
    done
else
    echo "=== Some architectures failed ==="
    exit 1
fi
