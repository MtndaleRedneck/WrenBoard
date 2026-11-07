package org.futo.inputmethod.latin

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Test-only (androidTest APK, never in the app): one plain text field for GlideProofTest.
 */
class GlideProofActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val field = EditText(this)
        field.contentDescription = "wb_glide_field"
        field.inputType = InputType.TYPE_CLASS_TEXT
        field.setSingleLine(true)
        root.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        field.requestFocus()
    }
}
