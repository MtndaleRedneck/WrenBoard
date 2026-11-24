package org.futo.inputmethod.latin;

import android.content.Intent;
import android.os.SystemClock;
import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.uiautomator.UiDevice;
import androidx.navigation.NavBackStackEntry;
import org.junit.Rule;
import org.junit.Test;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/**
 * 1.10 r21 diagnostic (not a product test): the frozen Wren18NavigationTest failed once at its
 * final "App root must not trap Android back" assert (line 220) with the same code green before
 * and after, and the decisive logcat window had rotated out of the buffer. This probe replays
 * ONLY the nested-chain back-walk tail with full state capture so any recurrence is decisive:
 *   - the whole NavController back stack (route names, in order) after EVERY back press, so a
 *     duplicate/phantom destination that swallows a back press is visible directly;
 *   - the foreground package + focused-window package sampled through the exit window, so a slow
 *     finish (timing) is distinguishable from a consumed back (stack still holds entries);
 *   - a screenshot of the residual screen when the app is still foreground after 6s.
 * It asserts exit within 8s (the frozen test allows 3s): a pass that needed >3s supports the
 * timing read; a failure comes with the back-stack evidence attached. Log tag WB18_NAV, which
 * the frozen script's cleanup dump already collects.
 */
public class Wren110NavBackDiagProbeTest {
    @Rule public ActivityTestRule<org.futo.inputmethod.latin.uix.settings.SettingsActivity> activityRule =
        new ActivityTestRule<>(org.futo.inputmethod.latin.uix.settings.SettingsActivity.class, true, false);

    private org.futo.inputmethod.latin.uix.settings.SettingsActivity activity() { return activityRule.getActivity(); }

    private void logStack(String tag) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            List<String> names = new ArrayList<>();
            for (NavBackStackEntry e : activity().getNavController().getCurrentBackStack().getValue()) {
                names.add(e.getDestination().getRoute() == null ? ("id:" + e.getDestination().getId()) : e.getDestination().getRoute());
            }
            String cur = activity().getNavController().getCurrentDestination() == null ? "null"
                    : activity().getNavController().getCurrentDestination().getRoute();
            android.util.Log.i("WB18_NAV", "DIAG " + tag + " current=" + cur + " stack=" + names);
        });
    }

    private void navigate(String destination) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            activity().getNavController().navigate(destination, null, null));
        for (int i = 0; i < 50; i++) {
            final String[] cur = new String[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (activity().getNavController().getCurrentDestination() != null)
                    cur[0] = activity().getNavController().getCurrentDestination().getRoute();
            });
            if (destination.equals(cur[0])) return;
            SystemClock.sleep(100);
        }
        fail("diag navigation never reached " + destination);
    }

    @Test public void nestedChainBackWalkExitDiag() throws Exception {
        activityRule.launchActivity(new Intent());
        SystemClock.sleep(1500);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File out = new File(InstrumentationRegistry.getTargetContext().getExternalFilesDir(null), "wren110-navdiag");
        out.mkdirs();

        // Same nested chain the frozen test walks back from at its tail.
        navigate("wren_rich_input");
        navigate("wren_toolbar");
        navigate("actionEdit");
        logStack("chain-built");

        device.pressBack(); logStack("back1"); SystemClock.sleep(400);
        device.pressBack(); logStack("back2"); SystemClock.sleep(400);
        device.pressBack(); logStack("back3-at-home-expected"); SystemClock.sleep(400);

        device.pressBack();
        boolean exited = false;
        long waitedMs = 0;
        for (int i = 0; i < 16; i++) {
            SystemClock.sleep(500);
            waitedMs += 500;
            String pkg = device.getCurrentPackageName();
            android.util.Log.i("WB18_NAV", "DIAG exit-poll i=" + i + " pkg=" + pkg
                    + " finishing=" + activity().isFinishing() + " destroyed=" + activity().isDestroyed());
            if (!activity().getPackageName().equals(pkg)) { exited = true; break; }
            logStack("exit-poll-" + i);
        }
        android.util.Log.i("WB18_NAV", "DIAG result exited=" + exited + " waitedMs=" + waitedMs);
        if (!exited) {
            device.takeScreenshot(new File(out, "diag_residual_screen.png"));
            logStack("residual");
        }
        assertTrue("Back-walk exit did not happen within 8s; see WB18_NAV DIAG back-stack dumps "
                + (waitedMs > 3000 ? "(needed >3s: timing-sensitive)" : "(fast fail: consumed back)"),
                exited);
        android.util.Log.i("WB18_NAV", "DIAG timing-class waitedMs=" + waitedMs
                + (waitedMs > 3000 ? " SLOWER-THAN-FROZEN-3S-WINDOW" : " inside-3s"));
    }
}
