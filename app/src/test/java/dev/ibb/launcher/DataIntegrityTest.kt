package dev.ibb.launcher

import dev.ibb.launcher.data.model.ControlLayout
import dev.ibb.launcher.data.model.ControlSpec
import dev.ibb.launcher.data.model.ControlType
import dev.ibb.launcher.data.model.LauncherSettings
import dev.ibb.launcher.data.model.ModCategory
import dev.ibb.launcher.data.model.InputSettings
import dev.ibb.launcher.data.model.PerformanceOptions
import dev.ibb.launcher.data.model.Renderer
import dev.ibb.launcher.core.IbbPaths
import dev.ibb.launcher.download.ParallelDownloader
import dev.ibb.launcher.download.ParallelDownloader.Companion.sha1OrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sanity checks for the pure helpers that guard the launcher against bad user data:
 * path sanitising (a version called `../..` must not escape the instances folder), checksum
 * verification (a truncated download must be rejected before the game sees it) and file-type
 * detection for imported mods.
 */
class DataIntegrityTest {

    // ------------------------------------------------------------------ paths

    @Test
    fun `sanitize cannot escape the instances directory`() {
        assertEquals(".._.._etc", IbbPaths.sanitize("../../etc"))
        assertEquals("1.20.4", IbbPaths.sanitize("1.20.4"))
        assertEquals("1.20.4_-_Fabric_0.15.0", IbbPaths.sanitize("1.20.4 - Fabric 0.15.0"))
        assertFalse(IbbPaths.sanitize("/absolute/path").startsWith("/"))
    }

    @Test
    fun `formatBytes switches units sensibly`() {
        assertEquals("512 B", IbbPaths.formatBytes(512))
        assertEquals("1.0 KB", IbbPaths.formatBytes(1024))
        assertEquals("1.5 MB", IbbPaths.formatBytes(1024L * 1024 + 512 * 1024))
        assertTrue(IbbPaths.formatBytes(2L * 1024 * 1024 * 1024).endsWith("GB"))
    }

