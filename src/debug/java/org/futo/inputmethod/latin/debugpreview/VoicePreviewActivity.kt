package org.futo.inputmethod.latin.debugpreview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.uix.theme.UixThemeAuto
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.ui.InnerRecognize

/**
 * Debug-only host for layout screenshots of the production Voice Input composable.
 * Extras: level (float 0..1, circle level) and state (MagnitudeState name). Not a microphone test.
 */
class VoicePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val level = intent.getFloatExtra("level", 0.0f)
        val state = try {
            MagnitudeState.valueOf(intent.getStringExtra("state") ?: "TALKING")
        } catch (e: IllegalArgumentException) {
            MagnitudeState.TALKING
        }
        setContent {
            UixThemeAuto {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.fillMaxWidth().height(440.dp)) {
                        InnerRecognize(
                            magnitude = remember { mutableFloatStateOf(level) },
                            state = remember { mutableStateOf(state) }
                        )
                    }
                }
            }
        }
    }
}
