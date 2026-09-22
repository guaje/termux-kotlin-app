# Bundled terminal fonts

The integrated Termux:Styling picker embeds exactly one font: the terminal-safe
**Nerd Font Mono** variant of Hack. All other families — including Fira Code and
JetBrains Mono, which older builds bundled — are explicit, user-initiated downloads
from the pinned [Nerd Fonts v3.5.0 release](https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.5.0).

| Asset | Upstream source | SHA-256 |
|---|---|---|
| `HackNerdFontMono-Regular.ttf` | [Hack.zip](https://github.com/ryanoasis/nerd-fonts/releases/download/v3.5.0/Hack.zip) (archive `24a54aa41ff8ca5829409bfeb1bc2883b9fcafbf79f8d4b7674898550cb5e3b3`) → `HackNerdFontMono-Regular.ttf` | `28a157c93f850c603faf77819654925fdaf3abc431aefcb6f89ecb08d22f0a3e` |

Because the bundled face is a full Nerd Font, icon glyphs used by prompts such as
starship render out of the box. It is distributed under Hack's MIT / Bitstream Vera
dual license (see [`licenses/Hack-LICENSE.md`](licenses/Hack-LICENSE.md)) plus the
symbol licenses documented in the Nerd Fonts
[license audit](https://github.com/ryanoasis/nerd-fonts/blob/v3.5.0/license-audit.md).

Older builds embedded plain `Hack-Regular.ttf`, `FiraCode-Regular.ttf`, and
`JetBrainsMono-Regular.ttf` as built-ins. Existing installations still carrying the
legacy plain Hack as the selected or canonical font are upgraded automatically to
the bundled Nerd Font when Styling restores the selection; plain Fira Code and
JetBrains Mono selections are preserved as "Custom (font.ttf)" and their Nerd Font
variants can be downloaded from the catalog.

Downloaded archives are checksum-verified before one regular terminal face is
installed, and may be large and remain subject to the licenses documented by
Nerd Fonts in its [license audit](https://github.com/ryanoasis/nerd-fonts/blob/v3.5.0/license-audit.md).
