package dev.ibb.launcher.ui.game

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.model.Account
import dev.ibb.launcher.data.model.ControlLayout
import dev.ibb.launcher.data.model.LauncherSettings
import dev.ibb.launcher.data.model.VersionSummary
import dev.ibb.launcher.game.ConsoleStreamer
import dev.ibb.launcher.game.GameLauncher
import dev.ibb.launcher.game.GameService
import dev.ibb.launcher.game.nativebridge.ExecutionLayer
import org.lwjgl.glfw.CallbackBridge
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.clickableNoRipple
import dev.ibb.launcher.ui.components.GlassIconButton
import dev.ibb.launcher.ui.game.overlay.GameToolbar
import dev.ibb.launcher.ui.game.overlay.VirtualController
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hosts the running game.
 *
 * ============================================================================================
 * Why a `SurfaceView` and not `GLSurfaceView`
 * ============================================================================================
 * The JVM (via LWJGL + our EGL bridge) owns the GL context and the render loop. A
 * `GLSurfaceView` would create *its own* context on *its own* thread and fight the JVM's. So we
 * hand the raw `Surface` to the native bridge ([net.kdt.pojavlaunch.utils.JREUtils.setupBridgeWindow])
 * and never render from the Kotlin side.
 *
 * The Compose overlay is drawn in a separate window layer above the surface, so the on-screen
 * controller and console do **not** force the game surface into software composition — that is
 * the single biggest cause of launcher-induced frame drops in naive ports.
 *
 * Lifecycle:
 *  * `surfaceCreated` → attach → launch the JVM once (guarded by [launched]).
 *  * `surfaceDestroyed` → detach; the game keeps running (audio + tick) and re-attaches when the
 *    user returns; a real stop only happens via the toolbar's exit button.
 */
