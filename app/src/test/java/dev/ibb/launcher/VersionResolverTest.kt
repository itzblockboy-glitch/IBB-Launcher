package dev.ibb.launcher

import dev.ibb.launcher.data.model.Library
import dev.ibb.launcher.data.model.LibraryDownloads
import dev.ibb.launcher.data.model.Rule
import dev.ibb.launcher.data.model.VersionDetail
import dev.ibb.launcher.game.VersionResolver
import dev.ibb.launcher.game.VersionResolver.Env
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules are the single most common source of "the game launches, then instantly crashes".
 * A mis-evaluated `rules` entry either drops a library the game needs or ships a Windows native,
 * and both failures are far from obvious in the crash log — so they get exhaustive coverage here.
 */
class VersionResolverTest {

    private val arm64 = Env(osName = "linux", osArch = "aarch64")

    // ------------------------------------------------------------------ architecture mapping

    @Test
    fun `arm64 abi maps to aarch64`() {
        assertEquals("aarch64", Env.forAndroidArch(true, "arm64-v8a", "14").osArch)
    }

    @Test
    fun `arm32 abi maps to arm`() {
        assertEquals("arm", Env.forAndroidArch(false, "armeabi-v7a", "11").osArch)
    }

    @Test
    fun `x86_64 abi maps to x86_64 and x86 to x86`() {
        assertEquals("x86_64", Env.forAndroidArch(true, "x86_64", "13").osArch)
        assertEquals("x86", Env.forAndroidArch(false, "x86", "13").osArch)
    }

    // ------------------------------------------------------------------ rule evaluation

    @Test
    fun `no rules means allowed`() {
        assertTrue(VersionResolver.isAllowed(emptyList(), arm64))
    }

    @Test
    fun `last matching rule wins`() {
        val rules = listOf(
            Rule(action = "allow"),
            Rule(action = "disallow", os = Rule.OsRule(name = "linux")),
        )
        assertFalse(VersionResolver.isAllowed(rules, arm64))
    }

    @Test
    fun `rule for another os does not allow anything`() {
        val rules = listOf(Rule(action = "allow", os = Rule.OsRule(name = "windows")))
        assertFalse(VersionResolver.isAllowed(rules, arm64))
    }

    @Test
    fun `os matching is case insensitive`() {
        val rules = listOf(Rule(action = "allow", os = Rule.OsRule(name = "LINUX")))
        assertTrue(VersionResolver.isAllowed(rules, arm64))
    }

    @Test
    fun `features gate resolves on the requested value`() {
        val rule = Rule(action = "allow", features = mapOf("has_custom_resolution" to true))
        assertFalse(VersionResolver.isAllowed(listOf(rule), arm64, features = emptyMap()))
        assertTrue(VersionResolver.isAllowed(listOf(rule), arm64, features = mapOf("has_custom_resolution" to true)))
    }

    @Test
    fun `arch rules are exact`() {
        assertFalse(VersionResolver.isAllowed(listOf(Rule(action = "allow", os = Rule.OsRule(arch = "x86"))), arm64))
        assertTrue(VersionResolver.isAllowed(listOf(Rule(action = "allow", os = Rule.OsRule(arch = "aarch64"))), arm64))
    }

    // ------------------------------------------------------------------ library filtering

    @Test
    fun `windows and macos natives are rejected`() {
        val windows = Library(name = "org.lwjgl:lwjgl:3.3.3:natives-windows")
        val macos = Library(name = "org.lwjgl:lwjgl:3.3.3:natives-macos")
        assertFalse(VersionResolver.isAndroidCompatible(windows, arm64))
        assertFalse(VersionResolver.isAndroidCompatible(macos, arm64))
    }

    @Test
    fun `plain artifacts are kept`() {
        val plain = Library(name = "net.minecraft:client:1.20.4", downloads = LibraryDownloads())
        assertTrue(VersionResolver.isAndroidCompatible(plain, arm64))
    }

