package org.futo.inputmethod.latin.debugpreview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
        val magnitude = mutableFloatStateOf(0.0f)
        val stateHolder = mutableStateOf(state)
        setContent {
            // Change the magnitude AFTER first composition, the way live mic updates arrive.
            LaunchedEffect(Unit) {
                delay(600)
                magnitude.floatValue = level
            }
            UixThemeAuto {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Box(modifier = Modifier.fillMaxWidth().height(440.dp)) {
                        InnerRecognize(magnitude = magnitude, state = stateHolder)
                        // Debug label so each frame states the inputs the host actually received.
                        Text(
                            "level=${intent.getFloatExtra("level", -1f)} applied=${magnitude.floatValue} state=${stateHolder.value}",
                            modifier = Modifier.align(Alignment.TopStart),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}
