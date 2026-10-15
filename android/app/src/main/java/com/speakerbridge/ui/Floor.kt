package com.speakerbridge.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speakerbridge.model.Device
import com.speakerbridge.ui.theme.SB
import com.speakerbridge.ui.theme.SBType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

// Plane layout, in "cells": 4 clusters per row; each cluster is a tile plus two swatches.
private const val COLS = 4
private const val PLANE_CELLS_WIDE = 16f
private const val PIVOT_Y = 0.45f
private const val TILT_REST = 55f       // degrees: the bird's-eye angle
private const val TILT_CLOSE = 31f      // flatter when the camera dives into a tile
private const val SCALE_REST = 0.82f
private const val ZOOM_GAIN = 2.2f      // extra scale at full zoom (so ~2.6x overall)

private fun slotCx(i: Int, cell: Float) = cell * (0.5f + (i % COLS) * 4f) + cell * 1.3f
private fun slotCy(i: Int, cell: Float) = cell * (1.5f + (i / COLS) * 2.6f) + cell * 0.65f

/**
 * The "bird's-eye floor": a tilted grid with every discovered speaker laid on it as a
 * poster tile.
 *
 * Motion (all of it switched off when the system has animations disabled):
 *  - on launch the camera starts zoomed into one cell and pulls back to reveal the floor
 *  - the floor drifts slowly by itself, pans when you drag it sideways (with momentum),
 *    and slides at a slower rate than the page when you scroll (parallax)
 *  - while scanning, a red pulse runs across the empty cells like a radar sweep
 *  - found speakers pop in one after another, then a spotlight moves between them
 *  - tapping a tile dives the camera into it; closing the details pulls back out
 *  - the floor dims behind the details panel
 */
