package org.futo.inputmethod.latin.uix.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class KlipyGifPipeTest {
    @Test
    fun oneShot_consumeOnce() {
        val store = KlipyGifPipe.PendingUrls(ttlMs = 60_000, cap = 4)
        val id = UUID.randomUUID()
        store.put(id, "https://static.klipy.com/x.gif", now = 1000)
        assertEquals("https://static.klipy.com/x.gif", store.take(id, now = 2000))
        assertNull(store.take(id, now = 2001))
        assertEquals(0, store.size())
    }

    @Test
    fun expiredEntry_returnsNull() {
        val store = KlipyGifPipe.PendingUrls(ttlMs = 60_000, cap = 4)
        val id = UUID.randomUUID()
        store.put(id, "https://static.klipy.com/x.gif", now = 1000)
        assertNull(store.take(id, now = 1000 + 61_000))
    }

    @Test
    fun capEvictsOldest_andPutPrunesExpired() {
        val store = KlipyGifPipe.PendingUrls(ttlMs = 60_000, cap = 3)
        val ids = (1..4).map { UUID.randomUUID() }
        ids.forEachIndexed { i, id -> store.put(id, "https://static.klipy.com/$i.gif", now = 1000L + i) }
        assertEquals(3, store.size())
        assertNull(store.take(ids[0], now = 2000)) // evicted as oldest
        assertEquals("https://static.klipy.com/3.gif", store.take(ids[3], now = 2000))
        // stale entries pruned on next put
        ids.forEachIndexed { i, id -> store.put(id, "https://static.klipy.com/n$i.gif", now = 100_000L + i) }
        assertEquals(3, store.size())
    }

    @Test
    fun urlAllowlist() {
        assertTrue(KlipyGifPipe.isAllowedUrl("https://static.klipy.com/a/b.gif"))
        assertTrue(KlipyGifPipe.isAllowedUrl("https://static2.klipy.com/a.gif"))
        assertTrue(!KlipyGifPipe.isAllowedUrl("http://static.klipy.com/a.gif"))
        assertTrue(!KlipyGifPipe.isAllowedUrl("https://evil.com/a.gif"))
        assertTrue(!KlipyGifPipe.isAllowedUrl("https://static.klipy.com.evil.com/a.gif"))
        assertTrue(!KlipyGifPipe.isAllowedUrl("not a url"))
    }
}
