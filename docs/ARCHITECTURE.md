# Architecture

How IBB Launcher is put together and — more usefully — *why*, so the next change does not undo a
decision that exists for a reason.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ UI (Compose)                                                                 │
│  MainActivity → AppShell → Home / Versions / Accounts / Skins / Files /      │
│                             Console / Settings                               │
│  GameActivity → GameSurfaceHost + VirtualController + GameToolbar            │
└───────────────┬──────────────────────────────────────────────────────────────┘
                │  plain Kotlin calls (no DI framework, see §7)
┌───────────────▼──────────────────────────────────────────────────────────────┐
│ Application services (IbbApplication locator)                                │
│  SettingsStore  AccountStore  SecureStore  MojangApi  MicrosoftAuth          │
│  RuntimeManager  VersionInstaller  LoaderRepository  ControlRepository       │
└───────────────┬──────────────────────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────────────────────┐
│ Game pipeline                                                                │
│  VersionResolver → LaunchArgsBuilder → GameLauncher                          │
│      (pure logic)                        │                                   │
│                                          ├─ ExecutionLayer (JNI bring-up)    │
│                                          ├─ ConsoleStreamer (logcat)         │
│                                          └─ GameService (foreground)         │
└───────────────┬──────────────────────────────────────────────────────────────┘
                │  com.oracle.dalvik.VMLauncher.launchJVM(argv)
┌───────────────▼──────────────────────────────────────────────────────────────┐
│ Native: libpojavexec.so (JLI_Launch, EGL bridge, GLFW, input)                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 1. Layers and their rules

| Layer | May depend on | Must not |
|---|---|---|
| `core`, `data/model` | nothing Android-ish except `Context` for paths | touch UI or networking |
| `data/network`, `data/auth`, `download` | core, model, `Http` | block the main thread |
| `data/store` | core, model, Android Keystore | expose plaintext secrets |
| `runtime`, `repository`, `mods` | core, model, network, download | assume a specific device |
| `game` | everything above | touch Compose |
| `ui` | everything | do IO on the main thread |
| `net.kdt…`, `com.oracle.dalvik`, `org.lwjgl…` | nothing of ours | be renamed (JNI ABI) |

`game/VersionResolver`, `game/LaunchArgsBuilder`, `controls/ControlEngine` and
`mods/ModRepository` are deliberately **Android-free** so they can be unit-tested on the JVM.
That is also why they take `File`/`String` instead of `Context` where possible.

## 2. Install pipeline (`VersionInstaller`)

```
version_manifest_v2.json
  └─ VersionSummary(id, type, url)
       └─ version/{id}.json → VersionDetail
            ├─ libraries[]   → libraries/<maven path>       (parallel, SHA-1, resumed)
            ├─ downloads.client → versions/<id>/<id>.jar
            ├─ logging.client   → log4j-rce-patch.xml
            ├─ assetIndex       → assets/indexes/<id>.json
            │     └─ objects    → assets/objects/<h[0:2]>/<hash>
            └─ loader profile   → versions/<loader>-<id>/<loader>-<id>.json
```

Order matters and is encoded in `VersionInstaller.install`:

1. **libraries** — the biggest download; runs first so a failure happens before we spend bandwidth
   on assets.
2. **client jar + log4j config** — small but mandatory for the classpath.
3. **asset index, then objects** — deduplicated across versions (`assets/objects` is shared).
4. **loader profile** — Fabric/Quilt metadata is a tiny JSON; Forge/NeoForge need their installer
   jar's `install_profile.json` merged.
5. **`launcher_profiles.json`** — written last so a half-finished install is never advertised as
   complete in the UI.

`Stage` is a sealed hierarchy (`Downloading`, `Extracting`, `InstallingLoader`, `Done`, `Failed`)
exposed as a `StateFlow`, so the version card can show real progress without polling.

### Why assets are shared but instances are not

Assets and libraries are identical across every version and dominate disk usage; keeping one copy
saves gigabytes. Saves, configs and mods must be isolated or two versions corrupt each other's
worlds — hence `instances/<version>/`.

## 3. Launch pipeline (`GameLauncher`)

```
1. preflight   account usable? version json present? runtime ready? → readable failures
2. resolve     flatten inheritsFrom chain, filter libraries, compute classpath
3. args        LaunchArgsBuilder (pure, unit-tested)
4. native      ExecutionLayer: LD_LIBRARY_PATH → dlopen JVM libs → renderer → hooks
5. surface     SurfaceView created → JREUtils.setupBridgeWindow(Surface)
6. execute     com.oracle.dalvik.VMLauncher.launchJVM(argv)  [blocks, IBB-GameThread]
7. teardown    non-zero exit → recovery hint + console tail
```

