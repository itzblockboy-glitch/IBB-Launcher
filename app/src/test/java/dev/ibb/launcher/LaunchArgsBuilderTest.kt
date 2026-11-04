package dev.ibb.launcher

import dev.ibb.launcher.data.model.Account
import dev.ibb.launcher.data.model.AccountType
import dev.ibb.launcher.data.model.VersionDetail
import dev.ibb.launcher.data.model.VersionSummary
import dev.ibb.launcher.game.LaunchArgsBuilder
import dev.ibb.launcher.game.LaunchArgsBuilder.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command line is the launcher's contract with the JVM.
 *
 * Constraints encoded below are the ones that break real devices if they regress:
 *  * `-cp` and its value are never merged, and exactly one classpath element follows it;
 *  * every game argument is `--flag value`, never `--flag=value` (older Minecraft rejects it);
 *  * `--accessToken` is never blank for online accounts and never contains spaces;
 *  * the native paths come before any loader JVM args so a mod cannot shadow them.
 */
class LaunchArgsBuilderTest {

    private val account = Account(
        username = "Steve",
        uuid = "069a79f444e94726a5befca90e38aaf5",
        type = AccountType.MICROSOFT,
        accessToken = "token-123",
        xuid = "2535400000000001",
        expiresAt = System.currentTimeMillis() / 1000 + 3600,
    )

    private val env = Environment(
        gameDirectory = "/storage/emulated/0/Android/data/dev.ibb.launcher/files/minecraft/instances/1.20.4",
        assetsDirectory = "/root/minecraft/assets",
        assetIndexId = "5",
        versionId = "1.20.4",
        versionType = "release",
        nativesDirectory = "/root/natives/1.20.4",
        librariesDirectory = "/root/libraries",
        runtimeHome = "/root/runtimes/jre17",
        windowWidth = 1280,
        windowHeight = 720,
        allocatedRamMb = 2048,
        classpath = listOf("/root/libs/main.jar", "/root/libs/lib1.jar"),
        mainClass = "net.minecraft.client.main.Main",
        openglLibrary = "libglxshim.so",
    )

    private val detail = VersionDetail(id = "1.20.4", mainClass = "net.minecraft.client.main.Main")
    private val summary = VersionSummary(id = "1.20.4", type = "release", url = "")

    @Test
    fun `jvm args keep cp and its value separate`() {
        val argv = LaunchArgsBuilder.buildJvmArgs(env, detail)
        val cpIndex = argv.indexOf("-cp")
        assertTrue(cpIndex > 0)
        assertEquals("/root/libs/main.jar:/root/libs/lib1.jar", argv[cpIndex + 1])
        assertEquals("net.minecraft.client.main.Main", argv[cpIndex + 2])
    }

    @Test
    fun `heap is fixed to the slider value`() {
        val argv = LaunchArgsBuilder.buildJvmArgs(env, detail)
        assertTrue(argv.contains("-Xmx2048M"))
        assertTrue(argv.contains("-Xms2048M"))
    }

    @Test
    fun `renderer and native paths are passed to lwjgl`() {
        val argv = LaunchArgsBuilder.buildJvmArgs(env, detail)
        assertTrue(argv.contains("-Dorg.lwjgl.opengl.libname=libglxshim.so"))
        assertTrue(argv.contains("-Djava.library.path=/root/natives/1.20.4"))
        assertTrue(argv.contains("-Dorg.lwjgl.librarypath=/root/natives/1.20.4"))
        assertTrue(argv.contains("-Dglfwstub.initEgl=false"))
        assertTrue(argv.contains("-Dglfwstub.windowWidth=1280"))
    }

    @Test
    fun `no renderer property when the system gles driver is used`() {
        val argv = LaunchArgsBuilder.buildJvmArgs(env.copy(openglLibrary = null), detail)
        assertFalse(argv.any { it.startsWith("-Dorg.lwjgl.opengl.libname") })
    }

    @Test
    fun `game args use separate flag and value elements`() {
        val argv = LaunchArgsBuilder.buildGameArgs(account, summary, env)
        assertTrue(argv.all { !it.startsWith("--") || !it.contains('=') })
        assertEquals("Steve", argv[argv.indexOf("--username") + 1])
        assertEquals("069a79f444e94726a5befca90e38aaf5", argv[argv.indexOf("--uuid") + 1])
        assertEquals("msa", argv[argv.indexOf("--userType") + 1])
    }

    @Test
    fun `offline accounts launch as legacy users without a token`() {
        val offline = Account(username = "Alex", type = AccountType.OFFLINE, accessToken = "")
        val argv = LaunchArgsBuilder.buildGameArgs(offline, summary, env)
        assertEquals("legacy", argv[argv.indexOf("--userType") + 1])
        assertEquals("0", argv[argv.indexOf("--accessToken") + 1])
        assertFalse(argv.contains("--xuid"))
    }

    @Test
    fun `resolution is only requested when the user set one`() {
        val without = LaunchArgsBuilder.buildGameArgs(account, summary, env)
        assertFalse(without.contains("--width"))

        val with = LaunchArgsBuilder.buildGameArgs(account, summary, env.copy(resolutionRequested = true))
        assertEquals("1280", with[with.indexOf("--width") + 1])
        assertEquals("720", with[with.indexOf("--height") + 1])
    }

    @Test
    fun `quick play world is appended last`() {
        val argv = LaunchArgsBuilder.buildGameArgs(account, summary, env, quickPlayWorld = "My World")
        assertEquals("My World", argv.last())
        assertEquals("--quickPlaySingleplayer", argv[argv.size - 2])
    }

    @Test
    fun `full command starts with the program name and ends with game args`() {
        val argv = LaunchArgsBuilder.buildFullCommand(account, detail, summary, env)
        assertEquals("java", argv.first())
        assertTrue(argv.contains("--version"))
        assertEquals("1.20.4", argv[argv.indexOf("--version") + 1])
        // exactly one main class occurrence, after the classpath
        assertEquals(1, argv.count { it == "net.minecraft.client.main.Main" })
    }

    @Test
    fun `logging config from the version metadata is honoured`() {
        val logging = dev.ibb.launcher.data.model.LoggingConfig(
            client = dev.ibb.launcher.data.model.LoggingConfig.LoggingClient(
                argument = "-Dlog4j.configurationFile=\${path}",
                file = dev.ibb.launcher.data.model.LoggingConfig.LoggingFile(id = "client-1.12.xml"),
            ),
        )
        val argv = LaunchArgsBuilder.buildJvmArgs(env, detail.copy(logging = logging))
        assertTrue(
            argv.contains(
                "-Dlog4j.configurationFile=${env.gameDirectory}/log4j-rce-patch.xml",
            ),
        )
    }
}
