# Termux fork package repository

This repository publishes packages for the `com.termux` fork. It currently exists to distribute the fork-corrected `termux-api` client, whose broadcast target is the receiver integrated into the main app:

```text
com.termux/.api.TermuxApiReceiver
```

The published package version is `1:0.59.1-1`. The `1:` Debian epoch makes it newer than upstream's unepoch'd `0.59.1-1`, so a normal `pkg upgrade` selects this fork package instead of restoring the upstream client that targets the separately installed `com.termux.api` app.

## Repository layout

The GitHub Pages repository is a flat repository split by Debian architecture:

```text
repo/
├── Release
├── aarch64/Packages{,.gz}  and termux-api_..._aarch64.deb
├── arm/Packages{,.gz}      and termux-api_..._arm.deb
├── x86_64/Packages{,.gz}   and termux-api_..._x86_64.deb
└── i686/Packages{,.gz}      and termux-api_..._i686.deb
```

This is not a `dists/<suite>/<component>` repository. The source URL must therefore point directly to the device's architecture directory and use `./` as the suite.

## Configure APT

Run `dpkg --print-architecture`, then add exactly one matching line to `$PREFIX/etc/apt/sources.list.d/termux-kotlin.list`:

```text
deb [trusted=yes] https://reapercanuk39.github.io/termux-kotlin-app/repo/aarch64/ ./
deb [trusted=yes] https://reapercanuk39.github.io/termux-kotlin-app/repo/arm/ ./
deb [trusted=yes] https://reapercanuk39.github.io/termux-kotlin-app/repo/x86_64/ ./
deb [trusted=yes] https://reapercanuk39.github.io/termux-kotlin-app/repo/i686/ ./
```

For example, an aarch64 device should contain only the first line. Then run:

```sh
pkg update
pkg upgrade
apt-cache policy termux-api
```

The repository is **unsigned**. `[trusted=yes]` is required because there is no signed `InRelease` or `Release.gpg`; it disables APT's publisher-authentication check for this source. HTTPS protects transport and the package index contains package checksums, but users are trusting the GitHub Pages account and its publication workflow rather than a repository signing key.

## Regenerate and publish

The canonical publication path is the `publish_repo` job in `.github/workflows/ci.yml`. On a successful main-branch run it:

1. Builds `termux-api` from `app/src/main/cpp/termux-api/` when an Android NDK is available, otherwise explicitly falls back to the committed package assets.
2. Replaces any previously published `termux-api` package for all four architectures.
3. Runs `scripts/generate-repo.sh` and `scripts/validate-repo-index.sh`.
4. Force-pushes the resulting flat repository to `gh-pages`.

To rebuild the committed package assets from source, use NDK `29.0.14206865`:

```sh
./scripts/build-termux-api-binary.sh --ndk-path /path/to/ndk/29.0.14206865
./scripts/build-termux-api-deb.sh
./scripts/validate-bundled-assets.sh
```

To regenerate the checked-in metadata from those assets without committing `.deb` files:

```sh
for arch in aarch64 arm x86_64 i686; do
  cp "app/src/main/assets/bootstrap-packages/$arch/termux-api_1%3a0.59.1-1_${arch}.deb" "repo/$arch/"
done
./scripts/generate-repo.sh repo
bash scripts/validate-repo-index.sh repo
rm repo/{aarch64,arm,x86_64,i686}/termux-api_*.deb
```

Review and merge the metadata changes; do not commit the copied `.deb` files on the application branch. The next eligible main-branch CI run publishes the packages and metadata to `gh-pages`.

## Verify the index offline

Validate the checked-in metadata with:

```sh
bash scripts/validate-repo-index.sh repo
```

This confirms that every architecture index has exactly one `termux-api` stanza at `1:0.59.1-1`. To additionally inspect the package payload and receiver target, copy the ignored asset `.deb` files into the matching `repo/<arch>/` directories as shown above and rerun the validator. You can also confirm that compressed and plain indexes match:

```sh
gzip -cd repo/aarch64/Packages.gz | cmp - repo/aarch64/Packages
```
