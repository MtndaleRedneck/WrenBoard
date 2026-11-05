package org.futo.inputmethod.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** WrenBoard 1.6 (item 11): the two feedback routes carry exactly the preview's fields, to the right places. */
class FeedbackUrlTest {
    private val c = FeedbackContext("1.6.0", "14 (API 34)", "Google sdk_gphone64_x86_64", "font scale 1.00, 450 dpi (xxhdpi), screen 384x823 dp")

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate {
            val (k, v) = (it.split('=', limit = 2) + "").take(2)
            k to URLDecoder.decode(v, "UTF-8")
        }

    @Test fun formUrlPrefillsTheFourFieldsAndLeavesEmailBlank() {
        val url = Feedback.formUrl("Space bar lags & 100% sure", c)
        assertTrue(url.startsWith("https://docs.google.com/forms/d/e/1FAIpQLSdgcWZuuFsv2z0hUlCfWbkVJ8RMsWI3Rwc5oP0WK89s6dCiOA/viewform?usp=pp_url&"))
        val p = params(url)
        assertEquals("pp_url", p["usp"])
        assertEquals("Space bar lags & 100% sure\n\nDisplay: font scale 1.00, 450 dpi (xxhdpi), screen 384x823 dp", p["entry.755520238"])
        assertEquals("Google sdk_gphone64_x86_64", p["entry.793045318"])
        assertEquals("14 (API 34)", p["entry.1132607697"])
        assertEquals("1.6.0", p["entry.904027313"])
        assertFalse(p.containsKey("entry.1123297416"))
        assertEquals(5, p.size)
    }

    @Test fun githubUrlUsesBingeMoteTitleAndBody() {
        val url = Feedback.githubUrl("Keys flicker\nsecond line", c)
        assertTrue(url.startsWith("https://github.com/MtndaleRedneck/WrenBoard/issues/new?title="))
        val p = params(url)
        assertEquals("Feedback: Keys flicker", p["title"])
        assertEquals("Keys flicker\nsecond line\n\n---\nApp version: 1.6.0\nAndroid version: 14 (API 34)\nPhone model: Google sdk_gphone64_x86_64\nDisplay: font scale 1.00, 450 dpi (xxhdpi), screen 384x823 dp", p["body"])
    }

    @Test fun longTextIsCappedAndPreviewMatchesWhatIsSent() {
        val long = "é".repeat(5000)
        val t = Feedback.clean(long)
        assertTrue(t.length <= 3000)
        assertTrue(java.net.URLEncoder.encode(t, "UTF-8").length <= 4500)
        assertTrue(Feedback.preview(long, c).startsWith("Message:\n$t\n\nApp version: 1.6.0"))
        assertEquals("Message:\n(nothing written yet)\n\nApp version: 1.6.0\nAndroid version: 14 (API 34)\nPhone model: Google sdk_gphone64_x86_64\nDisplay: font scale 1.00, 450 dpi (xxhdpi), screen 384x823 dp",
            Feedback.preview("  ", c))
    }

    @Test fun signerRuleMatchesBingeMote() {
        val a = setOf(listOf<Byte>(1, 2)); val b = setOf(listOf<Byte>(3, 4))
        assertTrue(UpdateVerify.signersAcceptable(a, a, a))
        assertFalse(UpdateVerify.signersAcceptable(a, b, b))
        assertTrue(UpdateVerify.signersAcceptable(a, b, a + b))
        assertFalse(UpdateVerify.signersAcceptable(emptySet(), a, a))
    }

    @Test fun downloadHostsAreGitHubOnly() {
        assertTrue(UpdateVerify.hostAllowed("https://github.com/MtndaleRedneck/WrenBoard/releases/download/v1.6.0/WrenBoard.apk"))
        assertTrue(UpdateVerify.hostAllowed("https://objects.githubusercontent.com/x"))
        assertFalse(UpdateVerify.hostAllowed("http://github.com/x"))
        assertFalse(UpdateVerify.hostAllowed("https://evilgithubusercontent.com/x"))
        assertFalse(UpdateVerify.hostAllowed("https://keyboard.futo.tech/x"))
    }

    @Test fun releasesListPicksNewestAllowedApkWithDigest() {
        val body = javaClass.getResource("/wrenboard_releases_sample.json")!!.readText()
        val r = newestUsableRelease(body)!!
        assertEquals("1.5.0", r.nextVersionString)
        assertEquals(1005000, r.nextVersion)
        assertEquals("https://github.com/MtndaleRedneck/WrenBoard/releases/download/v1.5.0/WrenBoard-1.5.apk", r.apkUrl)
        assertEquals(129403471L, r.size)
        assertEquals("2b6cb34a8a6193a6a4f9acae7170f664ead08374a5675285ab6e9591f394d404", r.sha256)
    }

    @Test fun releaseFromAnotherHostIsIgnored() {
        val body = """[{"tag_name":"v9.9.9","draft":false,"prerelease":false,"assets":[{"name":"x.apk","size":10,"browser_download_url":"https://example.com/x.apk"}]},
            {"tag_name":"v9.0.0","draft":true,"prerelease":false,"assets":[{"name":"x.apk","size":10,"browser_download_url":"https://github.com/MtndaleRedneck/WrenBoard/releases/download/v9.0.0/x.apk"}]}]"""
        assertEquals(null, newestUsableRelease(body))
    }
}