`LaunchState` is a `StateFlow` of `Idle → Preparing(step) → Running → Exited/Failed`, which is
exactly what the loading card renders. Steps are strings because they are shown verbatim —
"Extracting natives (2/6)" is more useful than a spinner.

**One JVM per process.** `JLI_Launch` cannot be restarted in the same process, so a failed launch
returns to the launcher and offers a *new session* rather than a retry. `GameService` is a silent
`specialUse` foreground service whose only job is to keep the process alive when the user
switches apps.

### Preflight beats crashing

Every check that can fail is done *before* the JVM starts, because a failure inside the JVM shows
up as a stack trace in a language most users cannot act on. `GameLauncher.recoveryHint` maps the
remaining cases (OOM, `UnsatisfiedLinkError`, missing `Main`, wrong Java version, a mod that fails
to mixin) to a concrete next step.

## 4. Rendering and input

The game owns its own EGL context on its own thread. `GameSurfaceHost` therefore uses a plain
`SurfaceView` — **not** `GLSurfaceView`, which would create a competing context — and the Compose
overlay is drawn in a separate window layer on top.

```
Touch → VirtualController (Compose, hit-testing in normalised coords)
      → ControlEngine (pure geometry: key sets, joystick vector, mouse delta)
      → org.lwjgl.glfw.CallbackBridge / GLFW (JNI)
      → native input bridge → GLFW callbacks → Minecraft
```

Rationale for a separate `GameActivity`: `sensorLandscape`, `turnScreenOn`, a black window
background and a fullscreen theme must not leak onto the launcher UI, and rotation must not
recreate the composable tree that owns the JVM handle. The launcher's own state (tab, scroll
positions) survives untouched behind it.

## 5. Data flow conventions

- **No blocking on the main thread.** Network, disk and archive work run on `Dispatchers.IO`;
  Compose observes `StateFlow`s.
- **Everything long-lived is a `StateFlow`**, never a callback chain — it survives recomposition
  and configuration changes.
- **Errors are values.** `MicrosoftAuth.Result`, `VersionInstaller.Result`, `LaunchState` are
  sealed types carrying human-readable reasons, so the UI never has to interpret an exception.
- **Logs are the source of truth.** `IbbLog` keeps a 4000-entry ring buffer plus a replay window,
  so opening the console mid-crash still shows the launch sequence.

## 6. Security

| Surface | Handling |
|---|---|
| Microsoft tokens | Encrypted with AES-256-GCM, key in the Android Keystore (`SecureStore`) |
| Passwords | **Never** handled — device-code flow only |
| Downloads | SHA-1 verified against Mojang metadata; `.part` files resumed, never trusted blindly |
| Log4j | `-Dlog4j2.formatMsgNoLookups=true` + the version's own patched config |
| File imports | SAF only, no `MANAGE_EXTERNAL_STORAGE`; deletes are confined to the instance dir |
| Backups | `allowBackup` disabled for token material (`data_extraction_rules.xml`) |

## 7. Dependency wiring

A hand-rolled locator in `IbbApplication` instead of Hilt:

- the graph is ~10 objects, created once, with no scopes;
- Hilt adds ~1.3 MB to the APK and a KSP compiler pass to every build;
- every consumer takes its collaborators as constructor parameters, so the code stays testable
  (the pure layers are already unit-tested with hand-built instances).

If the graph ever grows past ~20 nodes, migrate to Hilt — the constructors are already shaped for
it.

## 8. Performance budget

| Path | Target | How |
|---|---|---|
| Cold start → first frame | < 500 ms | splash keeps the system window until the first composition; no network on the main thread |
| Tab switch | < 200 ms | `AnimatedContent` with 220 ms fades, keyed content, no `NavHost` churn |
| Game overlay | 60/120 Hz | overlay is a separate window layer; hit boxes precomputed per layout change; no allocation in `onDraw` |
| Downloads | bandwidth-bound | 4–6 parallel connections, resumed `.part` files |
| APK size | ~18 MB + runtimes | no OkHttp/Coil/WebKit, no bundled JRE |

## 9. Testing strategy

Pure logic is unit-tested (81 tests) because that is where the expensive bugs live: a wrong rule
silently drops a library, a wrong argv element breaks the launch, a wrong joystick formula makes
the player walk at half speed. The Android-facing layers are thin by design and verified on a
device.
