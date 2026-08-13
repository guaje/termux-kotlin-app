# Bundled terminal fonts

These unmodified fonts are bundled for the integrated Termux:Styling picker.

| Asset | Upstream source | Revision | SHA-256 |
|---|---|---|---|
| `FiraCode-Regular.ttf` | https://github.com/tonsky/FiraCode/blob/5.2/distr/ttf/FiraCode-Regular.ttf | Fira Code 5.2 | `28c3ae21a853f1d74673384c7a0d620abb0e877b8c6cd8b64173a95512476824` |
| `JetBrainsMono-Regular.ttf` | https://github.com/JetBrains/JetBrainsMono/blob/02bb50b082dad9ef8a0f33ac393839202b760223/fonts/ttf/JetBrainsMono-Regular.ttf | `02bb50b082dad9ef8a0f33ac393839202b760223` | `e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1` |
| `Hack-Regular.ttf` | https://github.com/source-foundry/Hack/releases/download/v3.003/Hack-v3.003-ttf.zip (`ttf/Hack-Regular.ttf`) | Hack v3.003 | `15f55cc0c85a2988d2b4b3a8cdb5d77fdfbaf319e1bb5309d725db9818fb7125` |

Fira Code and JetBrains Mono are distributed under the SIL Open Font License 1.1.
Hack v3.003 is distributed under the MIT License and the Bitstream Vera License;
the exact applicable dual-license text is in [`licenses/Hack-LICENSE.md`](licenses/Hack-LICENSE.md).

Only Hack, Fira Code, and JetBrains Mono are embedded. All other Nerd Fonts are
explicit, user-initiated downloads from the pinned [Nerd Fonts v3.5.0
release](https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.5.0). Those archives
may be large and remain subject to the licenses documented by Nerd Fonts in its
[license audit](https://github.com/ryanoasis/nerd-fonts/blob/v3.5.0/license-audit.md).
Downloaded archives are checksum-verified before one regular terminal face is installed.