@Composable
fun GameSurfaceHost(
    session: GameSession,
    app: IbbApplication,
    onExit: () -> Unit,
) {
    // NOTE: the launch coroutine must NOT use rememberCoroutineScope(): that scope is cancelled
    // when this composable leaves the tree (e.g. the user rotates the device), which would abort
    // the in-process JVM boot we already started. A process-wide scope keeps the session alive and
    // lets us re-attach the surface afterwards.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    var settings by remember { mutableStateOf(session.settings) }
    var showConsole by remember { mutableStateOf(false) }
    var showLogPeek by remember { mutableStateOf(false) }
    var showToolbar by remember { mutableStateOf(true) }
    // The session sheet (control layout + exit) is always reachable, even with the console
    // overlay disabled — otherwise there would be no way to quit besides the back gesture.
    var layout by remember { mutableStateOf(app.controlRepository.selected) }
    var status by remember { mutableStateOf<GameLauncher.LaunchState>(GameLauncher.LaunchState.Preparing("Waiting for the display")) }
    var surfaceSize by remember { mutableStateOf(IntArray(2)) }
    // Set by surfaceCreated. The JVM must not boot before EGL has a window, otherwise the game
    // renders into nothing and Minecraft closes during its own start-up.
    var pendingSurface by remember { mutableStateOf<android.view.Surface?>(null) }

    val launcher = remember { GameLauncher(app, app.versionInstaller, app.runtimeManager) }
    val gameContext = androidx.compose.ui.platform.LocalContext.current
    val streamer = remember { ConsoleStreamer() }
    var launched by remember { mutableStateOf(false) }

    // ---- Clipboard bridge (the native layer calls back into CallbackBridge) ------------------
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    DisposableEffect(clipboard) {
        CallbackBridge.clipboardHandler = { action, copySrc ->
            when (action) {
                CallbackBridge.CLIPBOARD_COPY -> {
                    copySrc?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }
                    copySrc
                }
                CallbackBridge.CLIPBOARD_PASTE -> clipboard.getText()?.text
                else -> null
            }
        }
        onDispose { CallbackBridge.clipboardHandler = null }
    }

    DisposableEffect(Unit) {
        streamer.startLogcat()
        onDispose {
            streamer.stop()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    // Start the session exactly once, as soon as the output surface exists. Booting the JVM with
    // no window is the classic "Minecraft opens and immediately closes" failure, so the launch is
    // gated on `pendingSurface`.
    LaunchedEffect(session.versionId, session.account.id, pendingSurface) {
        if (launched || pendingSurface == null) return@LaunchedEffect
        launched = true
        val width = surfaceSize.getOrNull(0)?.takeIf { it > 0 } ?: app.resources.displayMetrics.widthPixels
        val height = surfaceSize.getOrNull(1)?.takeIf { it > 0 } ?: app.resources.displayMetrics.heightPixels
        status = GameLauncher.LaunchState.Preparing("Starting Java")
        val state = launcher.launch(
            GameLauncher.Request(
                account = session.account,
                versionId = session.versionId,
                settings = settings,
                surface = pendingSurface,
                surfaceWidth = width,
                surfaceHeight = height,
                versionSummary = session.summary,
            ),
        )
        status = state
    }

    LaunchedEffect(launcher) {
        launcher.state.collect { state ->
            status = state
            when (state) {
                is GameLauncher.LaunchState.Running -> GameService.start(gameContext, session.versionId)
                is GameLauncher.LaunchState.Exited, is GameLauncher.LaunchState.Failed ->
                    GameService.stop(gameContext)
                else -> Unit
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Leaving the screen without an explicit exit: keep the JVM booting in the background.
            IbbLog.i("GameSurfaceHost", "Game screen disposed; session continues")
        }
    }

    // Back gesture: hide the overlay first, then ask the game to save & quit.
    androidx.activity.compose.BackHandler(enabled = showConsole) { showConsole = false }
    androidx.activity.compose.BackHandler(enabled = !showConsole) {
        org.lwjgl.glfw.CallbackBridge.requestExit()
        onExit()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.setKeepScreenOn(settings.keepScreenOn)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surfaceSize = intArrayOf(holder.surfaceFrame.width(), holder.surfaceFrame.height())
                            if (launched) {
                                // Rotation / return from background while the game is alive: only
                                // the window needs to be re-attached.
                                ExecutionLayer.attachSurface(holder.surface)
                            } else {
                                // First frame: the launch coroutine waits on this, then does the
                                // full native bring-up including attachSurface().
                                pendingSurface = holder.surface
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            surfaceSize = intArrayOf(width, height)
                            val scale = settings.graphics.scaleFactor.coerceIn(0.5f, 2f)
                            CallbackBridge.updateWindowSize(
                                physicalW = width,
                                physicalH = height,
                                virtualW = (width / scale).toInt(),
                                virtualH = (height / scale).toInt(),
                            )
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (launched) ExecutionLayer.detachSurface()
                        }
                    })
                }
            },
        )

        // --- Virtual controller (touch) -----------------------------------------------------
        if (status is GameLauncher.LaunchState.Running || status is GameLauncher.LaunchState.Preparing) {
            VirtualController(
                layout = layout,
                settings = app.settingsStore.inputSettings(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // --- Loading / error overlay ---------------------------------------------------------
        AnimatedVisibility(
            visible = status !is GameLauncher.LaunchState.Running,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            LaunchStatusCard(state = status, onExit = onExit, onOpenConsole = { showConsole = true })
        }

        // --- Toolbar ------------------------------------------------------------------------
        AnimatedVisibility(
            visible = showToolbar,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it },
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Session sheet: always reachable (control layout, exit, quick log peek).
                GlassIconButton(
                    icon = Icons.Rounded.Tune,
                    contentDescription = "Session settings and control layout",
                    onClick = { showConsole = true },
                    size = 40.dp,
                )
                GlassIconButton(
                    icon = Icons.Rounded.Fullscreen,
                    contentDescription = "Toggle toolbar",
                    onClick = { showToolbar = false },
                    size = 40.dp,
                )
                GlassIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Exit game",
                    tint = Color(0xFFFF8FA3),
                    onClick = {
                            org.lwjgl.glfw.CallbackBridge.requestExit()
                            onExit()
            },
                    size = 40.dp,
                )
            }
        }

        // Tap the top edge to bring the toolbar back.
        if (!showToolbar) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(width = 56.dp, height = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .clickableNoRipple { showToolbar = true },
            )
        }

        // --- Live log peek (honours Settings → Play behaviour → console overlay) ------------
        AnimatedVisibility(
            visible = showLogPeek && settings.showConsoleOverlay,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .fillMaxWidth(0.62f)
                .height(112.dp),
        ) {
            dev.ibb.launcher.ui.components.GlassCard(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                accent = Cyan,
                accentStrength = 0.08f,
            ) {
                val entries by IbbLog.history.collectAsState()
                Text(
                    text = entries.takeLast(4).joinToString("\n") { it.level.short + " " + it.message.take(80) },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = dev.ibb.launcher.ui.theme.ConsoleFamily,
                        fontSize = 9.sp,
                    ),
                    color = Porcelain.copy(alpha = 0.72f),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // A small edge handle toggles the log peek without stealing surface touches.
        if (settings.showConsoleOverlay) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 6.dp)
                    .size(width = 44.dp, height = 26.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .clickableNoRipple { showLogPeek = !showLogPeek },
            ) {
                Text(
                    text = "LOG",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = Porcelain.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // --- Console overlay ----------------------------------------------------------------
        AnimatedVisibility(
            visible = showConsole,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150)),
        ) {
            GameToolbar(
                onDismiss = { showConsole = false },
                layout = layout,
                onLayoutChange = {
                    layout = it
                    app.controlRepository.save(it)
        },
                onExitGame = {
                    org.lwjgl.glfw.CallbackBridge.requestExit()
                    onExit()
        },
            )
        }
    }
}

