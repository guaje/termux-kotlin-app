# Canuk feature integration for v2.5.0

The v2.5.0 work selectively ports feature code from `canuk/main` rather than merging that branch or its release automation.

| Area | Source commits | Local treatment |
|---|---|---|
| Kitty keyboard | `e95944e6301e3b22d8fe75053271e17b2c045ca6` | Ported into the existing terminal modules, then corrected for progressive flag negotiation, one-based modifiers, event types, reset behavior, and tests. |
| X11/VNC | `e4e25d11bdf55f3a0150c9f51af224583b2bfabd`, final restoration `3aea370ba830d81b2813e68dcdc0c580f628e184` | Ported the final desktop UI, lifecycle manager, installer, and noVNC runtime. Obsolete disable/remove commits were not replayed. VNC is forced to loopback and WebView access is restricted. |
| Termux:API packages | `ed43904c` and rebuilt packages `5d688737` | Ported the final per-ABI packages with an APK-side SHA-256 manifest and verified bootstrap installation. |
| Termux:API IPC | `323cd83c0b329740bf7ea40d19ceaa0beb8bb01c` | Ported the native bridge, unexported receiver, socket result bridge, handlers, and rebuild scripts; added same-UID socket validation. |

Canuk CI/CD commit `0b9f9bc8` was intentionally not merged. Origin's workflows now use one signed release path and validate the manifest metadata and certificate before publication.
