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
 * Per-case JUnit3 methods (ServiceTestCase runs each test* method separately) so case counts
 * stay distinguishable: a case that dies mid-run shortens the instrumentation summary instead
 * of passing silently. Assertion grounds: UpdateScreen.kt ON_RESUME pendingApk path
 * (pendingApk consumed, verify failure deletes file and resets readyVersion, verify success
 * calls the production launchInstaller) and UpdateVerify.verifyInner (package, signer, newer
 * versionCode all checked).
 *
 * T2/T3b notes: the dummy file in T2 proves route/grant/installer UI only, never a valid
 * update installation. T3b uses the same-tree vc-12 APK (same source, same CI debug signer,
 * higher versionCode) staged by tools/wren110-probe.sh; it proves the production re-verify
 * ACCEPTS the valid replacement and the production launch ROUTE fires. Route-level only: no
 * completed install is performed or claimed.
 * Grant precondition: the production ON_RESUME observer consumes pendingApk only when
 * canRequestPackageInstalls() is true; without the grant ON_RESUME silently retains the file
 * (source-observed, not probe-asserted). The probe script grants REQUEST_INSTALL_PACKAGES on
 * the isolated emulator (appops, logged), so these cases exercise the granted path.
 * T4 uses synthetic clock values and says nothing about actual 10-minute UI timing.
 * No network, no real install completion, no publication.
 */
package org.futo.inputmethod.latin;

