package org.futo.inputmethod.latin.uix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val MoreDecelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val MoreAccelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/** Original drawing, with separate measured open and close motion. */
@Composable
fun WrenMoreIcon(active: Boolean, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(if(active) 0f else 1f) }
    LaunchedEffect(active) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(if(active) 475 else 485, easing = LinearEasing))
    }
    Canvas(modifier.size(24.dp).semantics { contentDescription = if(active) "Close actions" else "More actions" }) {
        val ms = progress.value * if(active) 475f else 485f
        val unit = size.minDimension / 24f
        val orange = Color(0xFFFF8710)
        fun phase(start: Float, duration: Float) = ((ms - start) / duration).coerceIn(0f, 1f)
        val dotsScale = if(active) 1f - MoreAccelerate.transform(phase(0f, 65f)) else MoreDecelerate.transform(phase(185f, 60f))
        val xScale = if(active) MoreDecelerate.transform(phase(65f, 40f)) else 1f - MoreAccelerate.transform(phase(0f, 85f))
        val xAngle = if(active) -180f * (1f - MoreDecelerate.transform(phase(65f, 410f))) else -45f * MoreAccelerate.transform(phase(0f, 85f))
        val dotsAngle = if(active) 0f else 90f * (1f - MoreDecelerate.transform(phase(185f, 300f)))
        if(dotsScale > 0f) rotate(dotsAngle) {
            scale(dotsScale) {
                for(i in -1..1) drawCircle(orange, 1.3f * unit, Offset(center.x + i * 4.5f * unit, center.y))
            }
        }
        if(!active && ms in 85f..185f) drawCircle(orange, 1.3f * unit, center)
        if(xScale > 0f) rotate(xAngle) {
            scale(xScale) {
                val d = 4.5f * unit
                drawLine(orange, center + Offset(-d, -d), center + Offset(d, d), 1.5f * unit, StrokeCap.Round)
                drawLine(orange, center + Offset(-d, d), center + Offset(d, -d), 1.5f * unit, StrokeCap.Round)
            }
        }
    }
}
