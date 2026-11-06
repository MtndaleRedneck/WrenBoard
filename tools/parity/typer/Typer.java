package parity;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Parity-harness test tool: taps a list of screen points with exact timing so continuous typing
 *  can be measured. args: "x,y:x,y:..." intervalMs [holdMs]. Prints one line per tap with its
 *  real uptime. Compiled to a dex at run time by build_typer.sh; nothing prebuilt is committed. */
public class Typer {
    static Object im; static Method inject;
    static void init() throws Exception {
        Class<?> c;
        try { c = Class.forName("android.hardware.input.InputManagerGlobal"); }
        catch (ClassNotFoundException e) { c = Class.forName("android.hardware.input.InputManager"); }
        im = c.getDeclaredMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
    }
    static void send(long down, long t, int action, float x, float y) throws Exception {
        MotionEvent e = MotionEvent.obtain(down, t, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        inject.invoke(im, e, 0);
        e.recycle();
    }
    static void sleepUntil(long t) {
        long d;
        while ((d = t - SystemClock.uptimeMillis()) > 0) SystemClock.sleep(Math.min(d, 5));
    }
    public static void main(String[] a) throws Exception {
        init();
        String[] pts = a[0].split(":");
        long interval = Long.parseLong(a[1]);
        long hold = a.length > 2 ? Long.parseLong(a[2]) : 40;
        long t0 = SystemClock.uptimeMillis() + 50;
        long prev = -1;
        for (int i = 0; i < pts.length; i++) {
            String[] xy = pts[i].split(",");
            float x = Float.parseFloat(xy[0]), y = Float.parseFloat(xy[1]);
            sleepUntil(t0 + i * interval);
            long down = SystemClock.uptimeMillis();
            send(down, down, MotionEvent.ACTION_DOWN, x, y);
            sleepUntil(down + hold);
            send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
            System.out.println("TAP " + i + " " + (int) x + "," + (int) y + " at " + down + (prev < 0 ? "" : " gap " + (down - prev)));
            prev = down;
        }
    }
}