@Composable
fun SpeakerFloor(
    devices: List<Device>,
    scanning: Boolean,
    revision: Int,
    sheetOpen: Boolean,
    scroll: () -> Float,
    ticker: String,
    leftCoord: String,
    rightCoord: String,
    onTileClick: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberMotionEnabled()
    val scope = rememberCoroutineScope()
    val dens = LocalDensity.current

    val anim = rememberInfiniteTransition(label = "floor")
    val drift by anim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val sweep by anim.animateFloat(
        initialValue = 0f, targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "sweep",
    )

    val focus = remember { Animatable(0f) }          // 0 = bird's-eye, 1 = diving into a tile
    var focusIndex by remember { mutableIntStateOf(5) }
    val panX = remember { Animatable(0f) }
    var introDone by remember { mutableStateOf(false) }
    var spot by remember { mutableIntStateOf(0) }

    // Launch: start close on a cell, then pull back to the full floor.
    LaunchedEffect(Unit) {
        if (motion) {
            focusIndex = 5
            focus.snapTo(1f)
            delay(300)
            focus.animateTo(0f, tween(1800, easing = FastOutSlowInEasing))
        }
        introDone = true
    }
    // Closing the details pulls the camera back out of the tile.
    LaunchedEffect(sheetOpen) {
        if (introDone && !sheetOpen && focus.value > 0f) {
            focus.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
        }
    }
    // A spotlight that hops from speaker to speaker.
    LaunchedEffect(devices.size, motion) {
        if (!motion || devices.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(1600)
            spot = (spot + 1) % devices.size
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(310.dp)
            .clipToBounds()
            .background(SB.Ink),
    ) {
        val planeW: Dp = maxWidth * 2.2f
        val cell: Dp = planeW / PLANE_CELLS_WIDE
        val slots = max(12, ((devices.size + COLS - 1) / COLS) * COLS)
        val rows = slots / COLS
        val planeH: Dp = cell * max(12.7f, 2.5f + rows * 2.6f)
        val maxPan = with(dens) { (maxWidth * 0.4f).toPx() }

        val dragState = rememberDraggableState { delta ->
            scope.launch { panX.snapTo((panX.value + delta).coerceIn(-maxPan, maxPan)) }
        }

        val onTile: (Int, Device) -> Unit = { i, d ->
            if (focus.value == 0f && !focus.isRunning) {
                scope.launch {
                    focusIndex = i
                    if (motion) focus.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
                    onTileClick(d)
                }
            }
        }

        // The tilted plane. Hit-testing follows the layer transform, so tiles stay tappable.
        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(planeW, planeH)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = motion && !sheetOpen,
                    onDragStopped = { velocity ->
                        panX.updateBounds(-maxPan, maxPan)
                        panX.animateDecay(velocity, exponentialDecay<Float>(frictionMultiplier = 1.6f))
                    },
                )
                .graphicsLayer {
                    val f = focus.value
                    val calm = 1f - f
                    val cellPx = cell.toPx()
                    val planeHpx = planeH.toPx()
                    val rot = TILT_REST - (TILT_REST - TILT_CLOSE) * f
                    val s = SCALE_REST * (1f + ZOOM_GAIN * f)
                    val d = if (motion) drift else 0.5f

                    rotationX = rot
                    scaleX = s
                    scaleY = s
                    cameraDistance = 12f * density
                    transformOrigin = TransformOrigin(0.5f, PIVOT_Y)

                    // Bring the focused tile to the middle of the view.
                    val dx = slotCx(focusIndex, cellPx) - 0.5f * cellPx * PLANE_CELLS_WIDE
                    val dy = slotCy(focusIndex, cellPx) - PIVOT_Y * planeHpx
                    val flatten = cos(Math.toRadians(rot.toDouble())).toFloat()

                    translationX = ((d - 0.5f) * -cellPx * 1.2f + panX.value) * calm - s * dx * f
                    translationY = (cellPx * 0.9f + (d - 0.5f) * 110f + scroll() * 140f) * calm +
                        ((0.5f - PIVOT_Y) * planeHpx - s * dy * flatten) * f
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val step = cell.toPx()
                var x = 0f
                while (x <= size.width) {
                    drawLine(SB.LineSoft, Offset(x, 0f), Offset(x, size.height), 2f)
                    x += step
                }
                var y = 0f
                while (y <= size.height) {
                    drawLine(SB.LineSoft, Offset(0f, y), Offset(size.width, y), 2f)
                    y += step
                }
            }
            for (i in 0 until slots) {
                val x = cell * (0.5f + (i % COLS) * 4f)
                val y = cell * (1.5f + (i / COLS) * 2.6f)
                val d = devices.getOrNull(i)
                if (d != null) {
                    TileCluster(
                        index = i,
                        d = d,
                        revision = revision,
                        cell = cell,
                        spotlight = motion && i == spot,
                        onClick = { onTile(i, d) },
                        modifier = Modifier.offset(x, y),
                    )
                } else {
                    // Empty cell. While scanning, a red pulse travels around the cells.
                    Box(
                        Modifier
                            .offset(x, y)
                            .requiredSize(cell * 1.8f, cell * 1.3f)
                            .drawBehind {
                                val k = if (scanning && motion) {
                                    val gap = abs(sweep - (i % 12))
                                    max(0f, 1f - min(gap, 12f - gap) / 2.5f)
                                } else {
                                    0f
                                }
                                val rest = if (scanning) 0.5f else 0.6f
                                val c = lerp(SB.Line.copy(alpha = rest), SB.Red, k * 0.9f)
                                drawRect(c, style = Stroke(width = 1.dp.toPx()))
                            },
                    )
                }
            }
        }

        // Depth fade, like looking down at a lit floor from above.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to SB.Ink, 0.3f to Color.Transparent, 0.72f to Color.Transparent, 1f to SB.Ink,
                ),
            ),
        )
        // The floor sinks into the dark while the details panel is open.
        val dimAlpha by animateFloatAsState(if (sheetOpen) 0.62f else 0f, tween(450), label = "dim")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dimAlpha }.background(SB.Ink))

        Text(leftCoord, style = SBType.Mono.copy(color = SB.Dim), modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        Text(rightCoord, style = SBType.Mono.copy(color = SB.Dim), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
        Ticker(ticker, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}

/** One speaker: a poster tile plus two swatches, which pops in and can take the spotlight. */
@Composable
private fun TileCluster(
    index: Int,
    d: Device,
    @Suppress("UNUSED_PARAMETER") revision: Int,
    cell: Dp,
    spotlight: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val motion = rememberMotionEnabled()
    val reveal = remember(d.key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(d.key) {
        if (motion) {
            delay((index * 90L).coerceAtMost(900L))
            reveal.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        }
    }
    val glow by animateFloatAsState(if (spotlight) 1f else 0f, tween(450), label = "glow")

    val c = tileColor(d)
    val dimmed = d.status == "control_only" || d.status == "not_speaker"
    val dim = if (dimmed) 0.45f else 1f

    Box(
        modifier
            .requiredSize(cell * 2.6f, cell * 1.3f)
            .graphicsLayer {
                val r = reveal.value
                val pop = (0.8f + 0.2f * r) * (1f + 0.06f * glow)
                alpha = r
                scaleX = pop
                scaleY = pop
                translationY = (1f - r) * 36f - glow * 6f
            },
    ) {
        Box(
            Modifier.offset(cell * 1.85f, 0.dp).requiredSize(cell * 0.75f, cell * 0.6f)
                .graphicsLayer { alpha = (0.55f + 0.25f * glow) * dim }
                .background(c, RoundedCornerShape(2.dp)),
        )
        Box(
            Modifier.offset(cell * 1.85f, cell * 0.65f).requiredSize(cell * 0.75f, cell * 0.6f)
                .graphicsLayer { alpha = (0.3f + 0.2f * glow) * dim }
                .background(c, RoundedCornerShape(2.dp)),
        )
        Tile(d, glow, Modifier.requiredSize(cell * 1.8f, cell * 1.3f).clickable(onClick = onClick))
    }
}

@Composable
private fun Tile(d: Device, glow: Float, modifier: Modifier) {
    val color = tileColor(d)
    val ink = if (color.luminance() > 0.5f) Color(0xFF111111) else SB.Cream
    val ring = when (d.status) {
        "verified" -> SB.Verified
        "group" -> SB.Group
        "ready_to_test" -> SB.Cream.copy(alpha = 0.0f)
        else -> Color.Black.copy(alpha = 0.55f)
    }
    val dimmed = d.status == "control_only" || d.status == "not_speaker"
    Box(
        modifier
            .graphicsLayer { alpha = if (dimmed) 0.45f else 1f }
            .background(color, RoundedCornerShape(2.dp))
            .border(1.5.dp, SB.Cream.copy(alpha = 0.75f * glow), RoundedCornerShape(2.dp))
            .border(if (ring.alpha > 0f) 3.dp else 0.dp, ring, RoundedCornerShape(2.dp))
            .padding(6.dp),
    ) {
        Text(
            d.protocols.keys.joinToString(" · ") { shortProto(it) }.uppercase(),
            style = SBType.Mono.copy(color = ink.copy(alpha = 0.75f), fontSize = 6.sp),
            maxLines = 1, overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            d.manufacturer.ifEmpty { "Speaker" }.uppercase(),
            style = SBType.Headline.copy(color = ink, fontSize = 19.sp, lineHeight = 19.sp),
            maxLines = 1, overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            d.name.ifEmpty { d.ip },
            style = SBType.Mono.copy(color = ink, fontSize = 8.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Ticker(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(max = 300.dp)
            .background(SB.Ink.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
            .border(1.dp, SB.Line, RoundedCornerShape(3.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            "»  $text  ·  $text  ·",
            style = SBType.Mono.copy(color = SB.Cream.copy(alpha = 0.8f)),
            maxLines = 1,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp),
        )
    }
}

/** Same brand always gets the same poster colour. */
fun tileColor(d: Device): Color =
    SB.Tiles[abs((d.manufacturer.ifEmpty { d.name }).hashCode() % SB.Tiles.size)]

fun shortProto(key: String): String = when (key) {
    "googlecast" -> "Cast"
    "airplay" -> "AirPlay"
    "raop" -> "RAOP"
    "dlna_renderer" -> "DLNA"
    "spotify_connect" -> "Spotify"
    "sony_scalar" -> "Sony API"
    "vizio_smartcast" -> "Vizio API"
    "sonos" -> "Sonos"
    else -> key
}

@Composable
fun SpeakerGlyph(size: Dp, color: Color = SB.Red, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // cabinet
        drawRoundRect(color, topLeft = Offset(w * 0.18f, h * 0.08f),
            size = androidx.compose.ui.geometry.Size(w * 0.5f, h * 0.84f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
        // cones
        drawCircle(SB.Ink, radius = w * 0.15f, center = Offset(w * 0.43f, h * 0.62f))
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.43f, h * 0.62f))
        drawCircle(SB.Ink, radius = w * 0.07f, center = Offset(w * 0.43f, h * 0.27f))
        // sound waves
        for ((i, r) in listOf(0.16f, 0.28f).withIndex()) {
            drawArc(
                color.copy(alpha = 1f - i * 0.35f), startAngle = -50f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(w * 0.62f - w * r, h * 0.5f - w * r),
                size = androidx.compose.ui.geometry.Size(w * r * 2f, w * r * 2f),
                style = Stroke(width = w * 0.06f),
            )
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(SB.Line))
}

@Composable
fun MonoCaption(text: String, color: Color = SB.Muted, modifier: Modifier = Modifier) {
    Column(modifier) { Text(text.uppercase(), style = SBType.Mono.copy(color = color)) }
}
