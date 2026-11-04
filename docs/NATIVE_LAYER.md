# Native layer

The launcher is pure Kotlin. Everything that makes desktop Minecraft run *inside an Android app
process* is native code. This document is the contract between the two halves: the symbols we call,
the JNI classes they belong to, the order they must be called in, and how the `.so` files get into
the APK.

> **Scope.** IBB Launcher ships **no** `.so` files in this repository. The bridge is
> PojavLauncher/Amethyst GPLv3 code; bundling it makes your APK a GPLv3 derivative, so it is left
> as an explicit build step (see §5). Without it the launcher still installs, browses versions,
> manages accounts and provisions runtimes — only "Play" reports a readable error.

## 1. Why a JVM can run in an Android app at all

Android apps run in the ART runtime, not a JVM. Two things make Java Edition possible anyway:

1. **A second, real JVM in-process.** `libpojavexec.so` calls `JLI_Launch` from an Android
   OpenJDK build (compiled for `bionic`), inside a normal `JNI_OnLoad`-loaded library. The JVM
   starts on a dedicated thread and Minecraft's `main` runs there. ART keeps running the launcher
   UI in the same process.
2. **A desktop graphics stack faked convincingly.** Minecraft links against LWJGL 3 → GLFW +
   OpenGL 3.3. On Android we provide an `org.lwjgl.glfw.GLFW` implementation (see §4) that talks to
   `ANativeWindow`/`EGL`, plus an OpenGL translator (ANGLE, Zink, VirGL or GL4ES) that maps GL 3.3
   onto GLES 3.x or Vulkan.

## 2. Classes that are frozen ABI

JNI resolves `native` methods by **mangled class name**. Rename any of these and the call fails at
runtime with `UnsatisfiedLinkError` — usually from a background thread, with a stack trace that
points nowhere near the cause.

| Kotlin class | Purpose | Symbols (from upstream) |
|---|---|---|
| `com.oracle.dalvik.VMLauncher` | starts the JVM, blocks until exit | `Java_com_oracle_dalvik_VMLauncher_launchJVM([Ljava/lang/String;)I` |
| `net.kdt.pojavlaunch.utils.JREUtils` | process & bridge control | `chdir`, `dlopen`, `setLdLibraryPath`, `setupBridgeWindow`, `releaseBridgeWindow`, `initializeHooks`, `setupExitMethod`, `renderAWTScreenFrame` |
| `org.lwjgl.glfw.CallbackBridge` | input, window size, exit requests | `nativeSendKeyEvent`, `nativeMouseButton`, `nativeCursorPos`, `nativeScroll`, `nativeChar`, `nativeSetGrabbing`, `nativeSetInputReady`, `nativeSetWindowSize`, `nativeNotifyLauncher` |
| `org.lwjgl.glfw.GLFW` | GLFW entry points the game links against | `glfwInit`, `glfwTerminate`, `glfwPollEvents`, `glfwSwapBuffers`, `glfwSetInputMode`, `glfwGetKey`, `glfwGetWindowSize`, `glfwSetWindowTitle` |

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
ExecutionLayer.setupExitHandler(context)     System.exit → launcher callback
ExecutionLayer.loadRenderer(nativeLibDir, "libglxshim.so" | "libEGL_angle.so" | …)
GameSurfaceHost → ExecutionLayer.attachSurface(surface)   setupBridgeWindow(ANativeWindow)
com.oracle.dalvik.VMLauncher.launchJVM(argv)  ← blocks on IBB-GameThread
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
| `jrelog: …Failed to create EGL context` | renderer/GPU mismatch — switch ANGLE ⇄ Zink |
| exit code 1 with no stack trace | typically OOM or a mixin failure — read the Console tab, not logcat |
| black surface, game "running" | surface attached after launch, or virtual size ≠ surface size |

## 8. Threading rules

- `launchJVM` **blocks**; it runs on `IBB-GameThread` (max priority) and nothing else may join it.
- `setupBridgeWindow` / `releaseBridgeWindow` are called on the main thread (they touch the
  window), but they only hand over the `ANativeWindow` — no GL work happens there.
- `CallbackBridge` input calls come from the Compose pointer thread; the native side queues them
  into the GLFW event loop.
- `ConsoleStreamer` reads logcat on its own daemon thread and pipes entries into `IbbLog`.
