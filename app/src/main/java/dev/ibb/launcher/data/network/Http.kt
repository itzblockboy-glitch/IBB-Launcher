package dev.ibb.launcher.data.network

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import dev.ibb.launcher.core.IbbLog
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * Minimal, allocation-light HTTP client built on [HttpURLConnection].
 *
 * We deliberately avoid a third-party stack: the launcher only performs GET/POST JSON and
 * streaming file downloads, and the platform client already ships connection pooling and
 * TLS. This keeps the APK ~1.5 MB smaller and removes a source of ProGuard surprises.
 *
 * All calls are blocking and must run on [kotlinx.coroutines.Dispatchers.IO].
 */
object Http {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    const val USER_AGENT = "IBBLauncher/1.0 (Android; +https://github.com/itzblockboy-glitch/IBB-Launcher)"

    class HttpException(val code: Int, val body: String, url: String) :
        IOException("HTTP $code for $url${if (body.isNotBlank()) ": ${body.take(300)}" else ""}")

    /**
     * Performs a request and returns the body as text.
     * @throws HttpException for non-2xx responses, IOException for transport failures.
     */
    fun requestText(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String = "application/json",
        followRedirects: Boolean = true,
    ): String {
        val conn = openConnection(url, method, headers, body, contentType, followRedirects)
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readFully(it, conn.contentEncoding) }.orEmpty()
            if (code !in 200..299) throw HttpException(code, text, url)
            text
        } finally {
            conn.disconnect()
        }
    }

    /** Opens a connection for streaming (downloads). Caller must disconnect. */
    fun openStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        rangeStart: Long = 0L,
    ): StreamResponse {
        val conn = openConnection(url, "GET", headers, null, null, true)
        if (rangeStart > 0L) conn.setRequestProperty("Range", "bytes=$rangeStart-")
        val code = conn.responseCode
        if (code !in 200..299) {
            val body = conn.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
            conn.disconnect()
            throw HttpException(code, body, url)
        }
        return StreamResponse(conn, conn.inputStream, conn.contentLengthLong, code == 206)
    }

    class StreamResponse(
        private val connection: HttpURLConnection,
        val stream: InputStream,
        val contentLength: Long,
        val isPartial: Boolean,
    ) : AutoCloseable {
        val contentType: String? get() = connection.contentType
        override fun close() {
            runCatching { stream.close() }
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?,
        followRedirects: Boolean,
    ): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                contentType?.let { setRequestProperty("Content-Type", it) }
                val bytes = body.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = Tls.factory
        }
        return conn
    }

    // -------------------------------------------------------------------------------------
    // Small pure helpers — kept public so the JVM unit tests can pin their behaviour.
    // -------------------------------------------------------------------------------------

    /** Parses a `Content-Length` header, or null when absent/garbage. */
    fun contentLength(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    /** Byte offset of `Content-Range: bytes 512-1023/1024` — i.e. where the resume starts. */
    fun contentRangeStart(value: String?): Long? {
        val match = Regex("""bytes\s+(\d+)-\d+/\d+""").find(value ?: return null) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    /** Last path segment of a URL, used to name downloaded files; never empty. */
    fun fileName(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val name = path.substringAfterLast('/')
        return name.ifBlank { "download" }
    }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"

    /** Reads a whole stream without trusting `available()` (slow networks return short reads). */
    fun readFully(stream: InputStream): ByteArray = stream.use { it.readBytes() }

    /**
     * Suspending-free retry with exponential backoff, used for metadata calls.
     *
     * Returns null when every attempt failed; callers decide whether that is fatal. Only
     * [IOException] is retried — an [HttpException] with a 4xx code is a permanent answer and
     * retrying it would just waste the user's battery.
     */
    fun <T> runCatchingWithRetries(
        attempts: Int = 3,
        initialDelayMs: Long = 400,
        block: (Int) -> T,
    ): T? {
        var lastError: Throwable? = null
        for (attempt in 1..attempts.coerceAtLeast(1)) {
            try {
                return block(attempt)
            } catch (e: HttpException) {
                lastError = e
                if (e.code in 400..499) break
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < attempts) {
                runCatching { Thread.sleep(initialDelayMs shl (attempt - 1)) }
            }
        }
        lastError?.let { IbbLog.w("Http", "Request failed after $attempts attempts", it) }
        return null
    }

    private fun readFully(stream: InputStream, encoding: String?): String {
        val decoded = if (encoding?.contains("gzip", ignoreCase = true) == true) GZIPInputStream(stream) else stream
        return decoded.use { it.readBytes().decodeToString() }
    }

}

/** TLS helper — a single shared factory avoids re-negotiating trust stores per request. */
private object Tls {
    val factory: javax.net.ssl.SSLSocketFactory
        get() = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
}
