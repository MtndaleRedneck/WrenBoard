package org.futo.inputmethod.latin.uix.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WrenBoard 1.5 (item 1): parser pinned to the documented KLIPY response shape. */
class KlipyClientTest {
    private val sample = """
    {
      "result": true,
      "data": {
        "data": [
          {
            "id": 8041071659142944,
            "slug": "hello-hi-662",
            "title": "Hello",
            "file": {
              "hd": {
                "gif": { "url": "https://static.klipy.com/ii/x/14/af/um0L4dFH.gif", "width": 498, "height": 498, "size": 4001918 },
                "webp": { "url": "https://static.klipy.com/ii/x/14/af/eUbp2uNc.webp", "width": 498, "height": 498, "size": 285228 },
                "jpg": { "url": "https://static.klipy.com/ii/x/14/af/LyWpim71.jpg", "width": 498, "height": 498, "size": 19255 },
                "mp4": { "url": "https://static.klipy.com/ii/x/14/af/MCCBoQlZ.mp4", "width": 498, "height": 498, "size": 119294 }
              },
              "md": {
                "gif": { "url": "https://static.klipy.com/ii/x/14/af/8GCrVAB7.gif", "width": 498, "height": 498, "size": 3721260 },
                "jpg": { "url": "https://static.klipy.com/ii/x/14/af/mdSmall.jpg", "width": 240, "height": 240, "size": 9000 }
              }
            }
          }
        ]
      }
    }
    """.trimIndent()

    @Test fun parsesDocumentedShape() {
        val page = KlipyClient.parsePage(sample)
        assertEquals(1, page.size)
        val gif = page[0]
        assertEquals("hello-hi-662", gif.slug)
        assertEquals(8041071659142944L, gif.id)
        assertEquals("https://static.klipy.com/ii/x/14/af/um0L4dFH.gif", gif.fullGif()?.url)
        // Lightest tier (md jpg, 9000 bytes) wins for the grid preview.
        assertEquals("https://static.klipy.com/ii/x/14/af/mdSmall.jpg", gif.gridPreview()?.url)
    }

    @Test fun failedResultYieldsEmpty() {
        assertTrue(KlipyClient.parsePage("""{"result": false}""").isEmpty())
        assertTrue(KlipyClient.parsePage("""{"result": true, "data": {}}""").isEmpty())
    }

    @Test fun unknownFieldsIgnored() {
        val withExtra = sample.replace("\"slug\": \"hello-hi-662\"",
            "\"slug\": \"hello-hi-662\", \"some_future_field\": {\"nested\": [1, 2]}")
        assertEquals(1, KlipyClient.parsePage(withExtra).size)
    }
}