import android.app.Instrumentation;
import android.content.Context;
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

    private File writeDummy() throws Exception {
        final File dummy = new File(updatesDir(), UPDATE_APK);
        try (FileOutputStream fos = new FileOutputStream(dummy)) {
            fos.write("WBPROBE-DUMMY not an apk".getBytes("UTF-8"));
        }
        return dummy;
    }

    /** Seed the update-check result and open the update settings screen (production WBDBG hooks). */
    private void seedAndOpenUpdateScreen() throws Exception {
        shell("am broadcast -a " + PKG + ".WBDBG -p " + PKG + " --es cmd seedupdate:99.0.0|wbprobe");
        Thread.sleep(1000);
        probeSeededStore();
        shell("am broadcast -a " + PKG + ".WBDBG -p " + PKG + " --es cmd settings:update");
        Thread.sleep(4000);
        probeSeededStore();
    }

    /** Fail (harness precondition, not product) unless the seed is still the current stored
     *  result; one re-seed retry. Deterministic guard required before the observer cases. */
    private void requireSeedCurrent() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            final boolean[] found = new boolean[]{false};
            probeSeededStore(found);
            if (found[0]) return;
            Log.i(TAG, "PROBE seed not current (attempt " + attempt + "); re-seeding");
            shell("am broadcast -a " + PKG + ".WBDBG -p " + PKG + " --es cmd seedupdate:99.0.0|wbprobe");
            Thread.sleep(1500);
        }
        final boolean[] found = new boolean[]{false};
        probeSeededStore(found);
        if (!found[0]) fail("PRECONDITION NOT MET: seeded update result not current in store (harness/emulator state, not product)");
    }

    /**
     * Decisive write-vs-read probe for the T3/T3b seed gap (run 45321885141: seed logged
     * SEEDED but the update screen showed the up-to-date branch). Reads only the datastore
     * file NAMES/sizes and whether the seeded marker bytes are present - never file contents.
     * Marker in the credential-encrypted file => write committed, read side is the gap.
     * Marker nowhere => write fell through to the in-memory direct-boot store (try-lock miss).
     */
    private void probeSeededStore() {
        probeSeededStore(new boolean[]{false});
    }

    /** @param found out-param: true when the seeded marker is present in any store file. */
    private void probeSeededStore(final boolean[] found) {
        try {
            final File[] bases = new File[]{
                    new File("/data/user/0/" + PKG + "/files/datastore"),
                    new File("/data/user_de/0/" + PKG + "/files/datastore")};
            for (final File base : bases) {
                final File[] fs = base.listFiles();
                Log.i(TAG, "PROBE store dir " + base + " files=" + (fs == null ? "null" : fs.length));
                if (fs == null) continue;
                for (final File f : fs) {
                    final byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                    final boolean hit = new String(b, "ISO-8859-1").contains("99.0.0");
                    Log.i(TAG, "PROBE store file=" + f.getName() + " size=" + b.length + " seeded99=" + hit);
                    if (hit) found[0] = true;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "PROBE store read failed: " + t);
        }
    }

    /** Background, then resume the settings activity so the UpdateDialog ON_RESUME observer fires. */
    private void backgroundAndResume() throws Exception {
        shell("input keyevent KEYCODE_HOME");
        Thread.sleep(2000);
        shell("am start -n " + PKG + "/org.futo.inputmethod.latin.uix.settings.SettingsActivity");
        Thread.sleep(4000);
    }

    // T1: the 1.10 FileProvider config actually resolves for the updates cache dir (fail-closed).
    public void testT1ProviderResolves() throws Exception {
        final Context ctx = getService().getApplicationContext();
        final File dummy = writeDummy();
        final Uri uri = FileProvider.getUriForFile(ctx, PKG + ".updates", dummy);
        assertTrue("T1 FileProvider uri for staged update file", uri.toString().contains("updates"));
        Log.i(TAG, "PROBE T1 provider ok uri=" + uri);
    }

    // T2: the PRODUCTION launchInstaller hands the file to Android's install screen (fail-closed
    // on the route/grant/installer UI only; a dummy file proves NOT a valid update installation).
    public void testT2ProductionInstallerRoute() throws Exception {
        final Context ctx = getService().getApplicationContext();
        final File dummy = writeDummy();
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
    }

    // T3: ON_RESUME with a TAMPERED staged file. Fail-closed on the production rejection path
    // (UpdateScreen.kt: verify failure consumes pendingApk, deletes the file, resets readyVersion).
    public void testT3TamperedPendingApkRejected() throws Exception {
        final Context ctx = getService().getApplicationContext();
        final File tampered = writeDummy();
        seedAndOpenUpdateScreen();
        UpdateStatus.INSTANCE.setPendingApk(tampered); // staged: stands in for the verified download; bypasses fetch+checksum
        Thread.sleep(500);
        saveShot("upd-t3-dialog-before.png");
        Log.i(TAG, "PROBE T3 pre-resume canRequestPackageInstalls="
                + ctx.getPackageManager().canRequestPackageInstalls()
                + " pendingApk=" + UpdateStatus.INSTANCE.getPendingApk()
                + " readyVersion=" + UpdateStatus.INSTANCE.getReadyVersion());
        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
            fail("PRECONDITION NOT MET: canRequestPackageInstalls false after appops grant (harness/emulator state, not product)");
        }
        requireSeedCurrent();
        backgroundAndResume();
        saveShot("upd-t3-after-resume.png");
        Log.i(TAG, "PROBE T3 post-resume pendingApk=" + UpdateStatus.INSTANCE.getPendingApk()
                + " readyVersion=" + UpdateStatus.INSTANCE.getReadyVersion()
                + " fileExists=" + tampered.exists()
                + " downloadText='" + UpdateStatus.INSTANCE.getDownloadText().getValue() + "'");
        assertNull("T3 tampered pendingApk consumed on resume", UpdateStatus.INSTANCE.getPendingApk());
        assertEquals("T3 readyVersion reset after failed re-verify", -1,
                UpdateStatus.INSTANCE.getReadyVersion());
        assertFalse("T3 tampered file deleted after failed re-verify", tampered.exists());
    }

    // T3b: ON_RESUME with the VALID same-tree vc-12 fixture (same source, same CI debug signer,
    // higher versionCode): the production re-verify must accept it and the production
    // launchInstaller must fire. This is the valid-replacement/signer/version success case the
    // dummy file can never prove. Fixture staged by tools/wren110-probe.sh; absent = harness bug.
    public void testT3bValidFixtureAccepted() throws Exception {
        final Context ctx = getService().getApplicationContext();
        final File valid = new File(updatesDir(), VALID_FIXTURE);
        assertTrue("T3b valid vc-12 fixture absent from updates cache (staging failed)", valid.exists());
        seedAndOpenUpdateScreen();
        UpdateStatus.INSTANCE.setPendingApk(valid);
        Thread.sleep(500);
        saveShot("upd-t3b-dialog-before.png");
        Log.i(TAG, "PROBE T3b pre-resume canRequestPackageInstalls="
                + ctx.getPackageManager().canRequestPackageInstalls()
                + " pendingApk=" + UpdateStatus.INSTANCE.getPendingApk()
                + " readyVersion=" + UpdateStatus.INSTANCE.getReadyVersion());
        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
            fail("PRECONDITION NOT MET: canRequestPackageInstalls false after appops grant (harness/emulator state, not product)");
        }
        requireSeedCurrent();
        backgroundAndResume();
        Thread.sleep(2000);
        // Poll the top-activity check: the installer STAGING window (system "Staging app..."
        // sheet over the update dialog, observed in run 45363089122's upd-t3b-after-resume.png)
        // has a transient with no topResumedActivity line; sampling once can land in it.
        String top = "";
        for (int i = 0; i < 6 && !top.contains("packageinstaller"); i++) {
            top = grepFirst(shell("dumpsys activity activities"), "topResumedActivity");
            Log.i(TAG, "PROBE T3b top poll " + i + "=" + top);
            if (!top.contains("packageinstaller")) Thread.sleep(2000);
        }
        saveShot("upd-t3b-after-resume.png");
        Log.i(TAG, "PROBE T3b post-resume pendingApk=" + UpdateStatus.INSTANCE.getPendingApk()
                + " readyVersion=" + UpdateStatus.INSTANCE.getReadyVersion()
                + " fileExists=" + valid.exists() + " top=" + top);
        try {
            assertNull("T3b valid pendingApk consumed on resume", UpdateStatus.INSTANCE.getPendingApk());
            assertTrue("T3b valid fixture NOT deleted by re-verify", valid.exists());
            // Route-level assertion only: the install ROUTE reached Android's installer UI.
            // No completed install is performed or claimed. The grant is established by the probe
            // script (appops), so the installer screen itself, not the unknown-sources screen, is
            // the expected top; canRequestPackageInstalls is logged above for the record.
            assertTrue("T3b install route reached Android's installer UI (route only, no completed install), top=" + top,
                    top.contains("packageinstaller"));
        } finally {
            // Always leave the installer UI: a failed assert must not strand a live staging
            // session that collides with the probe script's later positive-install step.
            shell("input keyevent KEYCODE_BACK");
            Thread.sleep(1500);
        }
    }

    // T5: the ACTUAL unknown-sources Settings route, end to end (r15 evidence gap: the T3/T3b
    // resume recheck ran with canRequestPackageInstalls already true, so the Settings route
    // itself was never entered). Here the permission starts DENIED (appops, restored in
    // finally), the PRODUCTION downloadAndInstall hits its permission branch (posts the
    // guidance, stages pendingApk, and startActivity really opens ACTION_MANAGE_UNKNOWN_APP_SOURCES
    // - the real system Settings screen, screenshot), then the grant lands and BACK returns to
    // the app, where the PRODUCTION ON_RESUME observer re-verifies the staged valid fixture and
    // hands it to Android's installer UI. Bypassed glue, labeled: the HTTP fetch/checksum never
    // runs (staged same-tree vc-12 fixture, as in T3b); the toggle is flipped via appops instead
    // of a human finger (the Settings screen itself is real); no completed install is performed
    // or claimed. downloadAndInstall is invoked by reflection like T2's launchInstaller.
    public void testT5UnknownSourcesSettingsRoute() throws Exception {
        final Context ctx = getService().getApplicationContext();
        // Harness precondition: clear any leftover hand-over from T4's synthetic-clock case.
        UpdateStatus.INSTANCE.getAttempts().endHandOver(
                UpdateStatus.INSTANCE.getAttempts().getCurrentToken(), "");
        final File valid = new File(updatesDir(), VALID_FIXTURE);
        assertTrue("T5 valid vc-12 fixture absent from updates cache (staging failed)", valid.exists());
        // verifiedDownload() reuses exactly this file name when readyVersion matches the result.
        final File staged = new File(updatesDir(), UPDATE_APK);
        java.nio.file.Files.copy(valid.toPath(), staged.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        shell("appops set " + PKG + " REQUEST_INSTALL_PACKAGES deny");
        try {
            assertFalse("T5 precondition: install-unknown-apps denied",
                    ctx.getPackageManager().canRequestPackageInstalls());
            seedAndOpenUpdateScreen();
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
            String top = "";
            for (int i = 0; i < 8 && !top.contains("com.android.settings"); i++) {
                Thread.sleep(2000);
                top = grepFirst(shell("dumpsys activity activities"), "topResumedActivity");
                Log.i(TAG, "PROBE T5 settings-route poll " + i + "=" + top);
            }
            saveShot("upd-t5-settings-route.png");
            Log.i(TAG, "PROBE T5 denied-branch text='"
                    + UpdateStatus.INSTANCE.getDownloadText().getValue() + "' pendingApk="
                    + UpdateStatus.INSTANCE.getPendingApk());
            assertEquals("T5 production permission branch posts the guidance",
                    "Turn on Allow, then come back.",
                    UpdateStatus.INSTANCE.getDownloadText().getValue());
            assertNotNull("T5 production permission branch stages pendingApk",
                    UpdateStatus.INSTANCE.getPendingApk());
            assertTrue("T5 the ACTUAL unknown-sources Settings route became top, top=" + top,
                    top.contains("com.android.settings"));
            // The person grants the toggle (harness: appops) and returns.
            shell("appops set " + PKG + " REQUEST_INSTALL_PACKAGES allow");
            shell("input keyevent KEYCODE_BACK");
            Thread.sleep(3000);
            top = "";
            for (int i = 0; i < 8 && !top.contains("packageinstaller"); i++) {
                top = grepFirst(shell("dumpsys activity activities"), "topResumedActivity");
                Log.i(TAG, "PROBE T5 installer poll " + i + "=" + top);
                if (!top.contains("packageinstaller")) Thread.sleep(2000);
            }
            saveShot("upd-t5-after-return.png");
            Log.i(TAG, "PROBE T5 post-return pendingApk=" + UpdateStatus.INSTANCE.getPendingApk()
                    + " busy=" + UpdateStatus.INSTANCE.getAttempts().isBusy(System.currentTimeMillis())
                    + " top=" + top);
            assertNull("T5 resume recheck consumed pendingApk", UpdateStatus.INSTANCE.getPendingApk());
            assertTrue("T5 resume recheck handed over (busy window open)",
                    UpdateStatus.INSTANCE.getAttempts().isBusy(System.currentTimeMillis()));
            assertTrue("T5 install route reached Android's installer UI after the Settings return (route only, no completed install), top=" + top,
                    top.contains("packageinstaller"));
        } finally {
            shell("appops set " + PKG + " REQUEST_INSTALL_PACKAGES allow");
            shell("input keyevent KEYCODE_BACK");
            Thread.sleep(1500);
        }
    }

    // T4: hand-over guard transitions with a SYNTHETIC clock (log-only; no statement about the
    // actual 10-minute UI timing).
    public void testT4HandOverGuardSynthetic() {
        final long now = System.currentTimeMillis();
        final long tok = UpdateStatus.INSTANCE.getAttempts().getCurrentToken();
        UpdateStatus.INSTANCE.getAttempts().handOver(tok, now);
        final boolean busyNow = UpdateStatus.INSTANCE.getAttempts().isBusy(System.currentTimeMillis());
        final boolean busyAfterSyntheticExpiry = UpdateStatus.INSTANCE.getAttempts().isBusy(now + 11L * 60 * 1000);
        Log.i(TAG, "PROBE T4 handOver token=" + tok + " isBusy(now)=" + busyNow
                + " isBusy(synthetic +11min)=" + busyAfterSyntheticExpiry);
        assertTrue("T4 hand-over holds at hand-out time", busyNow);
        assertFalse("T4 hand-over released after synthetic expiry", busyAfterSyntheticExpiry);
    }
}
