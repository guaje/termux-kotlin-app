# Installing with Obtainium

Use the GitHub repository URL for this fork as the Obtainium source. Select the universal release asset matching:

```text
termux-app_v2.5.0_universal.apk
```

An ABI-specific release APK may be selected instead when the device ABI is known. Production releases never include debug APKs.

## Version handling

GitHub tag `v2.5.0` is built into the APK as:

```text
applicationId = com.termux
versionName = 2.5.0
versionCode = 20500999
```

The release workflow checks these values inside every APK before publication. Future version codes are generated deterministically from semantic versions, so Android sees each release as an actual upgrade rather than repeatedly installing the old `1.2.3` manifest metadata.

## Signing and existing Termux installations

Android requires an update to have the same application ID and signing certificate as the installed APK. This fork intentionally retains `com.termux` for package and prefix compatibility.

If `com.termux` was installed from F-Droid, Google Play, another fork, or a debug/test-key release, uninstall that app before installing this release. This is a one-time migration; subsequent releases from this repository must retain the v2.5.0 production signing key.

Back up `$HOME` and any important files before uninstalling an incompatible installation.
