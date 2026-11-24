/*
 * WrenBoard 1.10 diagnostics ONLY (relruntime branch, frozen-source guard allowlist; not in e059314).
 *
 * Bounded changed-byte checks for candidate 7c6ffa5fd ("1.10 (17)") requested during I2 closeout:
 * the application-context retention fixes (PermissionsManager, AccessibilityUtils,
 * RichInputMethodManager init-entry normalization, SubtypeLocaleUtils first-init input) must be
 * verified on the CHANGED bytes through the REAL production first-caller path. Each test here is
 * bounded to exactly what it observes; nothing here proves behavior outside the instrumented
 * emulator process at this API level.
 *
 * Method order note: JUnit3 runs methods alphabetically. testZPermissionLaunchRoute runs LAST in
 * this class because it briefly launches PermissionsActivity (and possibly the system permission
 * dialog); it finishes the activity before returning.
 */
package org.futo.inputmethod.latin;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.futo.inputmethod.accessibility.AccessibilityUtils;
import org.futo.inputmethod.latin.permissions.PermissionsActivity;
import org.futo.inputmethod.latin.permissions.PermissionsManager;
import org.futo.inputmethod.latin.utils.SubtypeLocaleUtils;

import java.lang.reflect.Field;

@LargeTest
public class Wren110RetentionProbeTest extends InputTestsBase {

    private static final String TAG = "WBRET";
    private static final String PERM = android.Manifest.permission.RECORD_AUDIO;

    private static Object readField(final Object target, final Class<?> cls, final String name)
            throws Exception {
        final Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private Context appCtx() {
        return getService().getApplicationContext();
    }

    /** AccessibilityUtils: stored context is the application context and spoken strings resolve. */
    public void testAccessibilityStoredContextAndSpokenStrings() throws Exception {
        final Context app = appCtx();
        AccessibilityUtils.init(getService());
        final Object instance = readField(null, AccessibilityUtils.class, "sInstance");
        final Context stored = (Context) readField(instance, AccessibilityUtils.class, "mContext");
        Log.i(TAG, "PROBE accessibility stored=" + stored.getClass().getName()
                + " identityApp=" + (stored == app));
        assertSame("AccessibilityUtils.mContext must be the application context", app, stored);

        // Spoken-locale caveat check (I2): compare the SERVICE's own resource resolution against
        // the stored (application) context - not stored-vs-app, which would be a tautology after
        // the identity assert above. Discriminates a config mismatch between the service's
        // resources and the application resources the stored context now reads from.
        final CharSequence viaStored = stored.getText(R.string.spoken_use_headphones);
        final CharSequence viaService = getService().getText(R.string.spoken_use_headphones);
        Log.i(TAG, "PROBE spoken_use_headphones viaStored=\"" + viaStored + "\" viaService=\""
                + viaService + "\" equalService=" + viaStored.toString().contentEquals(viaService));
        assertTrue("spoken_use_headphones must resolve non-empty", viaStored.length() > 0);
        assertTrue("spoken string via stored context must equal service resolution",
                viaStored.toString().contentEquals(viaService));

        final String storedLocales = stored.getResources().getConfiguration().getLocales()
                .toString();
        final String serviceLocales = getService().getResources().getConfiguration().getLocales()
                .toString();
        Log.i(TAG, "PROBE accessibility locales stored=" + storedLocales + " service="
                + serviceLocales);
        assertEquals("locale list via stored context must equal service locale list",
                serviceLocales, storedLocales);
    }

    /** RichInputMethodManager: init-entry normalization; mContext and SubtypeLocaleUtils resources
     *  are the application's. */
    public void testRichInputMethodManagerEntryNormalization() throws Exception {
        final Context app = appCtx();
        // The service was started by setUp through the real production path, which calls
        // RichInputMethodManager.init(serviceContext); entry normalization must have replaced it.
        final RichInputMethodManager rimm = RichInputMethodManager.getInstance();
        final Context stored = (Context) readField(rimm, RichInputMethodManager.class, "mContext");
        Log.i(TAG, "PROBE rimm stored=" + stored.getClass().getName()
                + " identityApp=" + (stored == app));
        assertSame("RichInputMethodManager.mContext must be the application context", app, stored);

        // SubtypeLocaleUtils.sResources: LOGGED ONLY, not asserted (I2): the Resources singleton
        // is shared per config, so identity and locale equality pass on old bytes too - neither
        // discriminates the fix. The service-vs-stored locale parity assert above is the
        // discriminating check; this records the actual first-init input for the record.
        final Resources sRes = (Resources) readField(null, SubtypeLocaleUtils.class, "sResources");
        assertNotNull("SubtypeLocaleUtils must be initialized by service startup", sRes);
        final boolean identityApp = sRes == app.getResources();
        final boolean identityService = sRes == getService().getResources();
        final String sLocales = sRes.getConfiguration().getLocales().toString();
        Log.i(TAG, "PROBE subtype sResources identityApp=" + identityApp
                + " identityService=" + identityService + " locales=" + sLocales);
    }

    /** PermissionsManager: first-caller normalization and the PermissionsActivity launch route.
     *  Runs last (alphabetical) because it starts an activity. */
    public void testZPermissionLaunchRoute() throws Exception {
        final Context app = appCtx();
        final Instrumentation instr = InstrumentationRegistry.getInstrumentation();
        final PermissionsManager[] holder = new PermissionsManager[1];
        instr.runOnMainSync(new Runnable() {
            @Override public void run() {
                holder[0] = PermissionsManager.get(getService());
            }
        });
        final Context stored = (Context) readField(holder[0], PermissionsManager.class, "mContext");
        Log.i(TAG, "PROBE permissions stored=" + stored.getClass().getName()
                + " identityApp=" + (stored == app));
        assertSame("PermissionsManager.mContext must be the application context", app, stored);

        final boolean denied = ContextCompat.checkSelfPermission(stored, PERM)
                != PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "PROBE permissions " + PERM + " denied=" + denied);
        if (!denied) {
            Log.i(TAG, "PERMISSION_ROUTE=SKIPPED_ALREADY_GRANTED");
            return;
        }
        final Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor(
                PermissionsActivity.class.getName(), null, false);
        instr.addMonitor(monitor);
        instr.runOnMainSync(new Runnable() {
            @Override public void run() {
                holder[0].requestPermissions(
                        new PermissionsManager.PermissionsResultCallback() {
                            @Override public void onRequestPermissionsResult(
                                    final boolean allGranted) {
                                Log.i(TAG, "PROBE permissions result allGranted=" + allGranted);
                            }
                        }, null, PERM);
            }
        });
        final Activity launched = monitor.waitForActivityWithTimeout(8000);
        // Unambiguous greppable token: green count alone must not carry this verdict (I2).
        Log.i(TAG, "PERMISSION_ROUTE=" + (launched != null ? "FIRED" : "NOT_FIRED_TIMEOUT"));
        assertNotNull("PermissionsActivity launch route must fire from the stored app context",
                launched);
        instr.runOnMainSync(new Runnable() {
            @Override public void run() {
                launched.finish();
            }
        });
    }
}
