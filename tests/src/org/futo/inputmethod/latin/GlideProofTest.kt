package org.futo.inputmethod.latin

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.futo.inputmethod.keyboard.KeyboardSwitcher
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import kotlin.math.hypot
import kotlin.math.max

/**
 * WrenBoard glide proof (test-only). Runs the REAL WrenBoard IME (stableDebug, bundled FUTO Swipe model)
 * on the emulator at the parity display (1080x2316 @ 450 dpi) and glides each word through
 * UiAutomation with a smooth, densely sampled finger path (one MotionEvent every 12 ms at 1.5 px/ms
 * between the key centres read from the live keyboard view), instead of ~34 separate
 * `input motionevent` shell processes. Each word is glided TRIALS times in a fresh text field; every
 * trial's committed text is logged under tag GLIDEPROOF and must equal the word exactly.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GlideProofTest {
    companion object {
        const val TAG = "GLIDEPROOF"
        const val TRIALS = 3
        const val SAMPLE_MS = 12L
        const val PX_PER_MS = 1.5
        const val MIN_SEGMENT_MS = 70.0

        private val instr get() = InstrumentationRegistry.getInstrumentation()

        fun sh(cmd: String): String {
            val pfd = instr.uiAutomation.executeShellCommand(cmd)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }

        @BeforeClass
        @JvmStatic
        fun setUpDevice() {
            val pkg = instr.targetContext.packageName
            val ime = "$pkg/org.futo.inputmethod.latin.LatinIME"
            Log.i(TAG, "SETUP target=$pkg ime=$ime")
            Log.i(TAG, "SETUP " + sh("wm size 1080x2316").trim() + " " + sh("wm density 450").trim())
            sh("settings put system screen_off_timeout 1800000")
            sh("svc power stayon true")
            sh("input keyevent KEYCODE_WAKEUP")
            sh("wm dismiss-keyguard")
            Log.i(TAG, "SETUP enable: " + sh("ime enable $ime").trim())
            Log.i(TAG, "SETUP set: " + sh("ime set $ime").trim())
            SystemClock.sleep(2000)
            Log.i(TAG, "SETUP default_input_method=" + sh("settings get secure default_input_method").trim())
            Log.i(TAG, "SETUP " + sh("wm size").trim().replace('\n', ' ') + " | " + sh("wm density").trim().replace('\n', ' '))
        }
    }

    private val device: UiDevice = UiDevice.getInstance(instr)
    private val testPkg: String = instr.context.packageName

    private fun openField(): UiObject2 {
        val intent = Intent().setClassName(testPkg, "org.futo.inputmethod.latin.GlideProofActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instr.context.startActivity(intent)
        var field: UiObject2? = null
        val t0 = SystemClock.uptimeMillis()
        while (field == null && SystemClock.uptimeMillis() - t0 < 15000) {
            field = device.findObject(By.desc("wb_glide_field").pkg(testPkg))
            if (field == null) SystemClock.sleep(300)
        }
        if (field == null) throw AssertionError("text field did not appear")
        field.click()
        SystemClock.sleep(1500)
        return field
    }

    /** Screen-pixel key centres of the letters, read from the live WrenBoard keyboard view. */
    private fun keyCentres(): Map<Char, Pair<Float, Float>> {
        var out: Map<Char, Pair<Float, Float>> = emptyMap()
        val t0 = SystemClock.uptimeMillis()
        while (out.size < 26 && SystemClock.uptimeMillis() - t0 < 20000) {
            instr.runOnMainSync {
                val view = KeyboardSwitcher.getInstance().mainKeyboardView
                val kb = view?.keyboard
                if (view != null && kb != null && view.isShown && view.width > 0) {
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    val m = HashMap<Char, Pair<Float, Float>>()
                    for (key in kb.sortedKeys) {
                        val c = key.code
                        if (c in 'a'.code..'z'.code) {
                            m[c.toChar()] = Pair(loc[0] + view.paddingLeft + key.x + key.width / 2.0f,
                                loc[1] + view.paddingTop + key.y + key.height / 2.0f)
                        }
                    }
                    out = m
                }
            }
            if (out.size < 26) SystemClock.sleep(500)
        }
        if (out.size < 26) throw AssertionError("keyboard letters not found in the live view (${out.size} of 26)")
        return out
    }

    private fun inject(action: Int, downTime: Long, x: Float, y: Float) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            if (!instr.uiAutomation.injectInputEvent(ev, true)) throw AssertionError("injectInputEvent refused")
        } finally {
            ev.recycle()
        }
    }

    private fun glide(word: String, keys: Map<Char, Pair<Float, Float>>): String {
        val pts = ArrayList<Pair<Float, Float>>()
        for (ch in word) {
            val p = keys[ch] ?: throw AssertionError("no key for '$ch'")
            if (pts.isEmpty() || pts.last() != p) pts.add(p)
        }
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, pts[0].first, pts[0].second)
        var moves = 0
        for (i in 1 until pts.size) {
            val (ax, ay) = pts[i - 1]
            val (bx, by) = pts[i]
            val segMs = max(MIN_SEGMENT_MS, hypot((bx - ax).toDouble(), (by - ay).toDouble()) / PX_PER_MS)
            val steps = max(1, (segMs / SAMPLE_MS).toInt())
            for (s in 1..steps) {
                SystemClock.sleep(SAMPLE_MS)
                val f = s.toFloat() / steps
                inject(MotionEvent.ACTION_MOVE, down, ax + (bx - ax) * f, ay + (by - ay) * f)
                moves++
            }
        }
        SystemClock.sleep(SAMPLE_MS)
        inject(MotionEvent.ACTION_UP, down, pts.last().first, pts.last().second)
        val dur = SystemClock.uptimeMillis() - down
        Log.i(TAG, "GESTURE word=$word keys=${pts.map { "(${it.first.toInt()},${it.second.toInt()})" }} moves=$moves duration_ms=$dur")
        // settle: the field text must read the same twice, 700 ms apart
        var last = ""
        var same = 0
        val t0 = SystemClock.uptimeMillis()
        SystemClock.sleep(1200)
        while (same < 2 && SystemClock.uptimeMillis() - t0 < 12000) {
            val now = device.findObject(By.desc("wb_glide_field").pkg(testPkg))?.text ?: ""
            if (now == last) same++ else { same = 0; last = now }
            SystemClock.sleep(700)
        }
        return last
    }

    private fun runWord(word: String) {
        val results = ArrayList<String>()
        for (trial in 1..TRIALS) {
            openField()
            val keys = keyCentres()
            if (trial == 1) Log.i(TAG, "KEYMAP " + keys.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value.first.toInt()},${it.value.second.toInt()}" })
            val got = glide(word, keys)
            val ok = got.trim() == word
            Log.i(TAG, "RESULT word=$word trial=$trial field='$got' ${if (ok) "PASS" else "FAIL"}")
            results.add(got)
        }
        Log.i(TAG, "SUMMARY word=$word fields=$results")
        assertTrue("glide '$word': fields $results (every trial must be exactly '$word')",
            results.all { it.trim() == word })
    }

    @Test fun glide1_hello() = runWord("hello")
    @Test fun glide2_world() = runWord("world")
    @Test fun glide3_keyboard() = runWord("keyboard")
    @Test fun glide4_thanks() = runWord("thanks")
}
