#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
novnc_dir="$repo_root/app/src/main/assets/novnc"
packages_dir="$repo_root/app/src/main/assets/bootstrap-packages"
desktop_installer="$repo_root/app/src/main/assets/desktop-scripts/install-desktop.sh"

(
  cd "$novnc_dir"
  sha256sum --check --quiet SHA256SUMS
)

(
  cd "$packages_dir"
  sha256sum --check --quiet sha256sums.txt
)

if grep -RInE --include='*.html' "(src|href)=[\"']https?://" "$novnc_dir"; then
  echo 'Bundled noVNC HTML contains an external script or stylesheet reference.' >&2
  exit 1
fi

bash -n "$desktop_installer"
grep -q '^export DEBIAN_FRONTEND=noninteractive$' "$desktop_installer"
grep -q -- '--force-confold' "$desktop_installer"
grep -q 'dpkg .*--configure -a' "$desktop_installer"

echo 'Bundled noVNC, bootstrap packages, and desktop installer assets are valid.'
