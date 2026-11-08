package org.futo.inputmethod.latin.uix.gif

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSettingBlocking
import androidx.datastore.preferences.core.stringPreferencesKey
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * WrenBoard 1.5 (item 1): KLIPY GIF API client (https://docs.klipy.com/gifs-api).
 *
 * Terms-driven constraints baked in here:
 * - Requests originate from the end-user client (this app); no server proxy or re-hosting.
 * - Results are shown in the returned order and composition; no client-side re-filtering.
 * - Strongest safety level: content_filter=high (agreed Nov 1).
 * - The search field must carry the "Search KLIPY" placeholder (required attribution) - enforced
 *   in the UI layer (WrenGifTab), noted here so it is not lost.
 * - Grid thumbnails may be cached inside the user's copy of the app (allowed); full media is
 *   loaded directly from the returned static.klipy.com URLs and piped to the target app at
 *   insert time with nothing kept on disk (the written-OK-on-pipe item stays open until KLIPY
 *   confirms in writing; the flow is built to their published "load directly" wording).
 */
object KlipyClient {
    private const val BASE = "https://api.klipy.com/api/v1"
    const val CONTENT_FILTER = "high"
    const val PER_PAGE = 24

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class KlipyVariant(
        val url: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val size: Long = 0
    )

    @Serializable
    data class KlipyFileSet(
        val gif: KlipyVariant? = null,
        val webp: KlipyVariant? = null,
        val jpg: KlipyVariant? = null,
        val mp4: KlipyVariant? = null,
        val webm: KlipyVariant? = null
    )

    /** 1.7: a grid tile is sharp when its preview is at least this wide (2-col grid, xxhdpi). */
    const val PREVIEW_MIN_WIDTH = 480

    @Serializable
    data class KlipyGif(
        val id: Long = 0,
        val slug: String = "",
        val title: String = "",
        /** Tier name ("hd", "md", ...) -> formats. Tier names are not enumerated in the docs, so keep the map. */
        val file: Map<String, KlipyFileSet> = emptyMap()
    ) {
        /** Full-size GIF for insertion. */
        fun fullGif(): KlipyVariant? = file["hd"]?.gif ?: file["md"]?.gif
        /** 1.7: full-size MP4 for the distinct video/mp4 insertion path. */
        fun fullMp4(): KlipyVariant? = file["hd"]?.mp4 ?: file["md"]?.mp4
        /**
         * 1.7 grid preview: animated and sharp, never a tiny still when an animated variant
         * exists. Preference order: the md tier's animated webp, then md gif (webp is smaller
         * at equal quality); otherwise the smallest animated variant at least
         * [PREVIEW_MIN_WIDTH] px wide, or the widest animated variant when every tier is
         * smaller than that; a still jpg only when KLIPY returned no animated variant at all.
         */
        fun gridPreview(): KlipyVariant? {
            file["md"]?.let { set ->
                set.webp?.takeIf { it.url.isNotEmpty() }?.let { return it }
                set.gif?.takeIf { it.url.isNotEmpty() }?.let { return it }
            }
            val animated = file.values.flatMap { set ->
                listOfNotNull(set.webp, set.gif)
            }.filter { it.url.isNotEmpty() }
            if (animated.isNotEmpty()) {
                animated.sortedBy { if (it.size > 0) it.size else Long.MAX_VALUE }
                    .firstOrNull { it.width >= PREVIEW_MIN_WIDTH }?.let { return it }
                animated.maxByOrNull { it.width }?.let { return it }
            }
            return file.values.mapNotNull { it.jpg }
                .filter { it.url.isNotEmpty() }
                .minByOrNull { if (it.size > 0) it.size else Long.MAX_VALUE }
        }
    }

    @Serializable
    private data class KlipyData(val data: List<KlipyGif> = emptyList())

    @Serializable
    private data class KlipyResponse(val result: Boolean = false, val data: KlipyData? = null)

    /** Visible for tests: parse one API page body. Throws on malformed JSON. */
    fun parsePage(body: String): List<KlipyGif> {
        val response = json.decodeFromString<KlipyResponse>(body)
        if (!response.result) return emptyList()
        return response.data?.data ?: emptyList()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun buildUrl(appKey: String, path: String, params: Map<String, String>): String {
        val qs = params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        return "$BASE/${enc(appKey)}/$path?$qs"
    }

    private suspend fun getPage(url: String): List<KlipyGif> = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WrenBoard-GIF")
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parsePage(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun pagingParams(customerId: String, page: Int, perPage: Int, locale: String?) =
        mutableMapOf(
            "page" to page.toString(),
            "per_page" to perPage.toString(),
            "customer_id" to customerId,
            "content_filter" to CONTENT_FILTER
        ).also { m -> if (!locale.isNullOrBlank()) m["locale"] = locale }

    suspend fun search(appKey: String, customerId: String, query: String, page: Int = 1,
                       perPage: Int = PER_PAGE, locale: String? = null): List<KlipyGif> =
        getPage(buildUrl(appKey, "gifs/search", pagingParams(customerId, page, perPage, locale)
            .also { it["q"] = query }))

    suspend fun trending(appKey: String, customerId: String, page: Int = 1,
                         perPage: Int = PER_PAGE, locale: String? = null): List<KlipyGif> =
        getPage(buildUrl(appKey, "gifs/trending", pagingParams(customerId, page, perPage, locale)))

    suspend fun recent(appKey: String, customerId: String, page: Int = 1,
                       perPage: Int = PER_PAGE): List<KlipyGif> =
        getPage("$BASE/${enc(appKey)}/gifs/recent/${enc(customerId)}" +
            "?page=$page&per_page=$perPage")

    /** Register a share so KLIPY's per-user recents and trending stay accurate. Fire-and-forget. */
    suspend fun registerShare(appKey: String, slug: String) = withContext(Dispatchers.IO) {
        try {
            val connection = (URL("$BASE/${enc(appKey)}/gifs/share/${enc(slug)}")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "WrenBoard-GIF")
            }
            try { connection.responseCode } finally { connection.disconnect() }
        } catch (_: Exception) { }
    }
}

/** Stable per-install customer id KLIPY asks for; random, not tied to any identity. */
val KLIPY_CUSTOMER_ID = SettingsKey(stringPreferencesKey("klipy_customer_id"), "")

fun klipyCustomerId(context: Context): String {
    val existing = context.getSettingBlocking(KLIPY_CUSTOMER_ID)
    if (existing.isNotEmpty()) return existing
    val created = UUID.randomUUID().toString()
    context.setSettingBlocking(KLIPY_CUSTOMER_ID.key, created)
    return created
}
