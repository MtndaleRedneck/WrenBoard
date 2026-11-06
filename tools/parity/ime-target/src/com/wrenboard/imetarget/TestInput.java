package com.wrenboard.imetarget;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;

/** Parity-harness input target: one full-screen plain text field. Every change is logged as
 *  IMETARGET FIELD=<text>; the CLEAR broadcast (from the harness over adb) empties it. */
public class TestInput extends Activity {
    static final String CLEAR = "com.wrenboard.imetarget.CLEAR";
    private EditText edit;

    private final BroadcastReceiver control = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (CLEAR.equals(i.getAction())) {
                edit.setText("");
                Log.i("IMETARGET", "CLEARED");
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        edit = new EditText(this);
        edit.setBackgroundColor(Color.WHITE);
        edit.setTextColor(Color.BLACK);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        edit.setMinLines(8);
        edit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) { Log.i("IMETARGET", "FIELD=" + s.toString()); }
        });
        setContentView(edit);
        registerReceiver(control, new IntentFilter(CLEAR), Context.RECEIVER_EXPORTED);
        edit.requestFocus();
        Log.i("IMETARGET", "READY");
    }

    @Override protected void onDestroy() {
        unregisterReceiver(control);
        super.onDestroy();
    }
}
