/*
 * WrenBoard 1.10 diagnostics ONLY (relruntime branch, frozen-source guard allowlist; not in e059314).
 *
 * T5a/T5b: the ACTUAL unknown-sources Settings route (r15 evidence gap: the T3/T3b resume
 * recheck ran with canRequestPackageInstalls already true, so the Settings route itself was
 * never entered). Split into two instrumentation sessions because Android KILLS the app
 * process on any REQUEST_INSTALL_PACKAGES op change (r16 logcat, ActivityManager:
 * "Killing <pid>:com.wrenboard.keyboard ... REQUEST_INSTALL_PACKAGES changed"); the op is
 * flipped by tools/wren110-probe.sh between sessions, never while a session is live.
 *
 * T5a (session starts with the op DENIED): the PRODUCTION downloadAndInstall hits its
 * permission branch - posts the guidance, stages pendingApk, and startActivity really opens
 * ACTION_MANAGE_UNKNOWN_APP_SOURCES (the real system Settings screen; exact top activity is
 * logged and screenshotted).
 * T5b (session starts with the op ALLOWED, after the script's allow killed the app): the
 * post-grant recovery path as it actually exists - cold start (pendingApk is in-memory and
 * does NOT survive the kill; asserted null, documented, not treated as a defect of this
 * probe), then the production downloadAndInstall re-invoked with permission now present
 * reaches Android's installer UI with a hand-over recorded.
 *
 * Bypassed glue, labeled: the HTTP fetch/checksum never runs (staged same-tree vc-12 fixture,
 * as in T3b); the toggle is flipped via appops instead of a human finger (the Settings screen
 * itself is real); no completed install is performed or claimed. downloadAndInstall is
 * invoked by reflection like T2's launchInstaller.
 */
package org.futo.inputmethod.latin;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.futo.inputmethod.latin.uix.settings.UpdateStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

@LargeTest
public class Wren110UpdaterSettingsRouteProbeTest extends InputTestsBase {

    private static final String TAG = "WBUPD";
    private static final String PKG = "com.wrenboard.keyboard";
    private static final String UPDATE_APK = "WrenBoard-update.apk";
    private static final String VALID_FIXTURE = "w17-vc12-fixture.apk";

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

    private File updatesDir() {
        final File dir = new File(getService().getCacheDir(), "updates");
        dir.mkdirs();
        return dir;
    }

    private void seedAndOpenUpdateScreen() throws Exception {
        shell("am broadcast -a " + PKG + ".WBDBG -p " + PKG + " --es cmd seedupdate:99.0.0|wbprobe");
        Thread.sleep(1000);
        shell("am broadcast -a " + PKG + ".WBDBG -p " + PKG + " --es cmd settings:update");
        Thread.sleep(4000);
    }

    /** Invoke the private production downloadAndInstall(Context, UpdateResult, onHandedOver). */
    private void invokeProductionDownloadAndInstall(final Context ctx) throws Exception {
        final Class<?> urc = Class.forName("org.futo.inputmethod.updates.UpdateResult");
        final Object result = urc.getDeclaredConstructor(int.class, String.class, String.class,
                long.class, String.class, java.util.List.class)
                .newInstance(990000, "wbprobe", "99.0.0", 0L, null, java.util.Collections.emptyList());
        UpdateStatus.INSTANCE.setReadyVersion(990000);
        final Method m = Class.forName("org.futo.inputmethod.latin.uix.settings.UpdateScreenKt")
                .getDeclaredMethod("downloadAndInstall", Context.class, urc,
                        kotlin.jvm.functions.Function0.class);
        m.setAccessible(true);
        m.invoke(null, ctx, result, new kotlin.jvm.functions.Function0<Object>() {
            @Override public Object invoke() { return null; }
        });
    }

    private void stageValidFixtureAsDownload() throws Exception {
        final File valid = new File(updatesDir(), VALID_FIXTURE);
        assertTrue("T5 valid vc-12 fixture absent from updates cache (staging failed)", valid.exists());
        final File staged = new File(updatesDir(), UPDATE_APK);
        java.nio.file.Files.copy(valid.toPath(), staged.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private String pollTop(final String contains, final int polls, final String phase) throws Exception {
        String top = "";
        for (int i = 0; i < polls && !top.contains(contains); i++) {
            Thread.sleep(2000);
            top = grepFirst(shell("dumpsys activity activities"), "topResumedActivity");
            Log.i(TAG, "PROBE " + phase + " poll " + i + "=" + top);
        }
        return top;
    }

    // T5a: route ENTRY. Script set the op denied BEFORE this session started (the change killed
    // the app, so this process is fresh with the op denied).
    public void testT5aSettingsRouteEntry() throws Exception {
        final Context ctx = getService().getApplicationContext();
        assertFalse("T5a precondition: install-unknown-apps denied",
                ctx.getPackageManager().canRequestPackageInstalls());
        stageValidFixtureAsDownload();
        seedAndOpenUpdateScreen();
        invokeProductionDownloadAndInstall(ctx);
        final String top = pollTop("com.android.settings", 8, "T5a settings-route");
        saveShot("upd-t5a-settings-route.png");
        Log.i(TAG, "PROBE T5a denied-branch text='"
                + UpdateStatus.INSTANCE.getDownloadText().getValue() + "' pendingApk="
                + UpdateStatus.INSTANCE.getPendingApk() + " top=" + top);
        try {
            assertEquals("T5a production permission branch posts the guidance",
                    "Turn on Allow, then come back.",
                    UpdateStatus.INSTANCE.getDownloadText().getValue());
            assertNotNull("T5a production permission branch stages pendingApk",
                    UpdateStatus.INSTANCE.getPendingApk());
            assertTrue("T5a the ACTUAL unknown-sources Settings route became top, top=" + top,
                    top.contains("com.android.settings"));
        } finally {
            shell("input keyevent KEYCODE_BACK");
            Thread.sleep(1500);
        }
    }

    // T5b: post-grant RECOVERY. Script set the op allowed after T5a (the change killed the app
    // again, so this process is a cold start with the op allowed). pendingApk is in-memory
    // (UpdateScreen.kt UpdateStatus) and does not survive the system kill; the real post-grant
    // recovery is: user returns, app cold-starts, the download is re-triggered (here re-invoked
    // directly), and with permission now present it reaches the installer UI.
    public void testT5bPostGrantRecovery() throws Exception {
        final Context ctx = getService().getApplicationContext();
        assertTrue("T5b precondition: install-unknown-apps allowed",
                ctx.getPackageManager().canRequestPackageInstalls());
        assertNull("T5b documents cold-start loss of in-memory pendingApk after the op-change kill",
                UpdateStatus.INSTANCE.getPendingApk());
        stageValidFixtureAsDownload();
        seedAndOpenUpdateScreen();
        invokeProductionDownloadAndInstall(ctx);
        Thread.sleep(3000);
        final String top = pollTop("packageinstaller", 8, "T5b installer");
        saveShot("upd-t5b-after-grant-installer.png");
        Log.i(TAG, "PROBE T5b busy="
                + UpdateStatus.INSTANCE.getAttempts().isBusy(System.currentTimeMillis())
                + " top=" + top);
        try {
            assertTrue("T5b hand-over recorded on the production launch path",
                    UpdateStatus.INSTANCE.getAttempts().isBusy(System.currentTimeMillis()));
            assertTrue("T5b post-grant re-invoke reached Android's installer UI (route only, no completed install), top=" + top,
                    top.contains("packageinstaller"));
        } finally {
            shell("input keyevent KEYCODE_BACK");
            Thread.sleep(1500);
        }
    }
}
