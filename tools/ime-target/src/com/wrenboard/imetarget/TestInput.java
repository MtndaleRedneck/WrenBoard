package com.wrenboard.imetarget;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;

// Minimal deterministic input target for CI keyboard verification.
// White full-size EditText + control buttons + broadcast control + logcat text mirror.
// Not part of the released app.
public class TestInput extends Activity {

    // EditText that accepts committed content (image/gif): logs the handoff and drains the
    // provider stream so the sender's fetch path is fully exercised (commitContent, not text).
    public static class GifEditText extends EditText {
        public GifEditText(android.content.Context context) { super(context); }
        @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
            android.view.inputmethod.InputConnection base = super.onCreateInputConnection(outAttrs);
            outAttrs.contentMimeTypes = new String[] {"image/gif"};
            return new android.view.inputmethod.InputConnectionWrapper(base, true) {
                @Override public boolean commitContent(android.view.inputmethod.InputContentInfo info, int flags, android.os.Bundle opts) {
                    String mime = "-"; long n = -1;
                    try {
                        mime = info.getDescription().getMimeType(0);
                        info.requestPermission();
                        java.io.InputStream in = getContext().getContentResolver().openInputStream(info.getContentUri());
                        n = 0; byte[] buf = new byte[8192]; int r; while ((r = in.read(buf)) != -1) n += r; in.close();
                        info.releasePermission();
                    } catch (Exception e) { Log.i("IMETARGET", "CONTENT= error=" + e); return false; }
                    Log.i("IMETARGET", "CONTENT= mime=" + mime + " bytes=" + n);
                    return true;
                }
            };
        }
    }
    private static final String PREFS = "fixture";
    private static final String KEY_MODE = "mode";
    private static final String MODE_NORMAL = "NORMAL";
    private static final String MODE_NOLEARN = "NOLEARN";
    private static final String MODE_NOCORRECT = "NOCORRECT";

    private EditText edit;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Mode changes persist and RECREATE the activity: a live setImeOptions on a focused
    // field never renegotiates the IME session (rev29 neg4 executed: the NO_PERSONALIZED_
    // LEARNING flag was never seen). Persisted + applied in onCreate, the EditorInfo is
    // fresh at onStartInput. MODE=... APPLIED is the harness readiness marker.
    private void setMode(String mode) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply();
        Log.i("IMETARGET", "MODE_SET=" + mode);
        recreate();
    }

    private final BroadcastReceiver control = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final String a = intent.getAction();
            main.post(new Runnable() {
                public void run() {
                    if ("com.wrenboard.imetarget.CLEAR".equals(a)) {
                        edit.setText("");
                        Log.i("IMETARGET", "CLEARED");
                    } else if ("com.wrenboard.imetarget.CAPSENT".equals(a)) {
                        boolean on = intent.getBooleanExtra("on", true);
                        edit.setInputType(on
                                ? (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                                : android.text.InputType.TYPE_CLASS_TEXT);
                        edit.setText("");
                        Log.i("IMETARGET", "CAPSENT=" + (on ? "ON" : "OFF"));
                    } else if ("com.wrenboard.imetarget.PASSWORD".equals(a)) {
                        boolean on = intent.getBooleanExtra("on", true);
                        edit.setInputType(on
                                ? (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
                                : (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
                        edit.setText("");
                        Log.i("IMETARGET", "PASSWORD=" + (on ? "ON" : "OFF"));
                    } else if ("com.wrenboard.imetarget.SELECTALL".equals(a)) {
                        edit.requestFocus();
                        edit.selectAll();
                        Log.i("IMETARGET", "SELECTALL=1");
                    } else if ("com.wrenboard.imetarget.SELECT".equals(a)) {
                        edit.requestFocus();
                        int st = intent.getIntExtra("start", 0);
                        int en = intent.getIntExtra("end", edit.getText().length());
                        int len = edit.getText().length();
                        if (st < 0) st = 0; if (en > len) en = len; if (st > en) st = en;
                        edit.setSelection(st, en);
                        Log.i("IMETARGET", "SELECT=" + st + "-" + en);
                    } else if ("com.wrenboard.imetarget.NOLEARN".equals(a)) {
                        boolean on = intent.getBooleanExtra("on", true);
                        setMode(on ? MODE_NOLEARN : MODE_NORMAL);
                    } else if ("com.wrenboard.imetarget.NOCORRECT".equals(a)) {
                        boolean on = intent.getBooleanExtra("on", true);
                        setMode(on ? MODE_NOCORRECT : MODE_NORMAL);
                    } else if ("com.wrenboard.imetarget.REPORT".equals(a)) {
                        // On-demand current-content read: FIELD= is otherwise only logged on
                        // change, so an UNCHANGED field is invisible after a logcat clear.
                        Log.i("IMETARGET", "FIELD=" + edit.getText().toString());
                    } else if ("com.wrenboard.imetarget.SETCLIP".equals(a)) {
                        ClipboardManager cm =
                                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("probe", "clipboard-probe-42"));
                        Log.i("IMETARGET", "CLIPSET=clipboard-probe-42");
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        edit = new GifEditText(this);
        edit.setBackgroundColor(Color.WHITE);
        edit.setTextColor(Color.BLACK);
        edit.setMinLines(10);
        edit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                Log.i("IMETARGET", "FIELD=" + s.toString());
            }
        });
        // B2: editor actions (e.g. an IME sending ENTER/ACTION to the host) are invisible in
        // FIELD= when the action leaves the text unchanged - log them so checks can prove
        // "no host action was delivered", not just "host text did not change".
        edit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                Log.i("IMETARGET", "ACTION=" + actionId + (event != null ? " keycode=" + event.getKeyCode() : ""));
                return false;
            }
        });
        root.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        addButton(bar, "Clear", new Runnable() {
            public void run() { edit.setText(""); }
        });
        addButton(bar, "SetClip", new Runnable() {
            public void run() {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("probe", "clipboard-probe-42"));
            }
        });
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        IntentFilter f = new IntentFilter();
        f.addAction("com.wrenboard.imetarget.CLEAR");
        f.addAction("com.wrenboard.imetarget.CAPSENT");
        f.addAction("com.wrenboard.imetarget.PASSWORD");
        f.addAction("com.wrenboard.imetarget.SELECTALL");
        f.addAction("com.wrenboard.imetarget.SELECT");
        f.addAction("com.wrenboard.imetarget.NOLEARN");
        f.addAction("com.wrenboard.imetarget.NOCORRECT");
        f.addAction("com.wrenboard.imetarget.SETCLIP");
        registerReceiver(control, f, Context.RECEIVER_EXPORTED);

        String mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODE, MODE_NORMAL);
        if (MODE_NOLEARN.equals(mode)) {
            edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    | android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        } else if (MODE_NOCORRECT.equals(mode)) {
            // Email-variation field: mInputTypeNoAutoCorrect=true via isEmailVariation
            // (InputAttributes) while suggestions stay shown - the production
            // autocorrect-disabled-field condition the learn gate actually reads. The user
            // pref_key_auto_correction toggle never reaches that gate (rev29 executed).
            edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        }

        setContentView(root);
        edit.requestFocus();
        Log.i("IMETARGET", "MODE=" + mode + " APPLIED");
        Log.i("IMETARGET", "READY");
    }

    private void addButton(LinearLayout bar, String label, final Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
        bar.addView(b, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }
}
