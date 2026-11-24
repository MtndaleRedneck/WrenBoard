/*
 * WrenBoard 1.10 diagnostics ONLY (not part of the candidate tree's shipped app; added on the
 * relruntime branch under the frozen-source guard allowlist).
 *
 * Probe: do setBackDisposition pushes reach the system? Pushes WILL_NOT_DISMISS,
 * ADJUST_NOTHING, then DEFAULT in sequence, and after each logs the observable downstream
 * fields (DisplayPolicy.mDismissImeOnBackKeyPressed via `dumpsys window`, IME visibility via
 * `dumpsys input_method`). Log-only: no assertions on system state - timing, token and
 * session factors are interpreted off-device from the raw log, not asserted here.
 */
package org.futo.inputmethod.latin;

import android.app.Instrumentation;
import android.inputmethodservice.InputMethodService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@LargeTest
public class Wren110BackDispositionProbeTest extends InputTestsBase {

    private static final String TAG = "WBPROBE";

    private static String shell(final String cmd) throws Exception {
        final Instrumentation instr = InstrumentationRegistry.getInstrumentation();
        final ParcelFileDescriptor pfd = instr.getUiAutomation().executeShellCommand(cmd);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String grepFirst(final String dump, final String needle) {
        for (final String line : dump.split("\n")) {
            if (line.contains(needle)) return line.trim();
        }
        return needle + " absent";
    }

    private static void logState(final String stage) throws Exception {
        final String window = shell("dumpsys window");
        final String im = shell("dumpsys input_method");
        Log.i(TAG, "PROBE " + stage
                + " | " + grepFirst(window, "mDismissImeOnBackKeyPressed")
                + " | " + grepFirst(im, "mImeWindowVis"));
    }

    public void testDispositionPipelineProbe() throws Exception {
        Log.i(TAG, "PROBE begin getBackDisposition=" + getService().getBackDisposition());
        logState("baseline");
        getService().setBackDisposition(InputMethodService.BACK_DISPOSITION_WILL_NOT_DISMISS);
        Thread.sleep(3000);
        logState("after-WILL_NOT_DISMISS");
        getService().setBackDisposition(InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING);
        Thread.sleep(3000);
        logState("after-ADJUST_NOTHING");
        getService().setBackDisposition(InputMethodService.BACK_DISPOSITION_DEFAULT);
        Thread.sleep(2000);
        logState("after-DEFAULT");
        Log.i(TAG, "PROBE end");
    }
}
