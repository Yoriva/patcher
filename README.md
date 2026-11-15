<div align="center">

# Nexora

**AMX Mod X for Counter-Strike 1.6 on Android, without touching a single build file.**

Pick your CS 1.6 APK, hit **Patch**, install the result. Metamod, AMXX, the
modules, the bot and the plugin compiler all ride along.

[![Android](https://img.shields.io/badge/android-7.0%2B-3DDC84?logo=android&style=flat-square)](https://developer.android.com)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20armeabi--v7a-3DDC84?style=flat-square)](#supported-platforms)
[![License](https://img.shields.io/badge/license-Unlicense-3DDC84?style=flat-square)](#license)

</div>

---

## What it does

Nexora takes the stock CS 1.6 Android client and rewrites it into a fully
modded build. It injects the native AMXX stack, bundles a plugin compiler, and
re-signs the APK so it installs over the original.

### The screens

| Screen | What it's for |
|---|---|
| **Patch** | Choose the source APK, see the mod bundle status, patch, sign, install |
| **Compiler** | Compile `.sma` to `.amxx` with the bundled `amxxpc` |
| **Addons** | Install and manage the AMXX addons package (metamod, amxmodx, yapb) |
| **Store** | Browse and download custom plugins and libraries |

### Before you start

**Nexora patches the client shell.** The patched APK is not a full game — it is
a launcher that depends on a Xash3D engine APK. You need one of these installed
on the device first:

- [Xash3D FWGS engine](https://github.com/Poggtru/engine) — any build that
  matches the client (both `su.xash.engine` and `su.xash.engine.test` are
  supported; the client queries both)

Without the engine the patched app opens to a launcher screen that cannot start
the game.

Nexora also needs **all files access** (storage permission) to read your game
directory and write the patched APK.

## Requirements

- **Android 7.0** (API 24) or newer
- **arm64-v8a** or **armeabi-v7a** device
- A Xash3D engine APK (see above)
- A CS 1.6 client APK — the stock Nexora-built shell is downloaded
  automatically, or use your own
- Your game directory (usually `/sdcard/.xash/cstrike` — Nexora finds it
  automatically)

## Install

1. Download the latest APK from [Releases](https://github.com/berkchy/nexora/releases).
2. Install it, open **Patch** — the app downloads the client APK and the mod
   bundle for your ABI.
3. Open **Addons** and tap **Download & Install Addons** to install metamod,
   AMX Mod X and YaPB into your game directory.
4. Back in **Patch**, tap **Start Patch**, then **Install**.
5. Launch the game via the **Xash engine** app (not the Nexora icon).

## Why it's built this way

### Updates instead of repatching

A patched APK is a large binary. Re-downloading 60 MB every night to change a
few `.so` files is wasteful, so Nexora publishes a **manifest** alongside the
release. On launch it compares the SHA-256 of every installed library with the
manifest and only pulls down what actually changed.

### ABI-aware

Every library ships for both supported ABIs, and the manifest carries a
per-ABI manifest. The patcher picks the directory matching the device and
reports exactly what it is about to replace before it touches anything.

### Optional where it can be

`libclient_android_*.so` is optional on an APK that already carries a working
client: the patcher replaces it in place rather than refusing.

### Game folder auto-detection

The game directory is found automatically (`/sdcard/.xash/cstrike` by default,
`/sdcard/xash/cstrike` as fallback). Everything Nexora writes — addons
installation, metamod config, patch history — lands in the same directory the
engine actually uses, so the game boots into a working AMXX setup.

## Building from source

Local debug builds need two generated folders that CI creates at release time:

| Folder | How to create it |
|---|---|
| `android/app/src/main/jniLibs/<abi>/libamxxpc.so` + `libamxxpc32.so` | Download the `compiler-*` assets from the [`libs` release](https://github.com/berkchy/nexora/releases/tag/libs) and rename per CI (see `build-and-release-release.yml`) |
| `android/app/src/main/assets/keystore/` | `cp android/debug/patcher-release.p12 android/app/src/main/assets/keystore/debug.p12` and the two `.pem` files |

Then:

```sh
cd android
./gradlew :app:assembleDebug
```

## Supported platforms

| ABI | Renders as | Modules |
|---|---|---|
| `arm64-v8a` | 64-bit | `cstrike`, `csx`, `engine`, `fakemeta`, `fun`, `geoip`, `hamsandwich`, `json`, `nvault`, `reapi`, `regex`, `sockets`, `sqlite` |
| `armeabi-v7a` | 32-bit | same set |

The 64-bit build measures the ReGameDLL and engine struct offsets straight out
of the DWARF it just produced, so the shipped offset tables can never drift
from the binaries they describe.

## CI

`.github/workflows/` splits by what a commit touches:

| Workflow | Builds |
|---|---|
| `build-and-release.yml` | native bundle (AMXX, metamod, ReGameDLL), rolling `continuous` release |
| `build-and-release-release.yml` | stable app APK on `v*` tags |
| `build-client-apk.yml` | the CS16 client shell APK |

## License

Unlicense.

---

<div align="center">
  <sub>Counter-Strike is a trademark of Valve Corporation. This project is not
  affiliated with or endorsed by Valve.</sub>
</div>
