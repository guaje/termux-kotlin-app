#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
novnc_dir="$repo_root/app/src/main/assets/novnc"
packages_dir="$repo_root/app/src/main/assets/bootstrap-packages"
desktop_installer="$repo_root/app/src/main/assets/desktop-scripts/install-desktop.sh"
prefix="/data/data/com.termux/files/usr"
integrated_receiver="com.termux/.api.TermuxApiReceiver"
separate_receiver="com.termux.api/.TermuxApiReceiver"

(
  cd "$novnc_dir"
  sha256sum --check --quiet SHA256SUMS
)

(
  cd "$packages_dir"
  sha256sum --check --quiet sha256sums.txt
)

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/termux-kotlin-assets.XXXXXX")
trap 'rm -rf "$temp_dir"' EXIT

for arch in aarch64 arm x86_64 i686; do
  expected_name="termux-api_1%3a0.59.1-1_${arch}.deb"
  shopt -s nullglob
  api_debs=("$packages_dir/$arch"/termux-api_*.deb)
  shopt -u nullglob
  if [[ ${#api_debs[@]} -ne 1 || "$(basename "${api_debs[0]:-}")" != "$expected_name" ]]; then
    echo "Expected exactly one $expected_name for $arch." >&2
    exit 1
  fi

  deb="${api_debs[0]}"
  deb_dir="$temp_dir/$arch"
  mkdir -p "$deb_dir"
  (
    cd "$deb_dir"
    ar x "$deb"
  )
  control_archive=$(find "$deb_dir" -maxdepth 1 -name 'control.tar.*' -print -quit)
  data_archive=$(find "$deb_dir" -maxdepth 1 -name 'data.tar.*' -print -quit)
  if [[ -z "$control_archive" || -z "$data_archive" ]]; then
    echo "Invalid deb archive structure in $deb." >&2
    exit 1
  fi
  control=$(tar -xOf "$control_archive" ./control)
  if [[ "$(printf '%s\n' "$control" | sed -n 's/^Package: //p')" != "termux-api" ]]; then
    echo "Invalid Package field in $deb." >&2
    exit 1
  fi
  if [[ "$(printf '%s\n' "$control" | sed -n 's/^Version: //p')" != "1:0.59.1-1" ]]; then
    echo "Invalid Version field in $deb." >&2
    exit 1
  fi
  if [[ "$(printf '%s\n' "$control" | sed -n 's/^Architecture: //p')" != "$arch" ]]; then
    echo "Invalid Architecture field in $deb." >&2
    exit 1
  fi

  binary="$deb_dir/termux-api-broadcast"
  tar -xOf "$data_archive" "./data/data/com.termux/files/usr/libexec/termux-api-broadcast" > "$binary"
  if ! LC_ALL=C grep -aFq -- "$integrated_receiver" "$binary"; then
    echo "Bundled termux-api binary in $deb does not target $integrated_receiver." >&2
    exit 1
  fi
  if LC_ALL=C grep -aFq -- "$separate_receiver" "$binary"; then
    echo "Bundled termux-api binary in $deb still targets $separate_receiver." >&2
    exit 1
  fi
done

if grep -RInE --include='*.html' "(src|href)=[\"']https?://" "$novnc_dir"; then
  echo 'Bundled noVNC HTML contains an external script or stylesheet reference.' >&2
  exit 1
fi

bash -n "$desktop_installer"
grep -q '^export DEBIAN_FRONTEND=noninteractive$' "$desktop_installer"
grep -q -- '--force-confold' "$desktop_installer"
grep -q 'dpkg .*--configure -a' "$desktop_installer"

hold_line=$(grep -nE '^[[:space:]]*apt-mark[[:space:]]+hold[[:space:]]+termux-api([[:space:]]|$)' "$desktop_installer" | head -n 1 | cut -d: -f1)
first_package_operation_line=$(grep -nE '^[[:space:]]*(dpkg|apt)[[:space:]]' "$desktop_installer" | head -n 1 | cut -d: -f1)
if [[ -z "$hold_line" || -z "$first_package_operation_line" || "$hold_line" -ge "$first_package_operation_line" ]]; then
  echo 'apt-mark hold termux-api must occur before the first dpkg or apt package operation.' >&2
  exit 1
fi

echo 'Bundled noVNC, bootstrap packages, and desktop installer assets are valid.'
