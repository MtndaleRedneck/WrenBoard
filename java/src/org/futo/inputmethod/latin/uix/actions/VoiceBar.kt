package org.futo.inputmethod.latin.uix.actions

import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.futo.inputmethod.latin.R
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

/**
 * WrenBoard 1.4 (S18): the v9 voice bar - a compact strip replacing the full voice panel.
 * States differ by shape, not just color: dots = waiting, mic + level bars = listening,
 * ring = working, orange mic = stopped (tap to dictate). Device pill (center) only when a
 * Bluetooth headset is available; its label names the system-reported route and tapping it
 * uses FUTO's existing microphone switcher.
 */

enum class VoiceBarMode { Stopped, Waiting, Listening, Working }

private val BarBackground = Color(0xFF3C3B3E)
private val WrenOrange = Color(0xFFFC8613)
private val PillActiveBg = Color(0xFF624A32)   // waiting + listening
private val PillWorkingBg = Color(0xFF4A3D33)
private val PillWorkingRing = Color(0xFFA8601E)
private val PillStoppedIcon = Color(0xFF2B2A2D)

@Composable
fun VoiceRecognizeBar(
    mode: VoiceBarMode,
    magnitude: Float,
    device: MicrophoneDeviceState?,
    onChevron: () -> Unit,
    onPillTap: () -> Unit
) {
    LaunchedEffect(mode) { Log.i("WBDBG", "WINDOW VOICEBAR state=$mode") }

    val closeDesc = stringResource(R.string.voicebar_close)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(BarBackground),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.chevron_left),
            contentDescription = null,
            tint = WrenOrange,
            modifier = Modifier
                .padding(start = 16.dp)
                .size(20.dp)
                .onGloballyPositioned {
                    val c = it.boundsInWindow().center
                    Log.i("WBDBG", "WINDOW VOICEBARCHEVRON cx=${c.x.toInt()} cy=${c.y.toInt()} w=${it.size.width} h=${it.size.height}")
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onChevron
                )
                .semantics {
                    contentDescription = closeDesc
                    role = Role.Button
                }
        )

        if (mode == VoiceBarMode.Stopped) {
            Text(
                text = stringResource(R.string.voicebar_tap_to_dictate),
                color = WrenOrange,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1.0f))

        // Centered device pill: only when a headset is available; label = system-reported route
        if (device?.bluetoothAvailable == true) {
            // Label/icon reflect the recorder's ACTUAL routed input device; the toggle
            // still requests the preference flip via FUTO's existing switcher.
            val route = device.actualRouteIsBluetooth
            val label = when (route) {
                true -> stringResource(R.string.voicebar_bluetooth_mic)
                false -> stringResource(R.string.voicebar_phone_mic)
                null -> stringResource(R.string.voicebar_mic_generic)
            }
            val switchDesc = stringResource(R.string.voicebar_switch_mic, label)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .border(1.5.dp, WrenOrange, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .onGloballyPositioned {
                        val c = it.boundsInWindow().center
                        Log.i("WBDBG", "WINDOW VOICEDEVPILL cx=${c.x.toInt()} cy=${c.y.toInt()} w=${it.size.width} h=${it.size.height}")
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { device.setBluetooth(!device.bluetoothActive) }
                    )
                    .semantics {
                        contentDescription = switchDesc
                        role = Role.Switch
                    }
            ) {
                Icon(
                    painter = painterResource(if (route == true) R.drawable.bluetooth else R.drawable.mic),
                    contentDescription = null,
                    tint = WrenOrange,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(text = label, color = WrenOrange, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.weight(1.0f))

        val pillDesc = when (mode) {
            VoiceBarMode.Stopped -> stringResource(R.string.voicebar_tap_to_dictate)
            VoiceBarMode.Waiting -> stringResource(R.string.voicebar_waiting)
            VoiceBarMode.Listening -> stringResource(R.string.voicebar_listening)
            VoiceBarMode.Working -> stringResource(R.string.voicebar_working)
        }
        val tappable = mode != VoiceBarMode.Working
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(end = 16.dp)
                .size(width = 56.dp, height = 28.dp)
                .background(
                    when (mode) {
                        VoiceBarMode.Stopped -> WrenOrange
                        VoiceBarMode.Working -> PillWorkingBg
                        else -> PillActiveBg
                    },
                    RoundedCornerShape(14.dp)
                )
                .onGloballyPositioned {
                    val c = it.boundsInWindow().center
                    Log.i("WBDBG", "WINDOW VOICEBARPILL cx=${c.x.toInt()} cy=${c.y.toInt()} w=${it.size.width} h=${it.size.height}")
                }
                .then(
                    if (tappable) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPillTap
                    ) else Modifier
                )
                .clearAndSetSemantics {
                    contentDescription = pillDesc
                    if (tappable) role = Role.Button
                }
        ) {
            when (mode) {
                VoiceBarMode.Stopped -> Icon(
                    painter = painterResource(R.drawable.mic),
                    contentDescription = null,
                    tint = PillStoppedIcon,
                    modifier = Modifier.size(11.dp, 15.dp)
                )
                VoiceBarMode.Waiting -> WaitingDots()
                VoiceBarMode.Listening -> ListeningBars(magnitude)
                VoiceBarMode.Working -> WorkingRing()
            }
        }
    }
}

@Composable
private fun WaitingDots() {
    val transition = rememberInfiniteTransition(label = "waiting")
    val phase by transition.animateFloat(
        initialValue = 0.0f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "wave"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0..2) {
            val offsetDp = kotlin.math.sin((phase * 2.0 * Math.PI) + i * 1.05) * 1.7
            Canvas(modifier = Modifier
                .size(4.7.dp)
                .offset(y = offsetDp.dp)) {
                drawCircle(WrenOrange)
            }
        }
    }
}

@Composable
private fun ListeningBars(magnitude: Float) {
    // still-frame base heights from the v9 spec (16/32/44/26/36 px at 3 px/dp), scaled by live magnitude
    val base = listOf(5.3f, 10.7f, 14.7f, 8.7f, 12.0f)
    val scale = 0.35f + 0.65f * magnitude.coerceIn(0.0f, 1.0f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.mic),
            contentDescription = null,
            tint = WrenOrange,
            modifier = Modifier.size(8.7.dp, 11.7.dp)
        )
        Spacer(modifier = Modifier.width(4.7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            base.forEach { h ->
                Box(
                    modifier = Modifier
                        .width(2.3.dp)
                        .height((h * scale).coerceAtLeast(2.0f).dp)
                        .background(WrenOrange, RoundedCornerShape(1.15.dp))
                )
            }
        }
    }
}

@Composable
private fun WorkingRing() {
    val transition = rememberInfiniteTransition(label = "working")
    val rotation by transition.animateFloat(
        initialValue = 0.0f, targetValue = 360.0f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    Canvas(modifier = Modifier.size(14.7.dp)) {
        drawArc(
            color = PillWorkingRing,
            startAngle = rotation,
            sweepAngle = 270.0f,
            useCenter = false,
            topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
            size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
            style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
}
