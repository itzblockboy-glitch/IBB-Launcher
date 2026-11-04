# Building IBB Launcher

Everything needed to go from a fresh clone to an installed APK. There is no Android SDK, no
Gradle wrapper distribution and no Maven access inside the chat sandbox where this project was
authored — the steps below are what you run on your own machine.

---

## 1. Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | **17** (21 works too) | AGP 8.9 requires 17+. `JAVA_HOME` must point at it. |
| Android SDK | **Platform 35**, Build-Tools 35.0.0 | `ANDROID_HOME` (or `local.properties` with `sdk.dir=`). |
| Gradle | any 8.11+ | The wrapper (`./gradlew`) downloads the pinned version on first run. |
| NDK | 27.x — **optional** | Only needed for `-PwithNative=true`. |
| Disk | ~8 GB | Gradle caches + build outputs. |

Install the SDK pieces either from Android Studio (SDK Manager) or headless:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

`local.properties` — created automatically by Android Studio, or by hand:

```properties
sdk.dir=/home/you/Android/Sdk
```

## 2. Build the debug APK

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First run downloads Gradle, AGP, Kotlin 2.1.20 and the Compose BOM (≈1.5 GB of caches), so give it
a few minutes. Subsequent builds are incremental.

### Without Android Studio, from a clean machine

```bash
git clone https://github.com/itzblockboy-glitch/IBB-Launcher.git
cd IBB-Launcher
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk
export ANDROID_HOME=$HOME/Android/Sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

## 3. Make it *runnable*: add the native bridge

A launcher-only APK builds fine with no native code, but launching Minecraft requires the JNI
bridge. `docs/NATIVE_LAYER.md` documents the full contract; the short version:

```
app/src/main/jniLibs/arm64-v8a/libpojavexec.so
app/src/main/jniLibs/arm64-v8a/libpojavexec_awt.so
app/src/main/jniLibs/arm64-v8a/libexithook.so
app/src/main/jniLibs/armeabi-v7a/…   (same three)
app/src/main/jniLibs/x86_64/…        (same three)
```

Build them from the PojavLauncher/Amethyst `jni` tree (see the doc), or supply your own
implementation of the same symbols. Renderer shims (`libEGL_angle.so`, `libOSMesa_zink.so`,
`libgl4es.so`, …) go in the same folders and are selected in **Settings → Graphics → Renderer**.

The optional in-repo shim (`libibbexec.so`, used only when you replace the prebuilt bridge) builds
with:

```bash
./gradlew :app:assembleDebug -PwithNative=true
```

This is deliberately opt-in: a plain `assembleDebug` must never require the NDK.

## 4. Release build

Release signing reads `keystore.properties` at the repo root (git-ignored) or the environment:

```properties
storeFile=/absolute/path/to/ibb-release.jks
storePassword=…
keyAlias=ibb
keyPassword=…
```

Environment alternative (CI-friendly): `IBB_KEYSTORE_FILE`, `IBB_KEYSTORE_PASSWORD`,
`IBB_KEY_ALIAS`, `IBB_KEY_PASSWORD`.

```bash
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

R8 runs in full mode. The keep rules that matter are already in `app/proguard-rules.pro`:
`net.kdt.pojavlaunch.utils.**`, `com.oracle.dalvik.**`, `org.lwjgl.glfw.**`, the
`dev.ibb.launcher.game.*bridge*` classes and every `native <methods>` member. **If you add a JNI
class, add a keep rule** — a renamed class fails at runtime with `UnsatisfiedLinkError`.

### Per-ABI splits (smaller downloads)

```bash
./gradlew :app:assembleRelease -PabiSplit=true
```

produces one APK per ABI (~15 MB each instead of a ~30 MB universal build).

## 5. Tests and checks

```bash
./gradlew :app:testDebugUnitTest     # 81 JVM tests, no device needed
./gradlew :app:lintDebug             # non-blocking by design; read the report
./gradlew :app:assembleDebug --scan  # build-scan profile if a build is slow
```

The unit tests cover the pure logic only (version rules, argv construction, control geometry,
path/checksum handling, RAM heuristics, auth payload parsing). Anything touching `Context`,
`SurfaceView` or the native layer needs a device.

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `SDK location not found` | No `ANDROID_HOME` and no `local.properties`. |
| `Unsupported class file major version` | Gradle ran on a JDK older than 17. Check `java -version` **and** `JAVA_HOME`. |
| `Plugin [id: 'com.android.application'] was not found` | Offline mode or a proxy blocking `google()`/`mavenCentral()`. |
| Build hangs at `Downloading…` | Corporate proxy; configure `gradle.properties` proxy settings. |
| `UnsatisfiedLinkError` at launch | Missing/mismatched `.so` for the device ABI — see §3. |
| Game boots, then exits with code 1 | Open **Console**; the launcher maps the common causes (missing runtime, wrong Java version, OOM) to a hint. |
| Black screen after the loading card | Renderer mismatch: switch ANGLE ⇄ Zink in Settings. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | The APK lacks the device's ABI; rebuild with it enabled in `abiFilters`. |

## 7. Where things end up at runtime

```
/storage/emulated/0/Android/data/dev.ibb.launcher/files/minecraft/
├── instances/<version>/      isolated .minecraft (mods, saves, options.txt)
├── assets/                   shared asset objects
├── libraries/                shared maven tree
├── versions/<id>/            version json + client jar
├── logs/                     crash reports + exported console dumps
└── controls/                 custom controller layouts

/data/data/dev.ibb.launcher/
├── files/runtimes/<java>/    OpenJDK (needs the exec bit → internal storage)
├── files/natives/<version>/  extracted natives
└── files/components/         glfw stub / authlib-injector (optional)
```

`IbbPaths` is the only place that knows these paths — see `core/IbbPaths.kt`.
