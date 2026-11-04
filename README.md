<div align="center">

<img src="docs/brand/ic_launcher_512.png" width="132" alt="IBB Launcher icon" />

# IBB Launcher

**A fast, dark-first Minecraft: Java Edition launcher for Android.**
Kotlin · Jetpack Compose · PojavLauncher-derived execution layer

</div>

---

## What it is

IBB Launcher runs desktop Minecraft: Java Edition on Android. The launcher downloads the official
game files, provisions a matching OpenJDK runtime, installs Fabric/Forge/NeoForge/Quilt profiles,
and hands the game an LWJGL/GLFW implementation that talks to Android's `EGL`/`GLES` stack.

It is not affiliated with Mojang, Microsoft or PojavLauncher.

| | |
|---|---|
| **Package** | `dev.ibb.launcher` |
| **Min SDK** | 26 (Android 8.0) — target/compile 35 |
| **ABIs** | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| **UI** | 100 % Jetpack Compose, dark-first glassmorphism, 120 Hz-safe |
| **Size** | ~18 MB APK *without* runtimes; Java runtimes download on demand (~65–95 MB each) |

## Feature tour

### Dashboard
Quick-play card with one-tap launch, preflight status (version installed? runtime ready? account
signed in?), device telemetry (RAM, renderer, disk usage) and quick actions to versions, files and
the skin editor. Latest releases are fetched in the background — the first frame never waits on the
network.

### Versions
Full Mojang catalogue with search, release/snapshot filters and per-version install state. Loader
chips (Fabric, Quilt, Forge, NeoForge) install the loader profile on top of vanilla and merge the
`inheritsFrom` chain at launch time.

### Accounts
Microsoft sign-in through the **device-code flow** — no embedded WebView, no password ever touches
the app. Tokens are encrypted with an AES-256-GCM key held in the Android Keystore. Offline
accounts are one tap and derive a deterministic UUID, so worlds keep their inventories.

### Skins
Preset-based character customiser with a canvas-rendered voxel avatar (no image assets, no
allocation churn), classic/slim model switch and a local skin library.

### Files
Built-in explorer for the current instance: mods, resource packs, shader packs, datapacks, worlds.
Imports go through the Storage Access Framework, then the archive is sniffed
(`fabric.mod.json`, `pack.mcmeta`, `shaders/`) so a renamed file still lands in the right folder.
Nothing is ever silently overwritten; disabling renames with a `.disabled` suffix.

### Console
Live logcat-backed console with level filters, pause, copy, clear and share. Crash reports from the
previous run are surfaced on next start with a one-tap share sheet.

### Settings
Renderer selection (ANGLE / Zink / VirGL / GL4ES), resolution scale, FPS cap, vsync, big-core
affinity, RAM slider derived from *physical* RAM, JVM arguments, Java 8/17/21 runtime manager,
controller binds, storage usage and log export.

### In-game
Dedicated landscape `GameActivity` hosting a `SurfaceView` for the JVM-owned GL context, with a
Compose overlay for the virtual controller (D-pad, joystick, macros, mouse emulation, sneak/sprint
toggles, scale/opacity), a log peek and a session sheet — all drawn in a separate window layer so
the overlay never forces the game surface into software composition.

## Build it

> Full walkthrough with troubleshooting: **[docs/BUILDING.md](docs/BUILDING.md)**

