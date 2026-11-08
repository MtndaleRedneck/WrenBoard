package org.futo.inputmethod.latin.uix.gif

import org.futo.inputmethod.latin.uix.gif.KlipyClient.KlipyFileSet
import org.futo.inputmethod.latin.uix.gif.KlipyClient.KlipyGif
import org.futo.inputmethod.latin.uix.gif.KlipyClient.KlipyVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 1.7: tile-sharp animated grid previews (KlipyGif.gridPreview) and the distinct
 * supported animated MIME insertion pick (KlipyInsert.pickVariant). Pure Kotlin/JVM.
 */
class KlipyGifSelectionTest {

    private fun v(url: String, w: Int = 0, h: Int = 0, size: Long = 0) =
        KlipyVariant(url = url, width = w, height = h, size = size)

    private fun gifWith(file: Map<String, KlipyFileSet>) = KlipyGif(id = 1, slug = "s", title = "t", file = file)

    // ---------- gridPreview ----------

    @Test
    fun preview_prefersMdAnimatedWebp_overTinyJpg() {
        val g = gifWith(mapOf(
            "xs" to KlipyFileSet(jpg = v("https://static.klipy.com/xs.jpg", 90, 90, 1_000)),
            "md" to KlipyFileSet(
                webp = v("https://static.klipy.com/md.webp", 498, 280, 40_000),
                gif = v("https://static.klipy.com/md.gif", 498, 280, 90_000),
                jpg = v("https://static.klipy.com/md.jpg", 498, 280, 8_000))
        ))
        assertEquals("https://static.klipy.com/md.webp", g.gridPreview()?.url)
    }

    @Test
    fun preview_mdGifWhenNoMdWebp() {
        val g = gifWith(mapOf(
            "md" to KlipyFileSet(gif = v("https://static.klipy.com/md.gif", 498, 280, 90_000))
        ))
        assertEquals("https://static.klipy.com/md.gif", g.gridPreview()?.url)
    }

    @Test
    fun preview_noMdTier_picksSmallestAnimatedAtLeastMinWidth() {
        val g = gifWith(mapOf(
            "xs" to KlipyFileSet(webp = v("https://static.klipy.com/xs.webp", 200, 200, 5_000)),
            "lg" to KlipyFileSet(webp = v("https://static.klipy.com/lg.webp", 640, 640, 50_000)),
            "sm" to KlipyFileSet(webp = v("https://static.klipy.com/sm.webp", 500, 500, 20_000))
        ))
        assertEquals("https://static.klipy.com/sm.webp", g.gridPreview()?.url)
    }

    @Test
    fun preview_allBelowMinWidth_picksWidestAnimated() {
        val g = gifWith(mapOf(
            "xs" to KlipyFileSet(gif = v("https://static.klipy.com/xs.gif", 100, 100, 3_000)),
            "sm" to KlipyFileSet(gif = v("https://static.klipy.com/sm.gif", 300, 300, 9_000))
        ))
        assertEquals("https://static.klipy.com/sm.gif", g.gridPreview()?.url)
    }

    @Test
    fun preview_noAnimatedAnywhere_fallsBackToSmallestJpg() {
        val g = gifWith(mapOf(
            "md" to KlipyFileSet(jpg = v("https://static.klipy.com/md.jpg", 498, 280, 8_000)),
            "xs" to KlipyFileSet(jpg = v("https://static.klipy.com/xs.jpg", 90, 90, 1_000))
        ))
        assertEquals("https://static.klipy.com/xs.jpg", g.gridPreview()?.url)
    }

    // ---------- pickVariant ----------

    private val BOTH = gifWith(mapOf(
        "hd" to KlipyFileSet(
            gif = v("https://static.klipy.com/hd.gif", 640, 360, 3_000_000),
            mp4 = v("https://static.klipy.com/hd.mp4", 640, 360, 700_000)),
        "md" to KlipyFileSet(
            gif = v("https://static.klipy.com/md.gif", 498, 280, 1_200_000),
            mp4 = v("https://static.klipy.com/md.mp4", 498, 280, 300_000))
    ))

    @Test
    fun pick_gifPreferredWhenBothAccepted() {
        val pick = KlipyInsert.pickVariant(BOTH) { it == "video/mp4" || it == "image/gif" }
        assertEquals("image/gif", pick?.second)
        assertEquals("https://static.klipy.com/hd.gif", pick?.first?.url)
    }

    @Test
    fun pick_noVideoSubstitutionWhenGifAcceptedButUnavailable() {
        val mp4Only = gifWith(mapOf("hd" to KlipyFileSet(
            mp4 = v("https://static.klipy.com/hd.mp4", 640, 360, 700_000))))
        assertNull(KlipyInsert.pickVariant(mp4Only) { true })
    }

    @Test
    fun pick_gifWhenOnlyGifAccepted() {
        val pick = KlipyInsert.pickVariant(BOTH) { it == "image/gif" }
        assertEquals("image/gif", pick?.second)
        assertEquals("https://static.klipy.com/hd.gif", pick?.first?.url)
    }

    @Test
    fun pick_gifFallbackWhenNoMp4Variant() {
        val gifOnly = gifWith(mapOf(
            "md" to KlipyFileSet(gif = v("https://static.klipy.com/md.gif", 498, 280, 1_200_000))))
        val pick = KlipyInsert.pickVariant(gifOnly) { true }
        assertEquals("image/gif", pick?.second)
    }

    @Test
    fun pick_mp4WhenGifNotAccepted() {
        val pick = KlipyInsert.pickVariant(BOTH) { it == "video/mp4" }
        assertEquals("video/mp4", pick?.second)
    }

    @Test
    fun pick_nullWhenNeitherAccepted() {
        assertNull(KlipyInsert.pickVariant(BOTH) { false })
    }

    @Test
    fun pick_rejectsNonKlipyHosts() {
        val evil = gifWith(mapOf(
            "hd" to KlipyFileSet(
                gif = v("https://evil.com/hd.gif", 640, 360, 1_000),
                mp4 = v("https://evil.com/hd.mp4", 640, 360, 1_000))))
        assertNull(KlipyInsert.pickVariant(evil) { true })
    }
}
