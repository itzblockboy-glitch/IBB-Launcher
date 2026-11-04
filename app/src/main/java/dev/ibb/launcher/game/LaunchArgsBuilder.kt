package dev.ibb.launcher.game

import dev.ibb.launcher.data.model.Account
import dev.ibb.launcher.data.model.VersionDetail
import dev.ibb.launcher.data.model.VersionSummary

/**
 * Builds the final `java …` command line for Minecraft.
 *
 * This is pure logic (no Android imports) so it can be golden-tested. The order matters:
 * JVM args first, then `-cp`, then the main class, then game args — exactly what the vanilla
 * launcher produces.
 */
object LaunchArgsBuilder {

    data class Environment(
        val gameDirectory: String,
        val assetsDirectory: String,
        val assetIndexId: String,
        val versionId: String,
        val versionType: String,
        val nativesDirectory: String,
        val librariesDirectory: String,
        val runtimeHome: String,
        val windowWidth: Int,
        val windowHeight: Int,
        val allocatedRamMb: Int,
        val extraJvmArgs: List<String> = emptyList(),
        val classpath: List<String>,
        val mainClass: String,
        val resolutionRequested: Boolean = false,
        /** Renderer library to hand to LWJGL (`libEGL_angle.so` etc.), null = system GLES. */
        val openglLibrary: String? = null,
        val glfwStubWidth: Int = windowWidth,
        val glfwStubHeight: Int = windowHeight,
        val launcherName: String = "IBB Launcher",
        val launcherVersion: String = "1.0.0",
        val cpuCount: Int = Runtime.getRuntime().availableProcessors(),
    )

    /**
     * @param quickPlayWorld optional world folder for `--quickPlaySingleplayer` (1.20+).
     */
    fun buildJvmArgs(env: Environment, version: VersionDetail): MutableList<String> = buildList {
        add("-Xms${env.allocatedRamMb}M")
        add("-Xmx${env.allocatedRamMb}M")
        add("-Djava.home=${env.runtimeHome}")
        add("-Djava.io.tmpdir=${env.gameDirectory}/tmp")
        add("-Duser.home=${env.gameDirectory}")
        add("-Duser.language=en")
        add("-Duser.country=US")
        add("-Dos.name=Linux")
        add("-Dos.version=Android")
        add("-Dminecraft.launcher.brand=${env.launcherName}")
        add("-Dminecraft.launcher.version=${env.launcherVersion}")
        add("-Dlog4j2.formatMsgNoLookups=true")
        add("-Dfml.earlyprogresswindow=false")
        add("-Djdk.lang.Process.launchMechanism=FORK")
        add("-XX:ActiveProcessorCount=${env.cpuCount}")
        add("-Dorg.lwjgl.system.allocator=system")
        add("-Dorg.lwjgl.util.NoChecks=true")
        add("-Dglfwstub.windowWidth=${env.glfwStubWidth}")
        add("-Dglfwstub.windowHeight=${env.glfwStubHeight}")
        add("-Dglfwstub.initEgl=false")
        env.openglLibrary?.let { add("-Dorg.lwjgl.opengl.libname=$it") }
        add("-Djava.library.path=${env.nativesDirectory}")
        add("-Dorg.lwjgl.librarypath=${env.nativesDirectory}")
        version.logging?.client?.argument?.let { arg ->
            // -Dlog4j.configurationFile=<file> — points at the patched config we download.
            add(arg.replace("\${path}", "${env.gameDirectory}/log4j-rce-patch.xml"))
        }
        addAll(env.extraJvmArgs)
        add("-cp")
        add(VersionResolver.buildClasspath(":", env.classpath))
        add(env.mainClass)
    }.toMutableList()

    /**
     * Game arguments in the canonical order: username/version/gamedir/assets/uuid/token/
     * userType/versionType plus optional resolution and quick-play.
     */
    fun buildGameArgs(
        account: Account,
        version: VersionSummary,
        env: Environment,
        quickPlayWorld: String? = null,
    ): MutableList<String> = buildList {
        add("--username"); add(account.username)
        add("--version"); add(env.versionId)
        add("--gameDir"); add(env.gameDirectory)
        add("--assetsDir"); add(env.assetsDirectory)
        add("--assetIndex"); add(env.assetIndexId)
        add("--uuid"); add(account.uuid)
        add("--accessToken"); add(account.accessToken.ifBlank { "0" })
        add("--userType"); add(if (account.isOnline) "msa" else "legacy")
        add("--versionType"); add(env.versionType)
        add("--userProperties"); add("{}")
        if (account.isOnline) {
            add("--xuid"); add(account.xuid)
            add("--clientId"); add("")
            add("--authXuid"); add(account.xuid)
        }
        if (env.resolutionRequested) {
            add("--width"); add(env.windowWidth.toString())
            add("--height"); add(env.windowHeight.toString())
        }
        if (!quickPlayWorld.isNullOrBlank()) {
            add("--quickPlaySingleplayer"); add(quickPlayWorld)
        }
    }.toMutableList()

    /** Full command line, index 0 is the program name (matches `JLI_Launch` expectations). */
    fun buildFullCommand(
        account: Account,
        version: VersionDetail,
        summary: VersionSummary,
        env: Environment,
        quickPlayWorld: String? = null,
    ): List<String> = buildList {
        add("java")
        addAll(buildJvmArgs(env, version))
        addAll(buildGameArgs(account, summary, env, quickPlayWorld))
    }

    /**
     * Placeholder values for mod-loader profiles that still ship `${...}` argument templates.
     */
    fun placeholderValues(account: Account, env: Environment, version: VersionDetail): Map<String, String> = mapOf(
        "auth_player_name" to account.username,
        "version_name" to env.versionId,
        "game_directory" to env.gameDirectory,
        "assets_root" to env.assetsDirectory,
        "assets_index_name" to env.assetIndexId,
        "auth_uuid" to account.uuid,
        "auth_access_token" to account.accessToken.ifBlank { "0" },
        "auth_session" to "token:${account.accessToken}:${account.uuid}",
        "user_type" to if (account.isOnline) "msa" else "legacy",
        "version_type" to env.versionType,
        "user_properties" to "{}",
        "clientid" to "",
        "auth_xuid" to account.xuid,
        "launcher_name" to env.launcherName,
        "launcher_version" to env.launcherVersion,
        "classpath" to VersionResolver.buildClasspath(":", env.classpath),
        "natives_directory" to env.nativesDirectory,
        "library_directory" to env.librariesDirectory,
        "classpath_separator" to ":",
        "primary_jar" to "${env.librariesDirectory}/../versions/${env.versionId}/${env.versionId}.jar",
        "resolution_width" to env.windowWidth.toString(),
        "resolution_height" to env.windowHeight.toString(),
    )

    /** Smoke test used by the launch pre-flight checklist: catches template leftovers. */
    fun findUnresolvedPlaceholders(args: List<String>): List<String> =
        args.filter { it.contains("\${") }
}
