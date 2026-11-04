package dev.ibb.launcher

import dev.ibb.launcher.data.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * URL / header handling for the download pipeline.
 *
 * Nothing here touches the network: the parsers are fed with byte streams so the tests run in
 * microseconds and cannot flake in CI.
 */
class HttpParsingTest {

    @Test
    fun `content length header is parsed`() {
        assertEquals(1024L, Http.contentLength("1024"))
        assertNull(Http.contentLength(null))
        assertNull(Http.contentLength("garbage"))
    }

    @Test
    fun `content range gives the resume offset`() {
        assertEquals(512L, Http.contentRangeStart("bytes 512-1023/1024"))
        assertNull(Http.contentRangeStart("bytes */1024"))
        assertNull(Http.contentRangeStart(null))
    }

    @Test
    fun `file name is taken from the url path only`() {
        assertEquals("client.jar", Http.fileName("https://piston-data.mojang.com/v1/objects/abc/client.jar"))
        assertEquals("lwjgl-3.3.3-natives.tar.gz", Http.fileName("https://x/y/lwjgl-3.3.3-natives.tar.gz?token=1"))
        assertEquals("example.com", Http.fileName("https://example.com/"))
        assertEquals("example.com", Http.fileName("https://example.com"))
    }

    @Test
    fun `sha1 formatted with leading zeros is not truncated`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x0f, 0x7f)
        assertEquals("00000f7f", Http.toHex(bytes))
    }

    @Test
    fun `reads a stream fully even when the reader returns short chunks`() {
        val source = "0123456789".toByteArray()
        val chunks = object : ByteArrayInputStream(source) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 3))
        }
        assertEquals("0123456789", Http.readFully(chunks).decodeToString())
    }

    @Test
    fun `user agent identifies the launcher`() {
        assertTrue(Http.USER_AGENT.startsWith("IBBLauncher/"))
        assertTrue(Http.USER_AGENT.contains("Android"))
    }

    @Test
    fun `transport errors are retried`() {
        var attempts = 0
        val result = Http.runCatchingWithRetries(attempts = 2, initialDelayMs = 1) {
            attempts++
            throw IOException("connection reset")
        }
        assertNull(result)
        assertEquals(2, attempts)
    }

    @Test
    fun `client errors are not retried`() {
        var attempts = 0
        val result = Http.runCatchingWithRetries(attempts = 3, initialDelayMs = 1) {
            attempts++
            throw Http.HttpException(404, "not found", "https://example.com/x")
        }
        assertNull(result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retry helper succeeds on the second attempt`() {
        var attempts = 0
        val result = Http.runCatchingWithRetries(attempts = 3, initialDelayMs = 1) {
            attempts++
            if (attempts < 2) throw IOException("flaky") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `http exception message carries the status and url`() {
        val error = Http.HttpException(503, "", "https://example.com/a")
        assertTrue(error.message!!.contains("503"))
        assertTrue(error.message!!.contains("https://example.com/a"))
    }
}
