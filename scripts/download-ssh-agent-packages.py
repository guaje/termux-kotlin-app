#!/usr/bin/env python3
"""Download the OpenSSH dependency closure used by the bundled ssh-agent feature.

The standard Termux bootstrap deliberately excludes OpenSSH.  This script pins the
current packages from a Termux repository as APK assets so a first launch can
install them offline with dpkg.  It never installs packages on the development
machine.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import lzma
import re
import sys
import urllib.request
from email.parser import Parser
from pathlib import Path
from zipfile import ZipFile

ARCHITECTURES = ("aarch64", "arm", "i686", "x86_64")
ROOT_PACKAGES = ("openssh",)
DEFAULT_REPOSITORY = "https://packages.termux.dev/apt/termux-main"


def parse_control_paragraphs(contents: str) -> dict[str, dict[str, str]]:
    packages: dict[str, dict[str, str]] = {}
    for paragraph in contents.strip().split("\n\n"):
        fields = dict(Parser().parsestr(paragraph).items())
        package = fields.get("Package")
        if package:
            packages[package] = fields
    return packages


def package_dependencies(fields: dict[str, str]) -> list[str]:
    dependencies: list[str] = []
    for field_name in ("Pre-Depends", "Depends"):
        for dependency in fields.get(field_name, "").split(","):
            alternative = dependency.strip().split("|")[0].strip()
            package = re.split(r"\s|\(|:", alternative, maxsplit=1)[0]
            if package:
                dependencies.append(package)
    return dependencies


def bootstrap_packages(bootstrap: Path) -> set[str]:
    with ZipFile(bootstrap) as archive:
        status = archive.read("var/lib/dpkg/status").decode("utf-8")
    return set(parse_control_paragraphs(status))


def download(url: str) -> bytes:
    print(f"Downloading {url}")
    with urllib.request.urlopen(url) as response:
        return response.read()


def download_package_index(repository: str, architecture: str) -> str:
    base_url = f"{repository}/dists/stable/main/binary-{architecture}/Packages"
    for suffix, decompress in ((".xz", lzma.decompress), (".gz", gzip.decompress), ("", lambda data: data)):
        try:
            return decompress(download(base_url + suffix)).decode("utf-8")
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    raise RuntimeError(f"No Packages index found for {architecture}")


def dependency_closure(packages: dict[str, dict[str, str]], present: set[str]) -> list[str]:
    pending = list(ROOT_PACKAGES)
    selected: set[str] = set()
    while pending:
        package = pending.pop()
        if package in present or package in selected:
            continue
        fields = packages.get(package)
        if fields is None:
            raise RuntimeError(f"Package metadata is missing required dependency: {package}")
        selected.add(package)
        pending.extend(package_dependencies(fields))
    return sorted(selected)


def write_text(path: Path, contents: str) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(contents, encoding="utf-8")
    temporary.replace(path)


def fetch_architecture(repository: str, architecture: str, project_root: Path, assets: Path) -> list[str]:
    packages = parse_control_paragraphs(download_package_index(repository, architecture))
    bootstrap = project_root / "app" / "src" / "main" / "cpp" / f"bootstrap-{architecture}.zip"
    present = bootstrap_packages(bootstrap)
    selected = dependency_closure(packages, present)

    arch_dir = assets / architecture
    arch_dir.mkdir(parents=True, exist_ok=True)
    selected_paths: list[str] = []
    for package in selected:
        fields = packages[package]
        filename = fields["Filename"]
        expected_checksum = fields["SHA256"].lower()
        package_bytes = download(f"{repository}/{filename}")
        actual_checksum = hashlib.sha256(package_bytes).hexdigest()
        if actual_checksum != expected_checksum:
            raise RuntimeError(
                f"Checksum mismatch for {filename}: expected {expected_checksum}, got {actual_checksum}"
            )
        destination = arch_dir / Path(filename).name
        destination.write_bytes(package_bytes)
        selected_paths.append(f"{architecture}/{destination.name}")

    return selected_paths


def regenerate_checksums(assets: Path) -> None:
    entries: list[str] = []
    for package in sorted(assets.glob("*/*.deb")):
        relative = package.relative_to(assets).as_posix()
        digest = hashlib.sha256(package.read_bytes()).hexdigest()
        entries.append(f"{digest}  {relative}")
    write_text(assets / "sha256sums.txt", "\n".join(entries) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default=DEFAULT_REPOSITORY)
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[1]
    assets = project_root / "app" / "src" / "main" / "assets" / "bootstrap-packages"
    manifest = assets / "ssh-agent-packages.txt"
    if manifest.exists():
        for relative_path in manifest.read_text(encoding="utf-8").splitlines():
            if relative_path and not relative_path.startswith("#"):
                (assets / relative_path).unlink(missing_ok=True)

    selected_paths: list[str] = []
    for architecture in ARCHITECTURES:
        selected_paths.extend(fetch_architecture(args.repository.rstrip("/"), architecture, project_root, assets))

    manifest_contents = "# Generated by scripts/download-ssh-agent-packages.py.\n"
    manifest_contents += "# Packages required to install OpenSSH offline on a fresh bootstrap.\n"
    manifest_contents += "\n".join(selected_paths) + "\n"
    write_text(manifest, manifest_contents)
    regenerate_checksums(assets)
    print(f"Bundled {len(selected_paths)} OpenSSH packages across {len(ARCHITECTURES)} architectures.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