@Composable
private fun LaunchStatusCard(
    state: GameLauncher.LaunchState,
    onExit: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val (title, detail, isError) = when (state) {
        is GameLauncher.LaunchState.Preparing -> Triple(state.step, "Building the launch environment…", false)
        is GameLauncher.LaunchState.Running -> Triple("Running", "The game window is live", false)
        is GameLauncher.LaunchState.Exited -> Triple(
            if (state.exitCode == 0) "Session ended" else "The game stopped (exit ${state.exitCode})",
            "Tap the console button for the full log.",
            state.exitCode != 0,
        )
        is GameLauncher.LaunchState.Failed -> Triple(state.reason, state.hint, true)
        GameLauncher.LaunchState.Idle -> Triple("Preparing", "Starting the runtime…", false)
    }
    GlassCard(
        modifier = Modifier.padding(24.dp),
        accent = if (isError) Color(0xFFFF5C7A) else Violet,
        accentStrength = 0.10f,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isError) Color(0xFFFF8FA3) else Porcelain,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = Porcelain.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isError) {
                    dev.ibb.launcher.ui.components.IbbButton(
                            text = "Open console",
                            onClick = onOpenConsole,
                            style = dev.ibb.launcher.ui.components.IbbButtonStyle.SECONDARY,
                    )
                }
                dev.ibb.launcher.ui.components.IbbButton(
                    text = "Back to launcher",
                    onClick = onExit,
                    style = dev.ibb.launcher.ui.components.IbbButtonStyle.GHOST,
                )
            }
        }
    }
}

/**
 * Everything needed to start one session; produced by the launcher UI.
 * Top-level (not nested in [GameSurfaceHost]) so screens can create it without referencing a
 * composable's scope.
 */
data class GameSession(
    val account: Account,
    val versionId: String,
    val settings: LauncherSettings,
    val summary: VersionSummary? = null,
    val controlLayout: ControlLayout? = null,
)
