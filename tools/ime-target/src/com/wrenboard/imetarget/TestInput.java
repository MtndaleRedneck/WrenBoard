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
import android.widget.LinearLayout;

// Minimal deterministic input target for CI keyboard verification.
// White full-size EditText + control buttons + broadcast control + logcat text mirror.
// Not part of the released app.
public class TestInput extends Activity {
    private EditText edit;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver control = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final String a = intent.getAction();
            main.post(new Runnable() {
                public void run() {
                    if ("com.wrenboard.imetarget.CLEAR".equals(a)) {
                        edit.setText("");
                        Log.i("IMETARGET", "CLEARED");
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

        edit = new EditText(this);
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
        f.addAction("com.wrenboard.imetarget.SETCLIP");
        registerReceiver(control, f, Context.RECEIVER_EXPORTED);

        setContentView(root);
        edit.requestFocus();
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
