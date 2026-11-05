# Native layer

The launcher is pure Kotlin. Everything that makes desktop Minecraft run *inside an Android app
process* is native code. This document is the contract between the two halves: the symbols we call,
the JNI classes they belong to, the order they must be called in, and how the `.so` files get into
the APK.

> **Scope.** The bridge is PojavLauncher GPLv3 code, so it is vendored as source
> (`app/src/main/jni/`, see `NOTICE`) and the APK built from this tree is a GPLv3 derivative.
> It **is** compiled into the release APKs — `libpojavexec.so`, `libexithook.so`,
> `libpojavexec_awt.so` are built from source by ndk-build on every CI run, alongside the prebuilt
> renderer/JVM libraries fetched by `tools/fetch_native_libs.sh` (see §5). A build without them
> installs and browses, but "Play" cannot boot a JVM.

## 1. Why a JVM can run in an Android app at all

Android apps run in the ART runtime, not a JVM. Two things make Java Edition possible anyway:

1. **A second, real JVM in-process.** `libpojavexec.so` calls `JLI_Launch` from an Android
   OpenJDK build (compiled for `bionic`), inside a normal `JNI_OnLoad`-loaded library. The JVM
   starts on a dedicated thread and Minecraft's `main` runs there. ART keeps running the launcher
   UI in the same process.
2. **A desktop graphics stack faked convincingly.** Minecraft links against LWJGL 3 → GLFW +
   OpenGL 3.3. On Android we provide an `org.lwjgl.glfw.GLFW` implementation (see §4) that talks to
   `ANativeWindow`/`EGL`, plus an OpenGL translator (gl4es or Zink/OSMesa) that maps GL 3.3
   onto GLES 3.x or Vulkan.

## 2. Classes that are frozen ABI

JNI resolves `native` methods by **mangled class name**. Rename any of these and the call fails at
runtime with `UnsatisfiedLinkError` — usually from a background thread, with a stack trace that
points nowhere near the cause.

| Kotlin class | Purpose | Symbols actually exported by the `.so` |
|---|---|---|
| `com.oracle.dalvik.VMLauncher` | starts the JVM, blocks until exit | `Java_com_oracle_dalvik_VMLauncher_launchJVM` |
| `net.kdt.pojavlaunch.utils.JREUtils` | process & bridge control | `chdir`, `dlopen`, `executeBinary`, `setLdLibraryPath`, `setupBridgeWindow`, `releaseBridgeWindow`, `initializeHooks`, `setupExitMethod`, `renderAWTScreenFrame` |
| `org.lwjgl.glfw.CallbackBridge` | input, window size, clipboard | `RegisterNatives` in `JNI_OnLoad` supplies `nativeSendChar`, `nativeSendCharMods`, `nativeSendKey`, `nativeSendCursorPos`, `nativeSendMouseButton`, `nativeSendScroll`, `nativeSendScreenSize`, `nativeSetUseInputStackQueue`; direct exports: `nativeSetInputReady`, `nativeSetGrabbing`, `nativeClipboard`, `nativeSetWindowAttrib`, `nativeCreateGamepad{Button,Axis}Buffer` |
| `org.lwjgl.glfw.GLFW` | GLFW entry points the game links against | `nglfwSet*Callback`, `nglfwGetCursorPos`, `internalGetGamepadDataPointer` |
| `net.kdt.pojavlaunch.Logger` | the game JVM's stdout/stderr | `begin`, `appendToLog`, `setLogListener` |
| `net.kdt.pojavlaunch.MainActivity` | AWT/Cacio bridge only | `net.java.openjdk.cacio.ctc.CTCDesktopPeer_openUri/openFile`, `CTCClipboard_nQuerySystemClipboard/nPutClipboardData` call these statics |
| `net.kdt.pojavlaunch.CriticalNativeTest` | probe for `@CriticalNative` support | `testCriticalNative(II)V` registered then unregistered during `JNI_OnLoad` |

Two of these are easy to get wrong, and both were bugs in this tree:

* `Logger` may not declare both `begin(File, listener)` and `external fun begin(String)` — a JVM can
  only tell them apart by arity, and the native side binds the one-argument form. Setup therefore
  lives in `Logger.start(logFile, listener)`.
* `CallbackBridge.nativeSendKey` is bound by `RegisterNatives`, *not* by its mangled name, and the
  registration replaces whatever the class declared. The declarations therefore carry no
  `@CriticalNative` annotation: the native side probes the device and registers either the critical
  (no `JNIEnv`/`jclass`) or the regular variant.

The Kotlin declarations carry `@JvmStatic`; on an `object` that produces a **static** native
method, which is what the C side expects (`JNIEnv *env, jclass clazz, …`).

