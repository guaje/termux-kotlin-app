#!/usr/bin/env bash
# Build the termux-devkit .deb package that carries the on-device developer tooling.
#
# Usage:
#   ./scripts/build-devkit-package.sh [--version 0.1.0] [--output DIR] [--arch aarch64]
#
# Prerequisites: dpkg-deb on the build host (brew install dpkg).
#
# Output:
#   termux-devkit_<version>_<arch>.deb in the repository staging directory, ready for
#   scripts/generate-repo.sh to index, and copied to the APK assets directory when
#   --stage-assets is given so a fresh install can bootstrap the kit itself.
#
# The package is script-only by design: everything in it can be updated with
# `pkg upgrade` on the handset, which is what keeps a feature change off the APK
# install path. Anything that has to call Android framework APIs belongs in the app.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PKG_DIR="$PROJECT_ROOT/pkg/termux-devkit"
WORK_DIR="$PROJECT_ROOT/build/termux-devkit-deb"
OUTPUT_DIR="${TERMUX_DEBKIT_OUTPUT_DIR:-$PROJECT_ROOT/repo}"
VERSION="${TERMUX_DEBKIT_VERSION:-0.1.0}"
MAINTAINER="Termux-Kotlin <termux-kotlin@localhost>"
STAGE_ASSETS=0
ARCHES=""

PREFIX="/data/data/com.termux/files/usr"
# Termux architecture name to Debian architecture name, matching the other scripts.
ARCHES_ALL="aarch64 arm i686 x86_64"

while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION="$2"; shift 2 ;;
        --output) OUTPUT_DIR="$2"; shift 2 ;;
        --arch) ARCHES="$2"; shift 2 ;;
        --stage-assets) STAGE_ASSETS=1; shift ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) echo "ERROR: unknown argument: $1" >&2; exit 1 ;;
    esac
done

[ -z "$ARCHES" ] && ARCHES="$ARCHES_ALL"

command -v dpkg-deb >/dev/null 2>&1 || {
    echo "ERROR: dpkg-deb is not installed on this host (brew install dpkg)." >&2
    exit 1
}
[ -d "$PKG_DIR/usr/bin" ] || { echo "ERROR: missing package tree: $PKG_DIR/usr/bin" >&2; exit 1; }

# Termux patches dpkg, so `dpkg --print-architecture` reports aarch64/arm/i686/x86_64 rather than
# Debian's arm64/armhf/i386/amd64. A package whose Architecture field says amd64 is simply not
# installable here, which is why the termux names are used verbatim in both the control file and the
# file name - matching what scripts/build-termux-api-deb.sh ships.
deb_arch() {
    case "$1" in
        aarch64|arm|i686|x86_64) echo "$1" ;;
        *) echo "ERROR: unknown architecture $1" >&2; return 1 ;;
    esac
}

# Every installed script must be executable and must name the termux shebang, because a
# package that installs a non-executable command is only discovered at use time.
validate_payload() {
    local root="$1" failures=0
    for script in "$root"$PREFIX/bin/termux-*; do
        [ -f "$script" ] || continue
        if [ ! -x "$script" ]; then
            echo "  ERROR: $(basename "$script") is not executable" >&2
            failures=$((failures + 1))
        fi
        if ! head -1 "$script" | grep -q "^#!$PREFIX"; then
            echo "  ERROR: $(basename "$script") does not use the $PREFIX shebang" >&2
            failures=$((failures + 1))
        fi
        if ! bash -n "$script"; then
            echo "  ERROR: $(basename "$script") does not parse" >&2
            failures=$((failures + 1))
        fi
    done
    [ "$failures" -eq 0 ]
}

echo "=== termux-devkit package build ==="
echo ""

rm -rf "$WORK_DIR"

for arch in $ARCHES; do
    darch="$(deb_arch "$arch")"
    root="$WORK_DIR/$arch"
    mkdir -p "$root/DEBIAN"

    echo "--- $arch ($darch) ---"
    # Termux debs carry absolute paths, so the tidy source tree is placed at $PREFIX
    # inside the package root rather than being stored twice in the repository.
    mkdir -p "$root$PREFIX"
    cp -R "$PKG_DIR/usr/." "$root$PREFIX/"
    # cp -R through Finder-ish tooling loses bits; set them explicitly.
    find "$root$PREFIX/bin" -type f -exec chmod 0755 {} +

    installed_size="$(du -sk "$root$PREFIX" | cut -f1)"
    cat > "$root/DEBIAN/control" <<EOF
Package: termux-devkit
Version: $VERSION
Architecture: $darch
Maintainer: $MAINTAINER
Installed-Size: $installed_size
Depends: bash, termux-am, termux-tools
Priority: optional
Section: shells
Homepage: https://github.com/guaje/termux-kotlin-app
Description: Developer kit for Termux-Kotlin (reload, backup, restore, ship)
 Provides the on-device commands used by the developer loop: termux-reload to
 re-read configuration in the running app, termux-backup-prefix and
 termux-restore-prefix to make an app reinstall reversible, and termux-ship to
 prepare, dispatch and reconcile an APK replacement without keeping faith about
 what state the attempt was in. Everything here is scripts and configuration, so
 it ships through the package feed and never needs an APK install.
EOF

    validate_payload "$root" || { rm -rf "$root"; exit 1; }

    deb_name="termux-devkit_${VERSION}_${darch}.deb"
    out_dir="$OUTPUT_DIR/$arch"
    mkdir -p "$out_dir"
    dpkg-deb --build --root-owner-group "$root" "$WORK_DIR/$deb_name" >/dev/null

    verify="$WORK_DIR/verify-$arch"
    mkdir -p "$verify"
    dpkg-deb -x "$WORK_DIR/$deb_name" "$verify"
    for script in termux-reload termux-backup-prefix termux-restore-prefix termux-ship; do
        if [ ! -f "$verify$PREFIX/bin/$script" ]; then
            echo "ERROR: $deb_name does not contain bin/$script" >&2
            exit 1
        fi
    done
    built_version="$(dpkg-deb -f "$WORK_DIR/$deb_name" Version)"
    if [ "$built_version" != "$VERSION" ]; then
        echo "ERROR: $deb_name control Version is '$built_version', expected '$VERSION'" >&2
        exit 1
    fi

    # Do not remove the previous artifact until the new one is verified.
    mv "$WORK_DIR/$deb_name" "$out_dir/$deb_name"
    rm -rf "$verify" "$root"
    echo "  $(basename "$deb_name") ($(wc -c < "$out_dir/$deb_name" | tr -d ' ') bytes)"

    if [ "$STAGE_ASSETS" -eq 1 ]; then
        asset_dir="$PROJECT_ROOT/app/src/main/assets/bootstrap-packages/$arch"
        mkdir -p "$asset_dir"
        cp "$out_dir/$deb_name" "$asset_dir/$deb_name"
        echo "  staged into $asset_dir"
    fi
done

echo ""
echo "OK. Index it with ./scripts/generate-repo.sh and validate with ./scripts/validate-repo-index.sh"
echo "NOTE: the feed is unsigned, so keep the pinned checksum in the app up to date"
echo "      (see docs/HOT_RELOAD_DESIGN.md, threat model)."
