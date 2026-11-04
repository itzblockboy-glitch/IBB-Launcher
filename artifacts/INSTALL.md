# IBB Launcher 1.0.0 — installing the APK

Package: `dev.ibb.launcher` (debug build installs as `dev.ibb.launcher.debug`, so both can coexist)
Minimum Android: 8.0 (API 26) · Architectures: arm64-v8a, armeabi-v7a, x86_64 · Signed: APK v2

| File | Size | Notes |
|---|---|---|
| `IBB-Launcher-1.0.0-release.apk` | 3.7 MB | R8-minified, debug-signed (fine to install) |
| `IBB-Launcher-1.0.0-debug.apk` | 21 MB | no shrinking, readable stack traces |

Verify: `sha256sum -c SHA256SUMS.txt`

Install over USB:      `adb install -r IBB-Launcher-1.0.0-release.apk`
Install on the device: copy the file over, tap it, allow "install unknown apps" for that app.

If Android refuses the install it is almost always (a) an older build with the same package id —
uninstall first, or (b) a Play Protect prompt on a self-signed APK — tap "install anyway".

## What works right after installing
Version catalogue (vanilla / Fabric / Forge / NeoForge / Quilt), Microsoft device-code sign-in,
downloads with checksum + asset verification, mods / resource packs / shaders / worlds, per-version
settings, the on-screen control editor, console and log export.

## What still needs one more build
Booting Minecraft needs the native execution layer, which these APKs do not contain:
`libpojavexec.so` + `libexithook.so` (build with `-PwithNative` using the NDK), plus `glfw-stub.jar`
and an Android JRE installed from Settings → Optional components / Runtimes. Launching without
them fails gracefully with a console message rather than crashing.
