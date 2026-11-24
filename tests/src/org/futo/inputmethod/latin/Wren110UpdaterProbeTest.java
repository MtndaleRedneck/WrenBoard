/*
 * WrenBoard 1.10 diagnostics ONLY (relruntime branch, frozen-source guard allowlist; not in e059314).
 *
 * Bounded updater-lifecycle probe authorized 2026-11-23. Drives the REAL production paths where
 * reachable (WBDBG seedupdate:/settings: hooks, UpdateStatus, the private production
 * launchInstaller via reflection, the real UpdateDialog ON_RESUME observer). Bypassed glue,
 * labeled: the HTTP fetch of the APK and its published-checksum comparison never run; the
 * "downloaded" file is staged by the probe. UpdateVerify's package/signer/newer checks DO run
 * where the path includes them.
 *
 * Log-only except the two fail-closed asserts marked below. No network, no real install, no
 * publication. Synthetic clock values in T4 say nothing about actual 10-minute UI timing.
 */
package org.futo.inputmethod.latin;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.content.FileProvider;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.futo.inputmethod.latin.uix.settings.UpdateStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

@LargeTest
public class Wren110UpdaterProbeTest extends InputTestsBase {

    private static final String TAG = "WBUPD";
    private static final String PKG = "com.wrenboard.keyboard";

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

    private void saveShot(final String name) throws Exception {
        final Bitmap b = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (b == null) { Log.w(TAG, "PROBE shot " + name + ": takeScreenshot null"); return; }
        final File out = new File(getService().getExternalFilesDir(null), name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        Log.i(TAG, "PROBE shot saved " + out.getAbsolutePath());
    }

    public void testUpdaterLifecycleProbe() throws Exception {
        final Context ctx = getService().getApplicationContext();

        // T1: the 1.10 FileProvider config actually resolves for the updates cache dir (fail-closed).
        final File dir = new File(ctx.getCacheDir(), "updates");
        dir.mkdirs();
        final File dummy = new File(dir, "WrenBoard-update.apk");
        try (FileOutputStream fos = new FileOutputStream(dummy)) {
            fos.write("WBPROBE-DUMMY not an apk".getBytes("UTF-8"));
        }
        final Uri uri = FileProvider.getUriForFile(ctx, PKG + ".updates", dummy);
        assertTrue("T1 FileProvider uri for staged update file", uri.toString().contains("updates"));
        Log.i(TAG, "PROBE T1 provider ok uri=" + uri);

        // T2: the PRODUCTION launchInstaller hands the file to Android's install screen (fail-closed
        // on the route/grant/installer UI only; a dummy file proves NOT a valid update installation).
        final Method m = Class.forName("org.futo.inputmethod.latin.uix.settings.UpdateScreenKt")
                .getDeclaredMethod("launchInstaller", Context.class, java.io.File.class);
        m.setAccessible(true);
        final boolean launched = (Boolean) m.invoke(null, ctx, dummy);
        assertTrue("T2 production launchInstaller returned true", launched);
        Thread.sleep(4000);
        final String top = grepFirst(shell("dumpsys activity activities"), "topResumedActivity");
        Log.i(TAG, "PROBE T2 top after launch: " + top);
        assertTrue("T2 installer UI became top-resumed (parse error on dummy content expected)",
                top.contains("packageinstaller"));
        saveShot("upd-t2-installer.png");
        shell("input keyevent KEYCODE_BACK");
        Thread.sleep(1500);

        // T3: the real UpdateDialog ON_RESUME pendingApk path with a TAMPERED staged file (log-only;
        // records whether the person is told anything when the re-check fails on resume).
        shell("am broadcast -a " + PKG + ".WBDBG -p " + PKG + " --es cmd seedupdate:99.0.0|wbprobe");
        Thread.sleep(1000);
        UpdateStatus.INSTANCE.setPendingApk(dummy); // staged: stands in for the verified download; bypasses fetch+checksum
        shell("am broadcast -a " + PKG + ".WBDBG -p " + PKG + " --es cmd settings:update");
        Thread.sleep(4000);
        saveShot("upd-t3-dialog-before.png");
        Log.i(TAG, "PROBE T3 pre-resume canRequestPackageInstalls="
                + ctx.getPackageManager().canRequestPackageInstalls()
                + " pendingApk=" + UpdateStatus.INSTANCE.getPendingApk() + " readyVersion=" + UpdateStatus.INSTANCE.getReadyVersion());
        shell("input keyevent KEYCODE_HOME");
        Thread.sleep(2000);
        shell("am start -n " + PKG + "/org.futo.inputmethod.latin.uix.settings.SettingsActivity");
        Thread.sleep(4000);
        saveShot("upd-t3-after-resume.png");
        Log.i(TAG, "PROBE T3 post-resume pendingApk=" + UpdateStatus.INSTANCE.getPendingApk()
                + " readyVersion=" + UpdateStatus.INSTANCE.getReadyVersion()
                + " fileExists=" + dummy.exists()
                + " downloadText='" + UpdateStatus.INSTANCE.getDownloadText().getValue() + "'");
        // no assertion on the OS/app here: observed silence vs visible failure is reported as evidence.

        // T4: hand-over guard transitions with a SYNTHETIC clock (log-only; no statement about the
        // actual 10-minute UI timing).
        final long now = System.currentTimeMillis();
        final long tok = UpdateStatus.INSTANCE.getAttempts().getCurrentToken();
        UpdateStatus.INSTANCE.getAttempts().handOver(tok, now);
        final boolean busyNow = UpdateStatus.INSTANCE.getAttempts().isBusy(System.currentTimeMillis());
        final boolean busyAfterSyntheticExpiry = UpdateStatus.INSTANCE.getAttempts().isBusy(now + 11L * 60 * 1000);
        Log.i(TAG, "PROBE T4 handOver token=" + tok + " isBusy(now)=" + busyNow
                + " isBusy(synthetic +11min)=" + busyAfterSyntheticExpiry);

        Log.i(TAG, "PROBE updater end");
    }
}