    @Test
    fun `usage sums only files`() {
        val root = File.createTempFile("ibb", "root").let { it.delete(); it.mkdirs(); it }
        try {
            File(root, "a.bin").writeBytes(ByteArray(100))
            File(root, "nested").mkdirs()
            File(root, "nested/b.bin").writeBytes(ByteArray(50))
            assertEquals(150, IbbPaths.usage(root))
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    // ------------------------------------------------------------------ checksums

    @Test
    fun `sha1 matches the known digest of hello world`() {
        val file = File.createTempFile("ibb", ".txt")
        try {
            file.writeText("hello world")
            assertEquals("2aae6c35c94fcfb415dbe95f408b9ce91ee846ed", file.sha1OrNull())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `sha1 of a missing file is null instead of throwing`() {
        assertNull(File("/definitely/not/here/${System.nanoTime()}").sha1OrNull())
    }

    @Test
    fun `partial downloads are purged`() {
        val root = File.createTempFile("ibb", "dl").let { it.delete(); it.mkdirs(); it }
        try {
            File(root, "client.jar.part").writeText("half")
            File(root, "client.jar").writeText("whole")
            ParallelDownloader().purgePartials(root)
            assertFalse(File(root, "client.jar.part").exists())
            assertTrue(File(root, "client.jar").exists())
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    // ------------------------------------------------------------------ content detection

    @Test
    fun `mod category comes from the file extension`() {
        assertEquals(ModCategory.MOD, ModCategory.forExtension("jar"))
        assertEquals(ModCategory.RESOURCE_PACK, ModCategory.forExtension("ZIP"))
        assertEquals(ModCategory.WORLD, ModCategory.forExtension("mcworld"))
        assertEquals(ModCategory.CONFIG, ModCategory.forExtension("toml"))
        assertNull(ModCategory.forExtension("exe"))
    }

    @Test
    fun `mod folders match the vanilla layout`() {
        assertEquals("mods", ModCategory.MOD.folder)
        assertEquals("resourcepacks", ModCategory.RESOURCE_PACK.folder)
        assertEquals("shaderpacks", ModCategory.SHADER_PACK.folder)
        assertEquals("saves", ModCategory.WORLD.folder)
    }

    @Test
    fun `an archive is identified by its contents not its name`() {
        val repo = dev.ibb.launcher.mods.ModRepository(File(System.getProperty("java.io.tmpdir"), "ibb-instance"))
        val resourcePack = File.createTempFile("pack", ".jar") // deliberately the "wrong" extension
        try {
            ZipOutputStream(resourcePack.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("pack.mcmeta"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/minecraft/textures/block/stone.png"))
                zip.write(ByteArray(4))
                zip.closeEntry()
            }
            assertEquals(ModCategory.RESOURCE_PACK, repo.detectCategory(resourcePack))
        } finally {
            resourcePack.delete()
        }
    }

    @Test
    fun `a fabric mod is detected by its manifest`() {
        val repo = dev.ibb.launcher.mods.ModRepository(File(System.getProperty("java.io.tmpdir"), "ibb-instance"))
        val mod = File.createTempFile("mod", ".zip")
        try {
            ZipOutputStream(mod.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("fabric.mod.json"))
                zip.write("{\"id\":\"test\"}".toByteArray())
                zip.closeEntry()
            }
            assertEquals(ModCategory.MOD, repo.detectCategory(mod))
        } finally {
            mod.delete()
        }
    }

    @Test
    fun `a corrupt archive falls back to the extension`() {
        val repo = dev.ibb.launcher.mods.ModRepository(File(System.getProperty("java.io.tmpdir"), "ibb-instance"))
        val broken = File.createTempFile("broken", ".jar")
        try {
            broken.writeText("this is not a zip")
            assertEquals(ModCategory.MOD, repo.detectCategory(broken))
        } finally {
            broken.delete()
        }
    }

    // ------------------------------------------------------------------ settings defaults

    @Test
    fun `default settings are bootable on any device`() {
        val settings = LauncherSettings()
        assertTrue(settings.performance.allocatedRamMb >= 512)
        assertTrue(settings.performance.allocatedRamMb <= 6144)
        assertTrue(settings.graphics.scaleFactor in 0.5f..2f)
        assertTrue(settings.graphics.maxFrameRate in 30..240)
        assertTrue(InputSettings().gamepadEnabled)
        assertFalse(InputSettings().invertMouseY)
    }

    @Test
    fun `every renderer has a unique id, label and shim library`() {
        Renderer.entries.forEach { renderer ->
            assertTrue(renderer.displayName.isNotBlank())
            assertTrue(renderer.nativeLibrary.startsWith("lib") && renderer.nativeLibrary.endsWith(".so"))
            assertTrue(renderer.notes.isNotBlank())
        }
        assertEquals(Renderer.entries.size, Renderer.entries.map { it.id }.distinct().size)
        assertEquals(Renderer.entries.size, Renderer.entries.map { it.displayName }.distinct().size)
    }

    @Test
    fun `unknown renderer ids fall back to the compatible default`() {
        assertEquals(Renderer.ANGLE_GLES, Renderer.fromId("nonsense"))
        assertEquals(Renderer.ZINK_VULKAN, Renderer.fromId("zink"))
    }

    @Test
    fun `default jvm args do not pin a heap size`() {
        // The heap comes from the slider (-Xmx). A second, conflicting -Xmx in the free-text args
        // is the classic cause of "my RAM setting is ignored".
        assertFalse(PerformanceOptions.DEFAULT_JVM_ARGS.contains("-Xmx"))
        assertFalse(PerformanceOptions.DEFAULT_JVM_ARGS.contains("-Xms"))
    }

    @Test
    fun `a control layout survives a round trip through its defaults`() {
        val layout = ControlLayout(
            id = "custom",
            name = "Custom",
            controls = listOf(ControlSpec(type = ControlType.BUTTON, label = "Jump", keys = listOf(57))),
        )
        assertEquals(layout, layout.copy())
        assertEquals(1, layout.controls.size)
    }
}
