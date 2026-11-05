package dev.ibb.launcher.game

import android.content.Context
import dev.ibb.launcher.BuildConfig
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optional runtime components that live *outside* the APK.
 *
 * Why not `assets/`: an `.so` or a `.jar` in assets is stored uncompressed-or-not at the mercy of
 * `aaptOptions`, cannot be executed from there, and every update ships a new copy. Downloading on
 * demand keeps the APK small (the requirement for this project) and lets a component be replaced
 * without a launcher release.
 *
 * Current components:
 *  * **glfw-stub.jar** — the `org.lwjgl.glfw` shim that redirects LWJGL to our EGL bridge. LWJGL
 *    itself is a desktop jar; on Android the game needs this shim *ahead of* the real LWJGL on the
 *    classpath. `GameLauncher` picks up any `glfw-stub*.jar` in `components/lwjgl3/`.
 *  * **authlib-injector.jar** — optional, only for third-party ("authlib") servers. Never used for
 *    Microsoft accounts.
 *
 * Both are resolved through [describe] so the UI can show whether a component is present and what
 * it is for; a missing optional component must never block a launch.
 */
object ComponentManager {

    private const val TAG = "Components"

    /** A downloadable component and its on-disk location. */
    data class Component(
        val id: String,
        val title: String,
        val description: String,
        val target: File,
        val url: String?,
        val optional: Boolean = true,
    ) {
        val installed: Boolean get() = target.exists() && target.length() > 0
    }

    fun componentsDir(context: Context): File = File(context.filesDir, "components").apply { mkdirs() }

    fun glfwStubDir(context: Context): File = File(componentsDir(context), "lwjgl3").apply { mkdirs() }

    /** The stub jar `GameLauncher` will put first on the classpath, if any. */
    /**
     * The GLFW/LWJGL stub, extracted from the APK on first use.
     *
     * It ships in `assets/components/lwjgl3/glfw-stub.jar` rather than being downloaded: without
     * it *nothing* can start (it owns `org.lwjgl.glfw` and the GL bindings), so making the first
     * launch depend on the network would be a needless failure mode. It is copied out of assets
     * once per app version because a jar inside assets cannot be put on a `-cp` path.
     */
    fun glfwStub(context: Context): File? {
        val dir = glfwStubDir(context)
        val target = File(dir, "glfw-stub.jar")
        val versioned = File(dir, ".extracted-${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}")
        if (target.exists() && target.length() > 0 && versioned.exists()) return target

        val fromAssets = runCatching {
            context.assets.open("components/lwjgl3/glfw-stub.jar").use { input ->
                val partial = File(dir, "glfw-stub.jar.part")
                partial.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 16) }
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
            }
            versioned.writeText("ok")
            IbbLog.i(TAG, "Extracted glfw-stub.jar (${target.length()} bytes)")
            target
        }.getOrElse {
            IbbLog.e(TAG, "glfw-stub.jar missing from the APK assets", it)
            null
        }
        if (fromAssets != null) return fromAssets

        // Fall back to a user-supplied jar (useful when testing a newer stub without a rebuild).
        return dir.listFiles()?.firstOrNull { it.name != "glfw-stub.jar.part" && it.extension == "jar" }
    }

    fun authlibInjector(context: Context): File? =
        File(componentsDir(context), "authlib-injector.jar").takeIf { it.exists() }

    fun describe(context: Context): List<Component> = listOf(
        Component(
            id = "glfw-stub",
            title = "GLFW stub",
            description = "Required to run any version. Supplies org.lwjgl.glfw against the native EGL bridge.",
            target = File(glfwStubDir(context), "glfw-stub.jar"),
            url = null, // bundled in assets/components/lwjgl3/glfw-stub.jar
            optional = false,
        ),
        Component(
            id = "authlib-injector",
            // The jar is fetched from the project's GitHub releases; it changes rarely and is
            // verified by the JVM's own class-file checks before use.
            title = "authlib-injector",
            description = "Optional. Only needed for third-party (authlib) servers — never for Microsoft accounts.",
            target = File(componentsDir(context), "authlib-injector.jar"),
            url = AUTH_SERVER,
        ),
    )

    /**
     * Downloads a component to its target path using the shared HTTP client.
     *
     * The download is atomic: it lands in `<name>.part` and is only renamed once complete, so an
     * interrupted download can never leave a truncated jar that the JVM would reject with a
     * cryptic `ClassFormatError`.
     */
    suspend fun install(component: Component): Result<File> = withContext(Dispatchers.IO) {
        val url = component.url
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "${component.title} is not downloaded automatically. " +
                        "See docs/NATIVE_LAYER.md for how to add it to components/.",
                ),
            )
        val target = component.target
        val partial = File(target.parentFile, target.name + ".part")
        runCatching {
            target.parentFile?.mkdirs()
            Http.openStream(url).use { response ->
                partial.outputStream().use { output ->
                    response.stream.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            IbbLog.i(TAG, "Installed ${component.title} (${target.length()} bytes)")
            target
        }.onFailure {
            IbbLog.e(TAG, "Could not install ${component.title}", it)
            partial.delete()
        }
    }

    /** Removes a component; safe to call when it was never installed. */
    fun remove(component: Component): Boolean = runCatching {
        component.target.delete()
    }.getOrDefault(false)

    private const val AUTH_SERVER = "https://github.com/yushijinhun/authlib-injector/releases/latest/download/authlib-injector.jar"
}
