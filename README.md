<p align="center">
  <img src="docs/assets/logo.png" alt="AxM-Next logo" width="128">
</p>

<h1 align="center">AxM-Next</h1>

> **AxM-Next** is a modified fork of **[AxManager](https://github.com/fahrez182/AxManager)** by **fahrez182**: a self-contained, ADB/Root-powered environment manager for Android with plugins, a WebUI, and a permission manager built on the Shizuku API.

[Switch to Chinese translation 切换到中文翻译](README_cn.md) *(upstream AxManager translation, not updated for AxM-Next)*

## 🍴 About this fork

AxM-Next is a fork of AxManager, which is created and maintained by [fahrez182](https://github.com/fahrez182). The core idea, architecture, and the vast majority of the code come from the original project, and all credit for that work belongs to its author.

- Original repository: <https://github.com/fahrez182/AxManager>
- Original author: [fahrez182](https://github.com/fahrez182)
- AxM-Next maintainer: [Youngupdatesource (RelJawa)](https://github.com/Youngupdatesource)

> **Important:** if you redistribute or modify this project, please keep attribution to the original developer, fahrez182, and retain the [Apache License 2.0](LICENSE).

## 💡 The concept

AxManager explores a dedicated **ADB environment** on Android: instead of being a simple command runner, it keeps a resident, privileged layer (the `axeron_server` daemon) that apps and plugins can talk to. AxM-Next keeps that foundation and focuses on a lighter, more stable, and more polished experience.

## ✨ Features

### Inherited from AxManager
- 🏗️ **Internal ADB environment**: a self-contained environment that maintains and uses ADB-level privileges.
- 🖥️ **Shell executor**: run shell commands with persistent sessions, via ADB (non-root) or optionally root.
- ⚡ **Plugins (unrooted modules)**: manage third-party modules without root. [Learn more](https://fahrez182.github.io/AxManager/plugin/what-is-plugin.html)
- 🌐 **WebUI**: manage the environment and plugins through a web-based interface.
- 🔐 **Permission manager** built on the Shizuku API.

### What AxM-Next changes

**UI / UX**
- Rebranded as **AxM-Next v1.0**, with new launcher icons and a new maintainer profile.
- **Status card banner** with your own image (JPG, PNG, WebP, or animated GIF, up to 8 MB) at a standard **16:9** ratio, configurable in *Appearance*.
- Stat tiles and a device info card (device, kernel, Android version, ABI, SELinux context).
- Swipe left or right to switch between tabs, plus reworked navigation transitions.
- **Plugin WebUI shortcuts, customizable:** long-press a plugin card to open a centered configuration dialog. Choose the shortcut name and icon (default, the plugin's banner, or your own image). Icons are built as *adaptive icons*, so your launcher decides the final shape (circle, squircle, and so on).
- **Permission manager filter:** only show apps that request `moe.shizuku.manager.permission.API_V23`, so the list reflects apps that really use the Shizuku API. Apps that are switched on are pinned to the top.

**Performance**
- App icon loading is throttled (at most three in parallel), uses fixed-size requests, and keeps a bounded 16 MB memory cache that is cleared when the app goes to the background.
- Search keys (including Pinyin) are computed once instead of on every keystroke.
- The permission list skips system apps early and loads in parallel.
- WebUI icons use a bounded cache, recycled bitmaps, smaller images, and HTTP cache headers.
- Network client is created lazily.

**Reliability and power**
- **Configurable wake lock policy** for `axeron_server` (see below). The default holds no wake lock, so the device can reach deep sleep.
- **Server Guard:** the manager checks the server periodically (WorkManager, every 30 minutes) and restarts it with exponential backoff if it stops unexpectedly. Intentional shutdowns and restarts from the app are respected, and auto-restart can be turned off with the `server_guard_auto_restart` setting.
- **Daemon Guard:** on every server start, the daemon logs diagnostics (cgroup, `oom_score_adj`, adbd state) and exempts the manager from battery optimization and background restrictions. This does not keep the CPU awake.
- The server retries the manager-app lookup before exiting, so a transient failure no longer kills the daemon.

### Wake lock modes

The mode is read once when the server starts, from a file named `ax_wakelock_mode` placed in the same folder as `ax_perm_companion` (the server prints the exact path in logcat on start).

| File content | Behavior |
| --- | --- |
| *(missing or anything else)* | **never** (default). No wake lock is held. |
| `adaptive` | A wake lock is held only while commands run, for 60 seconds after the last client call, and for the first 60 seconds after the server starts. |
| `always` | A permanent wake lock, like the original AxManager. Uses more battery. |

Restart the server after changing the file.

## 🔧 Build & Install

AxM-Next no longer relies on git history for versioning: the version is fixed at **1.0** (`versionCode` comes from the shared `api` manifest).

### Requirements
- JDK 21
- Android SDK platform 36, build-tools 36.0.0
- NDK 29.0.14206865 and CMake 3.22.1
- Git (the `api` folder is a submodule, so clone recursively)

### Build locally

```bash
git clone --recursive https://github.com/Youngupdatesource/AxM-Next.git
cd AxM-Next
```

Create a keystore once and describe it in `local.properties` (never commit either file):

```properties
sdk.dir=/path/to/android/sdk
signing.storeFile=/path/to/axm-next.jks
signing.storePassword=your-store-password
signing.keyAlias=your-alias
signing.keyPassword=your-key-password
```

Then build:

```bash
./gradlew :manager:assembleRelease
```

The APK is written to `manager/build/outputs/apk/release/` as `AxM-Next_v1.0_<versionCode>-release_<timestamp>.apk`. Use `:manager:assembleDebug` for a debug build.

> Release builds strip `android.util.Log` calls through R8 rules. Use a debug build when you need to read the app or server logs.

### Build with GitHub Actions

You can build entirely on GitHub, even from a phone, with the workflow in `.github/workflows/build.yml`.

1. Open the **Actions** tab of your fork and enable workflows.
2. Optional but recommended: store a permanent signing key as repository secrets (one time):

   ```bash
   keytool -genkeypair -keystore axm-next.jks -storetype PKCS12 -alias axm -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 axm-next.jks | gh secret set KEYSTORE_B64
   gh secret set KEYSTORE_PASSWORD
   gh secret set KEY_ALIAS
   gh secret set KEY_PASSWORD
   ```

   For a PKCS12 keystore, `KEY_PASSWORD` must equal `KEYSTORE_PASSWORD`. Keep a private backup of the keystore: if you lose it, new builds can no longer update installed ones.
3. Run the workflow:

   ```bash
   gh workflow run build.yml -f build_type=Release
   gh run watch
   ```

4. Download the APK:

   ```bash
   gh run list --limit 3
   gh run download <run-id> -n AxM-Next-apk
   ```

If the signing secrets are missing or invalid, the workflow generates a temporary random key and prints a warning. Android requires every APK to be signed, but an APK signed with a random key cannot update an existing install, so uninstall the old build first.

### Publish a release

```bash
gh workflow run build.yml -f build_type=Release -f publish=true -f tag=v1.0
```

Publishing requires valid signing secrets (the workflow refuses to publish an APK signed with a random key). It attaches the APK and its SHA-256 checksum to a GitHub release.

### Install

```bash
adb install -r manager/build/outputs/apk/release/AxM-Next_*.apk
```

Or copy the APK to your phone and open it. If Android refuses to install over an existing build, the existing one was signed with a different key. Uninstall it first.

## 📖 Roadmap
- [x] Wireless Debugging activator.
- [x] Command-line / Root activator.
- [x] Shell executor basic support (ADB / non-root).
- [x] Auto-activate when using Wireless Debugging (test).
- [x] [Plugin](https://fahrez182.github.io/AxManager/plugin/what-is-plugin.html) system for third-party extensions.
- [x] Developer mode and advanced debugging tools.
- [x] Server auto-recovery and configurable wake lock policy.
- [ ] App optimization based on profiles.

## 🤝 Contribution
Contributions are welcome. Open an **issue**, submit a **pull request**, or start a discussion for ideas and improvements.

## 🙏 Credits
- **[AxManager](https://github.com/fahrez182/AxManager)** by **fahrez182**: the original project this fork is based on.
- **[Axora](https://github.com/corvexis/Axora)**: UI/UX inspiration.
- **[FolkPure](https://github.com/matsuzaka-yuki/FolkPure)**: UI/UX inspiration.
- **[Magisk](https://github.com/topjohnwu/Magisk)**: BusyBox and plugin (unrooted module) ideas.
- **[Shizuku](https://github.com/RikkaApps/Shizuku) / [Shizuku-API](https://github.com/RikkaApps/Shizuku-API)**: starting point and reference for Android IPC and ADB-based permission handling.
- **[KernelSU](https://github.com/tiann/KernelSU) / [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next)**: inspiration for the UI and WebUI features.

## ⚠️ Notices & legal disclaimer
This project includes adapted portions of code from:
- Shizuku Manager (© Rikka Apps), licensed under the Apache License 2.0. Repository: <https://github.com/RikkaApps/Shizuku>
- AxManager (© fahrez182), licensed under the Apache License 2.0. Repository: <https://github.com/fahrez182/AxManager>
- Other open-source projects as credited above.

AxM-Next does not include or distribute any original Shizuku Manager visual assets, and it is not an official replacement for any of the projects above. Axora and FolkPure are credited as design inspiration only. All adapted code is used with attribution and in compliance with the Apache License 2.0.

## 📜 License
Licensed under the [Apache License 2.0](LICENSE). Modifications are made by the AxM-Next maintainer as described above.
