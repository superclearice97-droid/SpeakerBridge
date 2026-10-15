package com.speakerbridge.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.speakerbridge.ui.theme.SB
import kotlinx.coroutines.delay

/**
 * False when the user has turned animations off in Android's developer/accessibility
 * settings. Ambient and decorative motion checks this so the app stays calm for them.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
}

/**
 * Fade + rise into place the first time something appears, like the panels in the
 * reference video. Plays once (it remembers across scrolling and rotation), and can be
 * staggered with [delayMillis].
 */
fun Modifier.reveal(delayMillis: Int = 0, rise: Dp = 14.dp): Modifier = composed {
    val motion = rememberMotionEnabled()
    var seen by rememberSaveable { mutableStateOf(false) }
    val p = remember { Animatable(if (seen || !motion) 1f else 0f) }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    LaunchedEffect(Unit) {
        if (!seen && motion) {
            delay(delayMillis.toLong())
            p.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
        seen = true
    }
    graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * risePx
    }
}

/**
 * Body copy with a bright sweep that runs word by word across the text now and then,
 * the same trick as the highlighted paragraph in the reference video.
 */
@Composable
fun SweepText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    hotColor: Color = SB.Verified,
) {
    val motion = rememberMotionEnabled()
    val words = remember(text) { text.split(' ') }
    var hot by remember(text) { mutableIntStateOf(-10) }
    LaunchedEffect(text, motion) {
        if (!motion) return@LaunchedEffect
        delay(1400)
        while (true) {
            for (i in 0 until words.size + 4) {
                hot = i
                delay(65)
            }
            hot = -10
            delay(7000)
        }
    }
    val annotated = buildAnnotatedString {
        words.forEachIndexed { i, w ->
            val d = hot - i                      // 0 = leading edge, then a short fading tail
            if (d in 0..3) {
                withStyle(SpanStyle(color = lerp(hotColor, style.color, d / 4f))) { append(w) }
            } else {
                append(w)
            }
            if (i < words.lastIndex) append(" ")
        }
    }
    Text(annotated, style = style, modifier = modifier)
}

/** A thin red light that sweeps left to right under the header while a scan runs. */
@Composable
fun ScanLine(active: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp)) {
        if (active) {
            val t = rememberInfiniteTransition(label = "scanline")
            val x by t.animateFloat(
                initialValue = -0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing)),
                label = "scanline-x",
            )
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val w = size.width
                drawRect(
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, SB.Red, Color.Transparent),
                        startX = w * x,
                        endX = w * (x + 0.35f),
                    ),
                )
            }
        }
    }
}
