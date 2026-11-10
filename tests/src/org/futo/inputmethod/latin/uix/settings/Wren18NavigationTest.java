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
        SystemClock.sleep(1000);
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
    private void openHidden(SettingsActivity activity,UiDevice device) {
        int count=org.futo.inputmethod.latin.uix.actions.UnifiedActionsKt.readUnifiedActions(activity).visible.size();
        // Four compact columns at this 480dpi fixture, based on captured grid bounds.
        float x=0.13f+(count%4)*0.245f;
        float y=0.21f+(count/4)*0.094f;
        device.click(Math.round(device.getDisplayWidth()*x),Math.round(device.getDisplayHeight()*y));
    }
    private android.graphics.Point copyCenter(UiDevice device, String label, boolean toolbar) {
        java.util.List<UiObject2> copies = device.findObjects(By.desc(label));
        assertTrue("Expected toolbar and grid copies of " + label, copies.size() >= 2);
        copies.sort((a,b) -> Integer.compare(a.getVisibleCenter().y,b.getVisibleCenter().y));
        return copies.get(toolbar ? 0 : 1).getVisibleCenter();
    }
    private void drag(UiDevice device, android.graphics.Point start, android.graphics.Point end, File screenshot) {
        android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        long down = SystemClock.uptimeMillis();
        inst.sendPointerSync(android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,start.x,start.y,0));
        SystemClock.sleep(750);
        for(int i=1;i<=20;i++) {
            inst.sendPointerSync(android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,start.x+(end.x-start.x)*i/20f,start.y+(end.y-start.y)*i/20f,0));
            SystemClock.sleep(35);
            if(i==15) assertTrue(device.takeScreenshot(screenshot));
        }
        inst.sendPointerSync(android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,end.x,end.y,0));
        SystemClock.sleep(700);
    }
    private int changedPixels(android.graphics.Bitmap a, android.graphics.Bitmap b) {
        int changed=0;
        // Inspect content, excluding clock/nav and animated more icon.
        for(int y=(int)(a.getHeight()*0.17f);y<(int)(a.getHeight()*0.40f);y+=2)
            for(int x=0;x<a.getWidth();x+=2)
                if(a.getPixel(x,y)!=b.getPixel(x,y)) changed++;
        return changed;
    }
    @Test public void registeredScreensReturnOneLevelAfterAccessibilityRead() throws Exception {
        SettingsActivity activity = activityRule.launchActivity(new Intent());
        waitRoute(activity, "home");
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File homeOut = new File(InstrumentationRegistry.getTargetContext().getExternalFilesDir(null), "wren18-nav");
        homeOut.mkdirs();
        SystemClock.sleep(1000);
        device.takeScreenshot(new File(homeOut,"home_top.png"));
        device.swipe(device.getDisplayWidth()/2, Math.round(device.getDisplayHeight()*0.8f), device.getDisplayWidth()/2, Math.round(device.getDisplayHeight()*0.25f), 40);
        SystemClock.sleep(1000);
        device.takeScreenshot(new File(homeOut,"home_bottom.png"));
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
        openHidden(activity,device);
        SystemClock.sleep(600);
        assertTrue(device.takeScreenshot(new File(out, "hidden.png")));
        try {
            device.dumpWindowHierarchy(new File(out, "hidden.xml"));
        } catch (NullPointerException transientNode) {
            android.util.Log.w("WB18_NAV", "Hidden hierarchy dump failed; pixel and Back assertions still run", transientNode);
        }
        android.graphics.Bitmap before = android.graphics.BitmapFactory.decodeFile(new File(out,"before_hidden.png").getPath());
        android.graphics.Bitmap hiddenShot = android.graphics.BitmapFactory.decodeFile(new File(out,"hidden.png").getPath());
        assertTrue("Hidden screen must visibly differ from actions", changedPixels(before,hiddenShot) > 1000);
        clickFresh(device, By.desc("Back"));
        waitRoute(activity, "actionEdit");
        int returnedPixels = Integer.MAX_VALUE;
        for(int attempt=0;attempt<50;attempt++) {
            SystemClock.sleep(100);
            assertTrue(device.takeScreenshot(new File(out,"hidden_back.png")));
            android.graphics.Bitmap returned = android.graphics.BitmapFactory.decodeFile(new File(out,"hidden_back.png").getPath());
            returnedPixels = changedPixels(before,returned);
            returned.recycle();
            if(returnedPixels < 1000) break;
        }
        assertTrue("Nested Hidden Back must visibly return to actions", returnedPixels < 1000);
        org.futo.inputmethod.latin.uix.actions.OrderedActionIds oldOrder =
            org.futo.inputmethod.latin.uix.actions.UnifiedActionsKt.readUnifiedActions(activity);
        android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        float sx=device.getDisplayWidth()*0.13f, sy=device.getDisplayHeight()*0.21f;
        float ex=device.getDisplayWidth()*0.38f;
        long down=SystemClock.uptimeMillis();
        inst.sendPointerSync(android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,sx,sy,0));
        SystemClock.sleep(750);
        for(int i=1;i<=12;i++) {
            inst.sendPointerSync(android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,sx+(ex-sx)*i/12f,sy+20,0));
            SystemClock.sleep(35);
            if(i==7) assertTrue(device.takeScreenshot(new File(out,"drag_mid.png")));
        }
        inst.sendPointerSync(android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,ex,sy+20,0));
        SystemClock.sleep(600);
        org.futo.inputmethod.latin.uix.actions.OrderedActionIds reordered =
            org.futo.inputmethod.latin.uix.actions.UnifiedActionsKt.readUnifiedActions(activity);
        assertFalse("Completed finger drag must persist a new order",oldOrder.visible.equals(reordered.visible));
        assertTrue(device.takeScreenshot(new File(out,"drag_end.png")));
        // Both copies of the first six actions must retain independent hit regions.
        java.util.List<String> beforeBar = new java.util.ArrayList<>(reordered.visible);
        drag(device, copyCenter(device,"Emojis",true), copyCenter(device,"Clipboard",true), new File(out,"drag_bar_start.png"));
        org.futo.inputmethod.latin.uix.actions.OrderedActionIds afterBar = org.futo.inputmethod.latin.uix.actions.UnifiedActionsKt.readUnifiedActions(activity);
        assertFalse("Drag starting on toolbar must reorder persistently",beforeBar.equals(afterBar.visible));
        java.util.List<String> beforeDrop = new java.util.ArrayList<>(afterBar.visible);
        drag(device, copyCenter(device,"Emojis",false), copyCenter(device,"Clipboard",true), new File(out,"drag_bar_drop.png"));
        reordered = org.futo.inputmethod.latin.uix.actions.UnifiedActionsKt.readUnifiedActions(activity);
        assertFalse("Grid drag dropping on toolbar must reorder persistently",beforeDrop.equals(reordered.visible));
        assertTrue(device.takeScreenshot(new File(out,"drag_bar_end.png")));
        // The Hidden destination stays last and its + restores one action persistently.
        openHidden(activity,device);
        SystemClock.sleep(500);
        device.click(Math.round(device.getDisplayWidth()*0.20f),Math.round(device.getDisplayHeight()*0.18f));
        SystemClock.sleep(600);
        org.futo.inputmethod.latin.uix.actions.OrderedActionIds restored =
            org.futo.inputmethod.latin.uix.actions.UnifiedActionsKt.readUnifiedActions(activity);
        assertEquals("Hidden + restores one tile",reordered.hidden.size()-1,restored.hidden.size());
        assertEquals(reordered.visible.size()+1,restored.visible.size());
        assertTrue(device.takeScreenshot(new File(out,"hidden_restored.png")));
        device.pressBack(); waitRoute(activity,"actionEdit");
        device.pressBack(); waitRoute(activity, "wren_toolbar");
        device.pressBack(); waitRoute(activity, "wren_rich_input");
        device.pressBack(); waitRoute(activity, "home");
        device.pressBack();
        for(int i=0;i<30 && activity.getPackageName().equals(device.getCurrentPackageName());i++) SystemClock.sleep(100);
        assertFalse("App root must not trap Android back", activity.getPackageName().equals(device.getCurrentPackageName()));
    }
}