`proguard-rules.pro` keeps all of the above, plus every `native <methods>` member in the app. Add a
rule when you add a JNI class:

```proguard
-keep class your.package.NativeThing { *; }
```

## 3. Bring-up order (load-bearing)

```
JREUtils.loadNativeLibraries()
  ├─ System.loadLibrary("exithook")      ← must be first: replaces the JVM's exit handler
  └─ System.loadLibrary("pojavexec")     ← EGL bridge, dlopen hook, input bridge, GOT patches
     System.loadLibrary("pojavexec_awt") ← optional: only old Forge / LWJGL2 AWT screens

ExecutionLayer.setLibraryPath(runtimeHome)   LD_LIBRARY_PATH = lib:lib/server:lib/jli
ExecutionLayer.preloadJvm(runtimeHome)       dlopen libjli, libjvm, libjava, libnet, libnio, libzip …
ExecutionLayer.chdir(instanceDir)            the JVM resolves java.home relative to the CWD
ExecutionLayer.initializeHooks()             signal handlers, /proc/self/maps shims, stdio
ExecutionLayer.loadRenderer(nativeLibDir, "libgl4es_114.so" | "libOSMesa.so")
GameLogSink.attach(versionDir/logs/latest.log)  Logger.begin + listener, before stdio is redirected
ExecutionLayer.attachSurface(surface)        setupBridgeWindow(ANativeWindow)
ExecutionLayer.setupExitHandler(context)     System.exit → launcher callback
com.oracle.dalvik.VMLauncher.launchJVM(argv)  ← blocks on IBB-GameThread

The environment is exported *before* the renderer loads, because the game JVM is created by
`JLI_Launch` and inherits this process's environment:
POJAV_ENVIRON (the shared struct pointer), POJAV_NATIVEDIR, LD_LIBRARY_PATH, POJAV_RENDERER,
LIBGL_NAME, LIBGL_ES, FORCE_VSYNC, MESA_GLSL_CACHE_DIR and the Zink/Turnip overrides.

`POJAV_ENVIRON` is the subtle one. `environ.c` has an `__attribute__((constructor))` that either
creates the struct and publishes its **address as a hex string**, or re-attaches to the address it
finds there. It runs again when the *game JVM* opens `libpojavexec.so` for `GLFW`'s static
initialiser; if the variable were missing the second run would allocate a second struct and the two
sides would silently disagree about the surface, the input buffers and the renderer. That is why the
launcher must not clear the environment, and why `NativeLibraryPath.prepare()` exports
`POJAV_NATIVEDIR` rather than reloading the library under a different path.
```
```

Why each step exists:

- **`exithook` first** — if the JVM starts before the hook is installed, an early `System.exit`
  takes the whole launcher down with no chance to show a message.
- **`chdir` before launch** — Android's process CWD is `/`, which is not writable and not where
  `-Djava.home` points; the runtime fails to find its own modules without this.
- **`setLdLibraryPath` before `dlopen`** — `libjli.so` pulls in `libjvm.so` by soname.
- **surface before launch, not after** — the GLFW stub needs a valid `ANativeWindow` when the game
  creates its window; attaching later leaves a black screen even though the game is "running".

## 4. The GLFW/EGL shim

`org.lwjgl.glfw.GLFW` in this repo is a thin Kotlin facade over the native GLFW that ships inside
`libpojavexec.so` (or `libglfw_stub.so`, depending on the upstream build). Two consequences for
Java-side code:

- LWJGL must be told **not** to load its own desktop GL:

  ```
  -Dorg.lwjgl.opengl.libname=<renderer shim>   e.g. libglxshim.so (Zink+Kopper) / libEGL_angle.so
  -Dorg.lwjgl.librarypath=<natives dir>
  -Dglfwstub.windowWidth=1280
  -Dglfwstub.windowHeight=720
  -Dglfwstub.initEgl=false
  ```

  These are emitted by `LaunchArgsBuilder`, so a unit test fails if they are dropped.

- `CallbackBridge.updateWindowSize` must be called on every `surfaceChanged`; GLFW does not get
  Android configuration callbacks on its own, and a stale size makes the pointer coordinates drift.

## 5. Packaging the `.so` files

```
app/src/main/jniLibs/
├── arm64-v8a/
│   ├── libpojavexec.so
│   ├── libpojavexec_awt.so     (optional)
│   ├── libexithook.so
│   └── libglxshim.so           (renderer shims — name must match Settings → Renderer)
├── armeabi-v7a/  (same set)
└── x86_64/       (same set; emulator / Chromebooks)
```

Gradle packages `jniLibs` verbatim; **do not** let AGP strip them (`packaging { jniLibs { keepDebugSymbols } }`
is unnecessary — stripping happens only for libraries built by the NDK in this project).

To build the bridge from upstream sources:

```bash
git clone https://github.com/AngelAuraMC/Amethyst-Android
cd Amethyst-Android
# Build only the JNI module; the exact task depends on the upstream AGP version:
./gradlew :app_pojavlauncher:externalNativeBuildRelease   # or use the NDK directly:
$ANDROID_NDK/ndk-build -C app_pojavlauncher/src/main/jni APP_ABI="arm64-v8a armeabi-v7a x86_64"
```

Then copy `libs/<abi>/lib*.so` into `app/src/main/jniLibs/<abi>/`. Keep the upstream `LICENSE`
(GPLv3) and publish your modified sources if you distribute.

### The optional in-repo shim

`app/src/main/cpp/ibbexec.c` is a template for replacing the prebuilt bridge with your own minimal
implementation. It builds only with `-PwithNative=true` and outputs to
`app/src/main/jniLibs/<abi>/libibbexec.so`:

```bash
./gradlew :app:assembleDebug -PwithNative=true
```

It intentionally does **not** implement the full JLI/EGL contract — it exists so the CMake wiring,
ABI filters and packaging can be exercised without the upstream tree.

## 6. Java runtimes

| What | Where |
|---|---|
| Download | `https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre{JAVA}/jre{JAVA}-android-{ARCH}.tar.xz` |
| Layout | `<data>/files/runtimes/<component>/{bin/java, bin/java.orig, lib, release}` |
| Internal storage | required: some devices mount external storage `noexec`, so `bin/java` would not be executable |
| Selection | ≤1.16.5 → 8 · 1.17–1.20.4 → 17 · 1.20.5+ → 21 (honours the version's own `javaVersion`) |
| `JAVA` values | 8 · 17 · 21 · 25 |
| `ARCH` values | `arm64` · `arm` · `x86` · `x86_64` (jre25 has no `x86` build) |

`RuntimeManager` extracts with `XZInputStream` + a tar reader (no `tar` binary on Android), restores
the exec bit, and renames `bin/java` → `bin/java.orig` so the launcher can verify integrity later.

## 7. Debugging a launch

```bash
adb logcat -s IBB:* jrelog:* AndroidRuntime:E DEBUG:* libc:*
```

| Signal | Meaning |
|---|---|
| `JREUtils: Native bridge unavailable` | `.so` missing for this ABI — check `adb shell ls $APP/lib` |
| `UnsatisfiedLinkError: No implementation found for …VMLauncher…` | class was renamed or ProGuard stripped it |
| `ExecutionLayer: Could not load libjvm.so` | runtime incomplete — delete it in Settings, reinstall |
| `jrelog: …Failed to create EGL context` | renderer/GPU mismatch — switch GL4ES ⇄ Zink |
| exit code 1 with no stack trace | typically OOM or a mixin failure — read Settings → Console, not logcat |
| black surface, game "running" | surface attached after launch, or virtual size ≠ surface size |

## 8. Threading rules

- `launchJVM` **blocks**; it runs on `IBB-GameThread` (max priority) and nothing else may join it.
- `setupBridgeWindow` / `releaseBridgeWindow` are called on the main thread (they touch the
  window), but they only hand over the `ANativeWindow` — no GL work happens there.
- `CallbackBridge` input calls come from the Compose pointer thread; the native side queues them
  into the GLFW event loop.
- `ConsoleStreamer` reads logcat on its own daemon thread and pipes entries into `IbbLog`.

## 9. How the `.so` files get into the APK

| Library | Where it comes from |
|---|---|
| `libpojavexec.so`, `libexithook.so`, `libpojavexec_awt.so`, `liblinkerhook.so`, `libawt_*.so` | compiled from `app/src/main/jni/` by ndk-build (`android.ndkVersion` in `app/build.gradle.kts`) |
| `libgl4es_114.so`, `libOSMesa.so`, `liblwjgl*.so`, `libopenal.so` | `tools/fetch_native_libs.sh`, pinned to PojavLauncher `b12ad04`; `app/src/main/jniLibs/` is gitignored |
| `glfw-stub.jar` | vendored in `app/src/main/assets/components/lwjgl3/`, extracted at runtime |

`app/src/main/assets/components/lwjgl3/glfw-stub.jar` **must stay first on the game's classpath**:
it is the real LWJGL/GLFW implementation for Android, and `org.lwjgl.glfw.GLFW` in this source tree is
only the launcher-side stub used while the jar is not loaded.
