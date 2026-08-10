#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
novnc_dir="$repo_root/app/src/main/assets/novnc"
packages_dir="$repo_root/app/src/main/assets/bootstrap-packages"

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

echo 'Bundled noVNC and bootstrap package assets are valid.'
