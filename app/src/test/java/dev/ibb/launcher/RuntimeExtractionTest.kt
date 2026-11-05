package dev.ibb.launcher

import dev.ibb.launcher.runtime.RuntimeManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue

/**
 * The runtime tarball is the difference between "Play starts Java" and an unhelpful failure on a
 * device we cannot attach a debugger to, so the extractor is exercised against archives shaped
 * like the ones upstream actually publishes.
 *
 * Archives are built in-memory here: a hand-written tar writer keeps the test independent of the
 * system `tar` and of the JDK's compression libraries.
 */
class RuntimeExtractionTest {

    private val temp: File = Files.createTempDirectory("ibb-runtime-test").toFile()

    @After
    fun tearDown() {
        temp.deleteRecursively()
    }

    // ------------------------------------------------------------------ fixtures

    private class TarEntry(
        val name: String,
        val content: ByteArray? = null,
        val type: Char = if (content == null) '5' else '0',
        val mode: Int = 0b111_101_101, // 0755
        val linkTarget: String = "",
    )

    /** Writes [value] as a NUL-terminated octal field of exactly [width] bytes. */
    private fun octal(value: Long, width: Int): ByteArray =
        (value.toString(8).padStart(width - 1, '0') + "\u0000").toByteArray(Charsets.US_ASCII)

    private fun buildTar(entries: List<TarEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            val header = ByteArray(512)
            fun put(offset: Int, bytes: ByteArray) {
                val n = minOf(bytes.size, header.size - offset)
                bytes.copyInto(header, offset, 0, n)
            }

            val body = e.content ?: ByteArray(0)
            put(0, e.name.toByteArray().copyOf(100))
            put(100, octal(e.mode.toLong(), 8))          // mode
            put(108, octal(0, 8))                        // uid
            put(116, octal(0, 8))                        // gid
            put(124, octal(body.size.toLong(), 12))      // size
            put(136, octal(0, 12))                       // mtime
            header[156] = e.type.code.toByte()           // typeflag
            if (e.linkTarget.isNotEmpty()) put(157, e.linkTarget.toByteArray().copyOf(100))
            put(257, "ustar\u0000".toByteArray())        // magic
            put(263, "00".toByteArray())                 // version

            // The checksum is computed with its own field read as eight spaces.
            for (i in 148..155) header[i] = ' '.code.toByte()
            val checksum = header.sumOf { (it.toInt() and 0xFF).toLong() }
            val checksumField = checksum.toString(8).padStart(6, '0')
                .toByteArray(Charsets.US_ASCII)
            put(148, checksumField)
            header[154] = 0
            header[155] = ' '.code.toByte()

            out.write(header)
            out.write(body)
            val padding = (512 - body.size % 512) % 512
            if (padding > 0) out.write(ByteArray(padding))
        }
        out.write(ByteArray(1024)) // two zero blocks terminate the archive
        return out.toByteArray()
    }

    /** Extracts a synthetic archive the way [RuntimeManager.install] does after decompression. */
    private fun extract(entries: List<TarEntry>, target: File) {
        val tar: ByteArray = buildTar(entries)
        manager(temp).untar(ByteArrayInputStream(tar), target)
    }

    private fun manager(dir: File) = RuntimeManager(dir, RuntimeManager.AndroidArch.ARM64)

    // ------------------------------------------------------------------ tests

    @Test
    fun `flat layout extracts bin java and lib`() {
        val target = File(temp, "runtimes/jre-17-arm64").apply { mkdirs() }

        extract(
            listOf(
                TarEntry("bin/"),
                TarEntry("bin/java", "#!/bin/sh\nexec java.real".toByteArray()),
                TarEntry("lib/"),
                TarEntry("lib/server/"),
                TarEntry("lib/server/libjvm.so", ByteArray(2048) { it.toByte() }),
            ),
            target,
        )

        assertTrue("bin/java missing", File(target, "bin/java").exists())
        assertEquals(
            "#!/bin/sh\nexec java.real",
            File(target, "bin/java").readText(),
        )
        assertEquals(2048L, File(target, "lib/server/libjvm.so").length())
    }

    @Test
    fun `binary payload larger than one tar block survives`() {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        val target = File(temp, "out").apply { mkdirs() }

        extract(listOf(TarEntry("bin/java", payload)), target)

        assertTrue(File(target, "bin/java").readBytes().contentEquals(payload))
    }

    @Test
    fun `symlinked bin java is materialised`() {
        assumeTrue("filesystem cannot create symlinks", canSymlink())
        val target = File(temp, "rt").apply { mkdirs() }

        extract(
            listOf(
                TarEntry("lib/jexec", "binary".toByteArray()),
                TarEntry("bin/java", type = '2', linkTarget = "../lib/jexec"),
            ),
            target,
        )

        val java = File(target, "bin/java")
        assertTrue("bin/java was not created", java.exists() || Files.isSymbolicLink(java.toPath()))
        assertEquals("binary", if (Files.isSymbolicLink(java.toPath())) {
            java.toPath().toRealPath().toFile().readText()
        } else {
            java.readText()
        })
    }

    @Test
    fun `path traversal entries are rejected`() {
        val target = File(temp, "out").apply { mkdirs() }

        val thrown = runCatching { extract(listOf(TarEntry("../escaped.txt", "nope".toByteArray())), target) }
            .exceptionOrNull()

        assertTrue("a traversal entry must throw, got $thrown", thrown != null)
        assertTrue("file escaped the target", !File(temp, "escaped.txt").exists())
    }

    @Test
    fun `nested single-root archives are usable after flattening`() {
        val root = File(temp, "jre-17-arm64")
        val nested = File(root, "jre17").apply { mkdirs() }
        File(nested, "bin").mkdirs()
        File(nested, "bin/java").writeText("bin")
        File(nested, "lib").mkdirs()
        File(nested, "lib/libjvm.so").writeText("jvm")

        manager(temp).flattenForTest(root)

        assertTrue(File(root, "bin/java").exists())
        assertTrue(File(root, "lib/libjvm.so").exists())
        assertTrue("nested directory left behind", !nested.exists())
    }

    private fun canSymlink(): Boolean = runCatching {
        val probe = File(temp, "symlink-probe")
        val target = File(temp, "symlink-target").apply { writeText("x") }
        probe.delete()
        Files.createSymbolicLink(probe.toPath(), target.toPath())
        probe.delete()
        target.delete()
        true
    }.getOrDefault(false)
}
