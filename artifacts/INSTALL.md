# IBB Launcher 1.2.0 — playable build

Package `dev.ibb.launcher` · Minimum Android 8.0 (API 26) · targetSdk 35
Architectures: arm64-v8a, armeabi-v7a, x86_64 · Signed: APK Signature Scheme v2

| File | Size | Notes |
|---|---|---|
| `app-release.apk` | 67 MB | R8-minified; includes the native execution layer |
| `app-debug.apk` | 85 MB | Unminified; readable crash logs |

Download: <https://github.com/itzblockboy-glitch/IBB-Launcher/releases/tag/v1.2.0>

The APKs themselves are **not** committed to this repository (67–85 MB each); the release assets
are the distribution channel.

Install with `adb install -r app-release.apk`, or copy it to the phone and tap it
(allow "install unknown apps" for the file manager).

## First run — what to expect

1. **Account.** Tap *Add* on Home. Sign in with Microsoft (device-code flow: the launcher shows a
   `microsoft.com/link` URL and a code to type) or create an offline profile for single player.
2. **Version.** Home shows the newest release. Tap *Change* to install or pick another one.
3. **Java.** The first Play tap downloads the matching runtime (~85 MB) and then the game files
   (libraries + assets, several hundred MB). Use Wi-Fi. Progress is on the Home card and in
   **Settings → Console**.
4. **Play.** The game renders into the launcher's own surface, with the on-screen controller over
   it: move stick, look drag, **Attack** (left mouse — hold it to keep mining), **Use** (right
   mouse — place, eat, open), jump, sneak, inventory, drop, hotbar scroll, chat and menu.

Everything is downloaded from the official Mojang and OpenJDK-Android endpoints; nothing is
bundled that would make the APK unreasonably large.

## If a launch fails

Open **Settings → Console**. The log includes the exact JVM command line, the native bring-up
steps (`LD_LIBRARY_PATH`, renderer load, surface attach) and the game's own output. The most
common causes and fixes:

| Symptom | Fix |
|---|---|
| Stops at "Starting Java" | Java runtime missing → Home → Play re-downloads it; or **Settings → Runtimes**. |
| Black screen, no menu | Renderer unsupported → **Settings → Graphics → OpenGL ES 2**, then relaunch. |
| Crashes with a GL/shader error | Same as above; Vulkan (Zink) needs a Vulkan 1.1 driver. |
| OutOfMemory | Lower allocated RAM in **Settings → Performance**. |
| Older/modded version will not start | Install Java 8 for ≤1.16.5, Java 17 for 1.17–1.20.4, Java 21 for 1.20.5+. |

## What is actually in this build

* Minecraft Java Edition support (1.7.10 → current), vanilla and Fabric/Forge/NeoForge/Quilt.
* A real in-process JVM (`libpojavexec.so` + `libexithook.so`, compiled from the
  PojavLauncher-derived bridge sources in `app/src/main/jni`).
* The GLFW/LWJGL shim (`glfw-stub.jar`) and the OpenGL translation layer (gl4es, OSMesa/Zink).
* Microsoft + offline accounts, downloads with SHA-1 verification, mods, resource packs, shaders,
  worlds, crash reporting, and a console that shows the real game log.
