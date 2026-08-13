# 🚀 Termux Kotlin App

<div align="center">

[![CI](https://github.com/reapercanuk39/termux-kotlin-app/actions/workflows/ci.yml/badge.svg)](https://github.com/reapercanuk39/termux-kotlin-app/actions/workflows/ci.yml)
[![Release](https://github.com/reapercanuk39/termux-kotlin-app/actions/workflows/release.yml/badge.svg)](https://github.com/reapercanuk39/termux-kotlin-app/actions/workflows/release.yml)
[![GitHub Downloads](https://img.shields.io/github/downloads/reapercanuk39/termux-kotlin-app/total?style=for-the-badge&logo=github&label=Downloads&color=success)](https://github.com/reapercanuk39/termux-kotlin-app/releases)
[![Latest Release](https://img.shields.io/github/v/release/reapercanuk39/termux-kotlin-app?style=for-the-badge&logo=android&label=Latest&color=blue)](https://github.com/reapercanuk39/termux-kotlin-app/releases/latest)

[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-7.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge)](LICENSE.md)
[![Fork](https://img.shields.io/badge/Fork%20of-termux%2Ftermux--app-orange?style=for-the-badge&logo=github)](https://github.com/termux/termux-app)

**The official Termux Android terminal emulator, fully converted to Kotlin!**

<a href="https://github.com/reapercanuk39/termux-kotlin-app/releases/latest">
  <img src="https://img.shields.io/badge/⬇️_Download_APK-Latest_Release-brightgreen?style=for-the-badge&logo=android" alt="Download APK">
</a>

[Features](#features) • [Installation](#installation) • [Building](#building) • [Contributing](#contributing) • [Original Project](#original-project)

</div>

---

## 📱 What is Termux?

**Termux** is a powerful **Android terminal emulator** and **Linux environment** app that works directly with no rooting or setup required. It provides a complete Linux environment on your Android device with access to:

- 🐧 **Linux shell** (bash, zsh, fish)
- 📦 **Package manager** (apt/pkg) with thousands of packages
- 🐍 **Programming languages** (Python, Node.js, Ruby, Go, Rust, C/C++)
- 🔧 **Development tools** (git, vim, nano, ssh, rsync)
- 🌐 **Networking utilities** (curl, wget, nmap, netcat)

## ✨ What is Termux Kotlin App?

This repository is a **complete Kotlin conversion** of the official [termux-app](https://github.com/termux/termux-app). Every Java file has been meticulously converted to idiomatic Kotlin while maintaining 100% compatibility with the original app.

### 📦 Full Upstream Compatibility (v2.0.0+)

Termux Kotlin App uses the `com.termux` package name for **100% compatibility with upstream Termux packages**. All packages install and work without any modification:

```
/data/data/com.termux/files/usr
```

**What works:**
- ✅ `pkg install python` - Python 3.12.12 installs perfectly
- ✅ `pip install <package>` - pip works out of the box
- ✅ All upstream packages work without path rewriting
- ✅ Uses official Termux bootstrap unchanged

**Trade-off:** Cannot be installed alongside the official Termux app (same package name, different signing key). This approach is inspired by [ZeroTermux](https://github.com/hanxinhao000/ZeroTermux) which uses the same strategy.

### 🆕 Kotlin Modernization

This fork focuses on:
- 100% Kotlin codebase (converted from Java)
- Modern Android development practices
- AI agent integration for development assistance

### ✨ v2.5.0 highlights

- Integrated X11/VNC desktop installer and bundled noVNC viewer
- Kitty keyboard progressive-enhancement and CSI-u input support
- Integrated Termux:API native bridge and common device actions
- Correct APK release metadata for reliable Obtainium installation and updates

See [Obtainium installation](docs/OBTAINIUM.md) and [canuk integration provenance](docs/CANUK_INTEGRATION.md).

### 🤖 Kotlin-Native Agent Daemon (v2.0.5+)

The agent framework now runs in **pure Kotlin** with zero Python dependency:

| Feature | Description |
|---------|-------------|
| **Auto-start** | Daemon starts automatically when app launches |
| **45+ Capabilities** | Fine-grained permission system for agents |
| **4 Pure Kotlin Skills** | pkg, fs, git, diagnostic |
| **Swarm Intelligence** | Stigmergy-based multi-agent coordination |
| **Python Fallback** | Complex skills gracefully degrade if Python missing |

### 🔌 Integrated Plugins (v2.0.5+)

No more separate plugin APKs! These features are now built-in:

| Plugin | Status | Features |
|--------|--------|----------|
| **Termux:Boot** | ✅ Built-in | Auto-run scripts on device boot |
| **Termux:Styling** | ✅ Built-in | 11 color schemes; bundled Fira Code, Hack, and JetBrains Mono; optional Nerd Font downloads; Compose UI |
| **Termux:Widget** | ✅ Built-in | 3 widget sizes, shortcut execution |
| **Termux:API** | ✅ Built-in | Native IPC plus battery, clipboard, toast, vibration, volume, and torch support |
| Termux:Tasker | 📋 Planned | Tasker integration |

### 📦 APK Size Explanation

| Architecture | APK Size | Bootstrap Size |
|-------------|----------|----------------|
| `arm64-v8a` | ~35 MB | 30 MB |
| `armeabi-v7a` | ~32 MB | 27 MB |
| `x86_64` | ~34 MB | 29 MB |
| `x86` | ~34 MB | 29 MB |
| `universal` | ~130 MB | All 4 combined |

**Why is it larger than original Termux?**

The APK includes **66 packages rebuilt from source** with native `com.termux.kotlin` paths. These packages have the correct paths compiled directly into the ELF binaries, ensuring:
- ✅ SSL/TLS works immediately (libgnutls uses correct cert path)
- ✅ Package management works out-of-box (apt/dpkg)
- ✅ No runtime path-patching needed

The original Termux downloads these packages from their repository, while we bundle them for immediate functionality.

### 🌍 Proper Environment Configuration

The app automatically configures all necessary environment variables:

| Category | Variables | Purpose |
|----------|-----------|---------|
| **Core** | `HOME`, `PREFIX`, `PATH`, `TMPDIR` | Basic terminal operation |
| **Libraries** | `LD_LIBRARY_PATH` | Override hardcoded RUNPATH in binaries |
| **Terminal** | `TERMINFO`, `TERM`, `COLORTERM` | Full terminal capability support |
| **Package Manager** | `DPKG_ADMINDIR`, `DPKG_DATADIR` | dpkg/apt path overrides |
| **SSL/TLS** | `SSL_CERT_FILE`, `CURL_CA_BUNDLE` | HTTPS mirror support |

See [ARCHITECTURE.md](ARCHITECTURE.md#-environment-variables) for the complete list.

### 🎯 Why Kotlin?

| Feature | Benefit |
|---------|---------|
| **Null Safety** | Compile-time null checks prevent NullPointerExceptions |
| **Concise Syntax** | ~40% less boilerplate code |
| **Type Inference** | Cleaner, more readable code |
| **Extension Functions** | Enhanced API without inheritance |
| **Coroutines Ready** | Modern async programming support |
| **Interoperability** | Seamless Java library compatibility |

## 🔄 Conversion Statistics

| Component | Java Files Converted | Kotlin Files Created |
|-----------|---------------------|---------------------|
| **app** | 40+ | 40+ |
| **terminal-emulator** | 15+ | 15+ |
| **terminal-view** | 10+ | 10+ |
| **termux-shared** | 80+ | 80+ |
| **Total** | **145+** | **145+** |

## 🚀 Features

All original Termux features are preserved:

- ✅ **Full Linux terminal** with touch/keyboard support
- ✅ **Package management** via apt (pkg)
- ✅ **Session management** with multiple terminal tabs
- ✅ **Customizable** extra keys row
- ✅ **Styling support** via Termux:Styling
- ✅ **Plugin ecosystem** (Termux:API, Termux:Boot, Termux:Widget, etc.)
- ✅ **Hardware keyboard** support with shortcuts
- ✅ **Background execution** via Termux:Tasker
- ✅ **URL handling** and file sharing

### 🆕 Kotlin-Exclusive Features

New features only available in the Kotlin version:

| Feature | Description |
|---------|-------------|
| 🎨 **Jetpack Compose UI** | Modern declarative UI for settings and dialogs |
| 🔍 **Command Palette** | VS Code-style fuzzy command search (Ctrl+Shift+P) |
| 📐 **Split Terminal** | Side-by-side or top/bottom terminal panes |
| 🔑 **SSH Manager** | Save and manage SSH connection profiles |
| 📜 **Command History** | Searchable command history with statistics |
| ⚡ **Kotlin Coroutines** | Efficient async operations with Flow |
| 💉 **Dependency Injection** | Hilt for clean architecture |
| 💾 **DataStore** | Modern preferences with reactive updates |
| 🎭 **Profile System** | Named profiles with theme, font, shell, and env vars |
| 🖌️ **Theme Gallery** | 10+ built-in themes with live preview |
| 💾 **Package Backup** | Full backup/restore of packages, repos, and dotfiles |
| 🩺 **Package Doctor** | Health checks with auto-repair suggestions |
| 🛠️ **termuxctl CLI** | Unified CLI for backup, doctor, and profile management |
| 📱 **Integrated Device API** | Built-in Termux:API - no separate APK needed |
| 🔒 **HTTPS Support** | Proper SSL/TLS certificate configuration for secure mirrors |
| 🖥️ **Full Terminal Support** | TERMINFO configured for clear, tput, ncurses apps |
| 🤖 **Agent Framework** | Offline Python-based agent system with skills & capabilities |

### 📱 Integrated Device API (No Separate APK!)

Unlike standard Termux which requires installing the separate **Termux:API** APK, the Kotlin version has device APIs **built directly into the main app**:

```bash
# Battery status
termuxctl device battery
termuxctl device battery --json --extended

# List available APIs
termuxctl device list

# Coming soon: location, sensors, clipboard, camera, wifi, and more
termuxctl device location --provider gps
termuxctl device sensor --name accelerometer
```

**Features:**
- ✅ **Zero Setup** - APIs work immediately after install
- ✅ **Coroutine-Based** - Efficient async operations
- ✅ **Type-Safe** - Sealed Result types and error handling
- ✅ **Unified Permissions** - Integrated permission manager

See [ARCHITECTURE.md](ARCHITECTURE.md#-integrated-device-api) for the full API list and implementation details.

### 🏗️ Modern Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                       Compose UI Layer                       │
│      (Settings, Command Palette, SSH Manager, Dialogs)       │
├─────────────────────────────────────────────────────────────┤
│                  ViewModels + StateFlow                      │
├─────────────────────────────────────────────────────────────┤
│                      Repositories                            │
│   (Settings, Sessions, History, SSH Profiles, Permissions)   │
├─────────────────────────────────────────────────────────────┤
│                      Core Modules                            │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐  │
│  │   core/api   │ │ core/logging │ │   core/permissions   │  │
│  │ Sealed Types │ │TermuxLogger  │ │  PermissionManager   │  │
│  │ Result<T,E>  │ │ File Logging │ │  Activity Result API │  │
│  └──────────────┘ └──────────────┘ └──────────────────────┘  │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐  │
│  │core/terminal │ │ core/plugin  │ │  core/deviceapi      │  │
│  │  EventBus    │ │  Plugin API  │ │  Battery, Location   │  │
│  │ Flow Events  │ │ Versioning   │ │  Sensors, Camera...  │  │
│  └──────────────┘ └──────────────┘ └──────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│              DataStore / Coroutines / Hilt DI                │
└─────────────────────────────────────────────────────────────┘
```

### Core Modules

| Module | Description |
|--------|-------------|
| `core/api` | Type-safe `Result<T,E>` and sealed error hierarchies |
| `core/logging` | Centralized logging with file output and Flow |
| `core/permissions` | Unified permission handling with coroutines |
| `core/terminal` | Flow-based event bus replacing callbacks |
| `core/plugin` | Stable plugin API with semantic versioning |
| `core/deviceapi` | Integrated device APIs (battery, location, sensors, etc.) |
| `ui/settings` | Material 3 Compose settings with DataStore |
| `pkg/backup` | Package backup/restore manager |
| `pkg/doctor` | Package health diagnostics and auto-repair |

### 🎨 Built-in Themes

10 beautiful themes included out of the box:

| Theme | Author | Description |
|-------|--------|-------------|
| **Dark Steel** | Termux Kotlin | Signature dark theme with steel blue accents |
| **Molten Blue** | Termux Kotlin | GitHub-inspired dark theme |
| **Obsidian** | Termux Kotlin | VS Code-inspired dark theme |
| **Dracula** | Zeno Rocha | Popular dark theme |
| **Nord** | Arctic Ice Studio | Arctic north-bluish palette |
| **Solarized Dark** | Ethan Schoonover | Classic precision colors |
| **Solarized Light** | Ethan Schoonover | Light variant |
| **Gruvbox Dark** | morhetz | Retro groove palette |
| **Gruvbox Light** | morhetz | Light variant |
| **High Contrast** | Termux Kotlin | Maximum readability |

### 💾 Package Management

Advanced package management features that surpass standard Termux:

```bash
# Create a full backup
termuxctl backup create --type full

# Restore with dry-run preview
termuxctl backup restore backup.json --dry-run

# Run package health diagnostics
termuxctl pkg doctor

# Auto-repair issues
termuxctl pkg doctor --auto-repair
```

### 🤖 Agent Framework

The Termux-Kotlin Agent Framework is a **fully offline**, Python-based agent system:

```bash
# Install Python (required)
pkg install python
pip install pyyaml

# List available agents
agent list

# Run a task through an agent
agent run debug_agent "apk.analyze" apk_path=/path/to/app.apk

# Show agent info and capabilities
agent info build_agent

# List available skills
agent skills
```

**Built-in Agents:**

| Agent | Purpose |
|-------|---------|
| `build_agent` | Package building, CI scripts, build log analysis |
| `debug_agent` | APK/ISO analysis, QEMU tests, binwalk |
| `system_agent` | Storage check, bootstrap validation, environment repair |
| `repo_agent` | Package repo sync, Packages.gz generation |

**Built-in Skills:** `pkg`, `git`, `fs`, `qemu`, `iso`, `apk`, `docker`

**Key Features:**
- 🔒 **Offline-only** - No external API calls, runs entirely locally
- 🛡️ **Capability system** - Fine-grained permissions (filesystem, network, exec)
- 📦 **Plugin architecture** - Easy to add new skills
- 💾 **Per-agent memory** - JSON-based persistent storage
- 📁 **Sandboxing** - Isolated directories per agent

See [AI.md](AI.md#-agent-framework-v100) for complete documentation.

## 📥 Installation

### Download APK

Download the latest release APK from the [Releases](https://github.com/reapercanuk39/termux-kotlin-app/releases) page.

Choose the appropriate variant for your device:
- `arm64-v8a` - Modern 64-bit phones (most devices)
- `armeabi-v7a` - Older 32-bit phones
- `x86_64` - 64-bit emulators/ChromeOS
- `x86` - 32-bit emulators
- `universal` - Works on all (larger file size)

### Obtainium

Add this repository URL to Obtainium and select the `termux-app_v2.5.0_universal.apk` release asset. See [docs/OBTAINIUM.md](docs/OBTAINIUM.md), especially the signing-key migration note for existing `com.termux` installations.

### Build from Source

See [Building](#building) section below.

## 🔨 Building

### Prerequisites

- **JDK 17** or higher
- **Android SDK** with Build Tools
- **Android NDK** (for native components)

### Build Commands

```bash
# Clone the repository
git clone https://github.com/reapercanuk39/termux-kotlin-app.git
cd termux-kotlin-app

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing.properties and explicit release metadata)
TERMUX_APP_VERSION_NAME=2.5.0 TERMUX_APK_VERSION_TAG=v2.5.0 ./gradlew assembleRelease

# APKs will be in app/build/outputs/apk/
```

### Build Variants

| Variant | Description |
|---------|-------------|
| `debug` | Development build with debugging enabled |
| `release` | Production build (requires signing) |

### 🔧 Custom Bootstrap

The app includes custom-built bootstraps with native `com.termux.kotlin` paths. To rebuild them:

```bash
# See full documentation
cat docs/CUSTOM_BOOTSTRAP_BUILD.md

# Quick overview:
# 1. Use Docker with termux/package-builder
# 2. Set TERMUX_APP__PACKAGE_NAME="com.termux.kotlin" in properties.sh
# 3. Build apt, dpkg, termux-exec, termux-tools, termux-core
# 4. Integrate into bootstrap zips
```

Pre-built packages are available in the `repo/` directory.

## 🔗 Related Repositories

| Repository | Description |
|------------|-------------|
| [termux/termux-app](https://github.com/termux/termux-app) | 🏠 Original Termux app (Java) |
| [reapercanuk39/termux-app](https://github.com/reapercanuk39/termux-app) | 🍴 My fork of official repo |
| [termux/termux-packages](https://github.com/termux/termux-packages) | 📦 Package build scripts |
| [termux/termux-api](https://github.com/termux/termux-api) | 🔌 Android API access plugin |

## 🤝 Contributing

Contributions are welcome! This project follows the same contribution guidelines as the original Termux project.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable/function names
- Add KDoc comments for public APIs
- Prefer immutable (`val`) over mutable (`var`)

## 📋 Original Project

This is a Kotlin conversion of the official **Termux** project:

- **Original Repository**: [github.com/termux/termux-app](https://github.com/termux/termux-app)
- **Original Authors**: [Termux Developers](https://github.com/termux)
- **License**: [GPLv3](LICENSE.md)

All credit for the original implementation goes to the Termux team. This conversion aims to modernize the codebase while maintaining full compatibility.

## 📄 License

```
Termux Kotlin App - Android terminal emulator (Kotlin version)
Copyright (C) 2024 Termux Developers & Contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.
```

---

<div align="center">

**Keywords**: `termux` `termux-app` `termux-kotlin` `android-terminal` `terminal-emulator` `linux-android` `kotlin-android` `android-app` `terminal` `shell` `bash` `linux` `android-terminal-emulator` `termux-android` `kotlin-conversion`

Made with ❤️ by [reapercanuk39](https://github.com/reapercanuk39)

⭐ **Star this repo** if you find it useful!

</div>
