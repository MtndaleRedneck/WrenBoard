package org.futo.inputmethod.latin.uix.gif

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import org.futo.inputmethod.latin.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * WrenBoard 1.5 (item 1): in-memory pipe for GIF insertion.
 *
 * The agreed Nov 1 flow: insert straight into the field via commitContent through our own
 * ContentProvider piping KLIPY bytes, nothing kept on disk. The URL lives only in this
 * process's memory between the user's tap and the target app reading the stream; the bytes
 * are fetched directly from KLIPY's static host at read time and are never written to disk
 * or retained after the stream closes. (KLIPY's written OK on this pipe approach is still an
 * open item; the implementation follows their published "load media directly" requirement.)
 *
 * Only KLIPY's documented media hosts are accepted, so the provider cannot be turned into a
 * general open proxy by a crafted URI.
 */
object KlipyGifPipe {
    val AUTHORITY = BuildConfig.APPLICATION_ID + ".font"
    private val ALLOWED_HOSTS = setOf("static.klipy.com", "static1.klipy.com", "static2.klipy.com")

    /** One-shot URL store, bounded: entries expire and the oldest are evicted past the cap.
     * Pure Kotlin for unit tests. */
    class PendingUrls(private val ttlMs: Long = 120_000L, private val cap: Int = 8) {
        private data class Entry(val url: String, val at: Long)
        private val pending = LinkedHashMap<UUID, Entry>()
        private val lock = Any()

        fun put(id: UUID, url: String, now: Long = System.currentTimeMillis()) {
            synchronized(lock) {
                pending.entries.removeAll { now - it.value.at > ttlMs }
                while (pending.size >= cap) pending.remove(pending.keys.first())
                pending[id] = Entry(url, now)
            }
        }

        /** One-shot: removes and returns the URL, or null when unknown, consumed or expired. */
        fun take(id: UUID, now: Long = System.currentTimeMillis()): String? = synchronized(lock) {
            val e = pending.remove(id) ?: return@synchronized null
            if (now - e.at > ttlMs) null else e.url
        }

        fun size(): Int = synchronized(lock) { pending.size }
    }

    /** Never buffer more than this for one insertion; KLIPY md/hd GIFs are normally 1-5 MB. */
    private const val MAX_GIF_BYTES = 8 * 1024 * 1024

    private val pending = PendingUrls()

    /** One fetch at a time: no unbounded thread growth from rapid taps; the target app reads one stream. */
    private val fetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "klipy-gif-fetch").apply { isDaemon = true }
    }

    fun isAllowedUrl(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && ALLOWED_HOSTS.contains(u.host)
    } catch (_: Exception) { false }

    /** Register a full-GIF URL for one insertion; returns the content:// URI to hand to commitContent. */
    fun register(url: String): Uri {
        require(isAllowedUrl(url)) { "Refusing non-KLIPY media URL" }
        val id = UUID.randomUUID()
        pending.put(id, url)
        return Uri.parse("content://$AUTHORITY/gif/$id")
    }

    fun mimeTypeFor(@Suppress("UNUSED_PARAMETER") uri: Uri): String = "image/gif"

    /** Called by KbContentProvider.openFile for content://.../gif/<uuid>. */
    fun openStream(id: UUID): ParcelFileDescriptor {
        val url = pending.take(id)
            ?: throw IllegalArgumentException("Unknown, expired or already-consumed GIF request")
        // Reliable pipe (API 19+, every device we support): the reader sees every byte written
        // before any EOF/error; the writer blocks on a full buffer instead of over-producing.
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val read = pipe[0]
        val write = pipe[1]
        fetchExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                // Fetch the whole GIF into memory BEFORE writing anything: the reader only ever
                // sees a complete GIF or a failure - never a decodable truncated stream (a
                // mid-transfer failure after HTTP 200 would otherwise pass as a clean EOF).
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "WrenBoard-GIF")
                }
                val code = connection!!.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("KLIPY media HTTP $code")
                val declared = connection!!.contentLength // -1 when unknown
                if (declared > MAX_GIF_BYTES) throw IOException("GIF too large: $declared bytes")
                val bytes = connection!!.inputStream.use { input ->
                    val buf = java.io.ByteArrayOutputStream(declared.coerceIn(8192, MAX_GIF_BYTES).coerceAtLeast(8192))
                    val chunk = ByteArray(16384)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (buf.size() + n > MAX_GIF_BYTES) throw IOException("GIF over $MAX_GIF_BYTES bytes")
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    out.write(bytes)
                    out.flush()
                } // clean close = complete content, EOF is truthful
            } catch (e: Exception) {
                // closeWithError (API 19+, minSdk 24) attaches the error to the pipe; a reader
                // calling checkError() at EOF sees the failure instead of a clean EOF (a reader
                // that never checks still sees a plain close - that is the reader's contract).
                try {
                    write.closeWithError("GIF fetch failed: ${e.javaClass.simpleName}")
                } catch (_: Exception) { }
            } finally {
                connection?.disconnect()
            }
        }
        return read
    }
}