    @Test
    fun `duplicate coordinates keep the later version`() {
        val merged = VersionResolver.dedupeLibraries(
            listOf(
                Library(name = "com.mojang:brigadier:1.0.18"),
                Library(name = "com.mojang:brigadier:1.1.8"),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("com.mojang:brigadier:1.1.8", merged.single().name)
    }

    @Test
    fun `lwjgl version is parsed from both package names`() {
        assertEquals("3.3.3", VersionResolver.lwjglVersion(listOf("org.lwjgl:lwjgl:3.3.3")))
        assertEquals("2.9.4", VersionResolver.lwjglVersion(listOf("org.lwjgl.lwjgl:lwjgl:2.9.4")))
        assertEquals(null, VersionResolver.lwjglVersion(listOf("com.mojang:brigadier:1.0.18")))
    }

    @Test
    fun `lwjgl2 versions need the compatibility layer`() {
        assertTrue(VersionResolver.needsLwjglx(listOf("org.lwjgl.lwjgl:lwjgl:2.9.4")))
        assertFalse(VersionResolver.needsLwjglx(listOf("org.lwjgl:lwjgl:3.3.3")))
    }

    // ------------------------------------------------------------------ java version mapping

    @Test
    fun `java major follows the vanilla cut-offs`() {
        assertEquals(8, VersionResolver.requiredJavaMajor("1.16.5", null))
        assertEquals(8, VersionResolver.requiredJavaMajor("1.12.2", null))
        assertEquals(17, VersionResolver.requiredJavaMajor("1.17", null))
        assertEquals(17, VersionResolver.requiredJavaMajor("1.20.4", null))
        assertEquals(21, VersionResolver.requiredJavaMajor("1.20.5", null))
        assertEquals(21, VersionResolver.requiredJavaMajor("1.21.4", null))
    }

    @Test
    fun `declared java version always wins`() {
        // 1.21.4 metadata declares 21 today, but a future retarget must be honoured.
        assertEquals(17, VersionResolver.requiredJavaMajor("1.21.4", 17))
        assertEquals(21, VersionResolver.requiredJavaMajor("1.8.9", 21))
    }

    // ------------------------------------------------------------------ inheritance flattening

    @Test
    fun `flatten keeps parent libraries and child main class`() {
        val parent = VersionDetail(
            id = "1.20.4",
            mainClass = "net.minecraft.client.main.Main",
            libraries = listOf(Library(name = "com.mojang:brigadier:1.0.18")),
        )
        val child = VersionDetail(
            id = "1.20.4-fabric",
            mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
            inheritsFrom = "1.20.4",
            libraries = listOf(Library(name = "net.fabricmc:fabric-loader:0.15.0")),
        )
        val merged = VersionResolver.flatten(child, parent)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", merged.mainClass)
        assertEquals(2, merged.libraries.size)
        assertEquals("com.mojang:brigadier:1.0.18", merged.libraries.first().name)
    }

    @Test
    fun `flatten without a parent returns the child untouched`() {
        val child = VersionDetail(id = "x", mainClass = "Main")
        assertEquals(child, VersionResolver.flatten(child, null))
    }

    // ------------------------------------------------------------------ placeholders

    @Test
    fun `placeholders expand inside one argv element`() {
        val out = VersionResolver.expandPlaceholders(
            listOf("--username", "\${auth_player_name}", "--version", "\${version_name}"),
            mapOf("auth_player_name" to "Steve", "version_name" to "1.20.4"),
        )
        assertEquals(listOf("--username", "Steve", "--version", "1.20.4"), out)
    }

    @Test
    fun `paths containing spaces are never split`() {
        val dir = "/storage/emulated/0/My Games/minecraft"
        val out = VersionResolver.expandPlaceholders(listOf("\${game_directory}"), mapOf("game_directory" to dir))
        assertEquals(1, out.size)
        assertEquals(dir, out.single())
    }

    @Test
    fun `unknown placeholders are preserved for diagnosis`() {
        val out = VersionResolver.expandPlaceholders(listOf("\${unknown_key}"), emptyMap())
        assertEquals(listOf("\${unknown_key}"), out)
    }

    // ------------------------------------------------------------------ classpath

    @Test
    fun `classpath drops blanks and duplicates but keeps order`() {
        val cp = VersionResolver.buildClasspath(":", listOf("/a.jar", "", "/b.jar", "/a.jar"))
        assertEquals("/a.jar:/b.jar", cp)
    }
}
