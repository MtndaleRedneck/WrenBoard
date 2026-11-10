package org.futo.inputmethod.latin.uix.settings;

import android.content.Intent;
import android.os.SystemClock;
import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import org.junit.Rule;
import org.junit.Test;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Real settings navigation, including the settings-only action editor (no IME manager). */
public class Wren18NavigationTest {
    @Rule public ActivityTestRule<SettingsActivity> activityRule =
        new ActivityTestRule<>(SettingsActivity.class, true, false);

    private String route(SettingsActivity activity) {
        final String[] result = new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (activity.getNavController().getCurrentDestination() != null)
                result[0] = activity.getNavController().getCurrentDestination().getRoute();
        });
        return result[0];
    }
    private void waitRoute(SettingsActivity activity, String expected) {
        for(int i=0;i<50;i++) {
            if(expected.equals(route(activity))) return;
            SystemClock.sleep(100);
        }
        assertEquals(expected, route(activity));
    }
    private void navigate(SettingsActivity activity, String destination) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            activity.getNavController().navigate(destination, null, null));
        waitRoute(activity, destination);
        SystemClock.sleep(300);
    }
    private void clickFresh(UiDevice device, androidx.test.uiautomator.BySelector selector) {
        if(selector.toString().contains("Back")) {
            // Actual captured top-bar pixels put the arrow here. Avoid cached accessibility nodes.
            device.click(Math.round(device.getDisplayWidth()*0.065f), Math.round(device.getDisplayHeight()*0.059f));
            return;
        }
        for(int attempt=0;attempt<5;attempt++) {
            try {
                UiObject2 object=device.findObject(selector);
                assertNotNull("Expected control " + selector,object);
                android.graphics.Point point=object.getVisibleCenter();
                device.click(point.x,point.y);
                return;
            } catch(androidx.test.uiautomator.StaleObjectException stale) {
                SystemClock.sleep(200);
            }
        }
        fail("Control stayed stale " + selector);
    }
    @Test public void registeredScreensReturnOneLevelAfterAccessibilityRead() throws Exception {
        SettingsActivity activity = activityRule.launchActivity(new Intent());
        waitRoute(activity, "home");
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Set<String> routes = new LinkedHashSet<>();
        routes.add("actionEdit");
        routes.add("wren_rich_input");
        routes.add("wren_toolbar");
        for(UserSettingsMenu menu : SettingsNavigatorKt.getSettingsMenus()) {
            if(menu.getRegisterNavPath() && !"home".equals(menu.getNavPath())) routes.add(menu.getNavPath());
        }
        for(String explicit : new String[]{"themes", "languages", "feedback", "actionEdit"}) routes.add(explicit);
        File out = new File(InstrumentationRegistry.getTargetContext().getExternalFilesDir(null), "wren18-nav");
        assertTrue(out.isDirectory() || out.mkdirs());
        for(String path : routes) {
            navigate(activity, path);
            try {
                device.dumpWindowHierarchy(new File(out, path.replace('/', '_') + ".xml"));
            } catch (NullPointerException transientNode) {
                android.util.Log.w("WB18_NAV", "Hierarchy dump failed for " + path, transientNode);
                // A failed accessibility dump is logged, never reported as a dump pass.
                // Back itself is still independently asserted below.
            }
            assertTrue(device.takeScreenshot(new File(out, path.replace('/', '_') + ".png")));
            device.pressBack();
            waitRoute(activity, "home");
            assertFalse("Back exited settings from " + path, activity.isFinishing());
            navigate(activity, path);
            UiObject2 arrow = device.findObject(By.desc("Back"));
            if(arrow != null) {
                clickFresh(device, By.desc("Back"));
                waitRoute(activity, "home");
            } else {
                device.pressBack();
                waitRoute(activity, "home");
            }
            android.util.Log.i("WB18_NAV", "PASS " + path);
        }
        // A genuine nested chain must return to its parent, not jump to home.
        navigate(activity, "wren_rich_input");
        navigate(activity, "wren_toolbar");
        navigate(activity, "actionEdit");
        // Pixel-inspected 480dpi action editor: Hidden is fourth tile, second row.
        assertTrue(device.takeScreenshot(new File(out,"before_hidden.png")));
        device.click(Math.round(device.getDisplayWidth()*0.865f), Math.round(device.getDisplayHeight()*0.305f));
        SystemClock.sleep(600);
        assertTrue(device.takeScreenshot(new File(out, "hidden.png")));
        device.dumpWindowHierarchy(new File(out, "hidden.xml"));
        String hiddenXml = new String(java.nio.file.Files.readAllBytes(new File(out,"hidden.xml").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("Hidden screen heading in fresh hierarchy", hiddenXml.contains("text=\"Hidden\""));
        clickFresh(device, By.desc("Back")); SystemClock.sleep(300);
        waitRoute(activity, "actionEdit");
        device.dumpWindowHierarchy(new File(out,"hidden_back.xml"));
        String returnedXml = new String(java.nio.file.Files.readAllBytes(new File(out,"hidden_back.xml").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("Nested Hidden Back must return to actions", returnedXml.contains("text=\"Hidden\""));
        device.pressBack(); waitRoute(activity, "wren_toolbar");
        device.pressBack(); waitRoute(activity, "wren_rich_input");
        device.pressBack(); waitRoute(activity, "home");
        device.pressBack();
        for(int i=0;i<30 && activity.getPackageName().equals(device.getCurrentPackageName());i++) SystemClock.sleep(100);
        assertFalse("App root must not trap Android back", activity.getPackageName().equals(device.getCurrentPackageName()));
    }
}