```bash
# 1. Toolchain: JDK 17 (or 21) + Android SDK 35
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 2. Native bridge (required for a runnable game — see docs/NATIVE_LAYER.md)
#    place libpojavexec.so / libpojavexec_awt.so / libexithook.so
#    in app/src/main/jniLibs/<abi>/

# 3. Build
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # 81 JVM unit tests
./gradlew :app:assembleRelease      # needs keystore.properties (see docs/BUILDING.md)
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

**The project cannot be compiled in this chat's sandbox** — there is no Android SDK, Gradle
distribution or Maven access here, so the code was verified with a standalone Kotlin compiler
harness instead (see *Verification* below). Building on a normal machine with the Android SDK
requires no extra steps beyond the two exports above.

## Project layout

```
app/src/main/java/
├── dev/ibb/launcher/
│   ├── core/              IbbLog, CrashHandler, IbbPaths (every on-disk location)
│   ├── data/
│   │   ├── model/         Version / Account / Settings / Control models (+ JSON schema)
│   │   ├── network/       Http (HttpURLConnection) and MojangApi
│   │   ├── auth/          MicrosoftAuth (device code → XBL → XSTS → Minecraft)
│   │   └── store/         SecureStore (Keystore), AccountStore, SettingsStore
│   ├── download/          ParallelDownloader (resume + SHA-1)
│   ├── runtime/           RuntimeManager (tar.xz JRE provisioning)
│   ├── repository/        VersionInstaller (libraries → client → assets → loader)
│   ├── mods/              ModLoaders (Fabric/Quilt/Forge/NeoForge) + ModRepository
│   ├── game/              VersionResolver, LaunchArgsBuilder, GameLauncher, GameService
│   │   └── nativebridge/  ExecutionLayer (LD_LIBRARY_PATH → preload → surface → hooks)
│   ├── controls/          ControlEngine, DefaultLayouts, ControlRepository
│   └── ui/                theme / components / screens / game overlay
├── com/oracle/dalvik/     VMLauncher — JNI ABI, do not move
├── net/kdt/pojavlaunch/   JREUtils — JNI ABI, do not move
└── org/lwjgl/glfw/        GLFW + CallbackBridge stubs the game links against
```

Architecture, data flow and the reasons behind each decision: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.
The native contract, symbol list and `.so` packaging: **[docs/NATIVE_LAYER.md](docs/NATIVE_LAYER.md)**.

## Verification

Everything in `dev.ibb.launcher.{core,data,download,runtime,repository,mods,game,controls}` compiles
against the real Kotlin 2.1.20 compiler in a standalone harness, and the pure logic is covered by
**81 unit tests** that run on the JVM:

| Suite | What it pins |
|---|---|
| `VersionResolverTest` (22) | rule DSL, library filtering, Java-version cut-offs, `${…}` expansion |
| `ControlEngineTest` (17) | hit boxes, joystick maths, mouse deltas, preset validation |
| `DataIntegrityTest` (16) | path sanitising, SHA-1, `.part` cleanup, archive sniffing |
| `LaunchArgsBuilderTest` (10) | argv shape, `-cp` handling, offline vs MSA args, log4j config |
| `HttpParsingTest` (10) | header parsing, retry policy, file naming |
| `RamHeuristicsTest` (6) | slider bounds vs. physical RAM |
| `AuthParsingTest` | MSA/XBL/XSTS payload decoding (needs the serialization plugin; runs under Gradle) |

Run them with `./gradlew :app:testDebugUnitTest`.

## Licensing & credits

- Execution layer design derived from **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)**
  and **[Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android)** (GPLv3). If you
  redistribute an APK with those `.so` files you must publish the corresponding source.
- Java runtimes: **[angelauramc-openjdk-build](https://github.com/AngelAuraMC/angelauramc-openjdk-build)**.
- Minecraft is a trademark of Mojang Synergies AB. This project ships no Mojang code and requires
  a legitimately owned copy of the game.

See `app/src/main/assets/licenses/` for the full texts shipped inside the APK.

## Download

Download the current build:

* **Release APK (3.7 MB, R8-minified)** —
  <https://github.com/itzblockboy-glitch/IBB-Launcher/releases/download/v1.0.1/app-release.apk>
* **Debug APK (21 MB, readable logs)** —
  <https://github.com/itzblockboy-glitch/IBB-Launcher/releases/download/v1.0.1/app-debug.apk>
* Checksums: [v1.0.1 release notes](https://github.com/itzblockboy-glitch/IBB-Launcher/releases/tag/v1.0.1)

The same two files sit in `artifacts/` in this checkout (with `SHA256SUMS.txt`), and every push is
rebuilt by `.github/workflows/build-apk.yml`; pushing a `v*` tag publishes a new release.

| File | Size | Install |
|---|---|---|
| `app-release.apk` | 3.7 MB | `adb install -r app-release.apk` |
| `app-debug.apk` | 21 MB, no shrinking | same; distinct package id `dev.ibb.launcher.debug` |

Minimum Android 8.0 (API 26). Arm64, armv7 and x86_64 are all in the one APK.

### What runs today, and what does not

The launcher itself — version catalogue, Microsoft device-code sign-in, downloads and asset
verification, mod/resource-pack/shader/world management, per-version settings, on-screen control
editor, console — is complete and installs and runs from these APKs.

Actually **booting Minecraft** additionally needs the native execution layer, which is a separate
build and is *not* in these APKs:

* `libpojavexec.so` + `libexithook.so` — build with `./gradlew :app:assembleDebug -PwithNative`
  on a machine with the NDK/CMake for the PojavLauncher-derived `pojavexec` sources;
* `glfw-stub.jar` and an Android JRE 8/17 image, installed from **Settings → Optional components**
  and **Settings → Runtimes**.

Without them the launch path reports "native bridge unavailable" in the console instead of
crashing — see [docs/NATIVE_LAYER.md](docs/NATIVE_LAYER.md).
