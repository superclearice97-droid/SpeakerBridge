package com.speakerbridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speakerbridge.model.Device
import com.speakerbridge.playback.PhoneStream
import com.speakerbridge.playback.StreamState
import com.speakerbridge.playback.TestSound
import com.speakerbridge.ui.theme.SB
import com.speakerbridge.ui.theme.SBType

private val SECTION_ORDER = listOf("verified", "group", "ready_to_test", "control_only", "not_speaker")
private val SECTION_TITLE = mapOf(
    "verified" to "Verified",
    "group" to "Cast groups",
    "ready_to_test" to "Ready to test",
    "control_only" to "Control only",
    "not_speaker" to "Not speakers",
)
private val STATUS_SHORT = mapOf(
    "verified" to "VERIFIED",
    "group" to "IN SYNC",
    "ready_to_test" to "READY",
    "control_only" to "CONTROL",
    "not_speaker" to "OTHER",
)

fun statusColor(status: String): Color = when (status) {
    "verified" -> SB.Verified
    "group" -> SB.Group
    "ready_to_test" -> SB.Ready
    else -> SB.Muted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: ScanViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stream by PhoneStream.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val startStream = rememberStreamStarter {
        vm.state.value.let { s -> s.visible.filter { it.key in s.together && TestSound.canPlay(it) } }
    }
    var selected by remember { mutableStateOf<Device?>(null) }
    val listState = rememberLazyListState()

    // 0 while the floor is fully on screen, 1 once the page has scrolled past it.
    val floorScroll by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "floor" }
            if (info == null) 1f else (-info.offset.toFloat() / info.size).coerceIn(0f, 1f)
        }
    }

    val visible = state.visible
    val speakers = state.devices.count { it.status != "not_speaker" }
    val usable = state.devices.count { it.status in setOf("ready_to_test", "group", "verified") }
    val subnet = state.devices.firstOrNull()?.ip?.substringBeforeLast('.')?.let { "$it.0/24" } ?: "—.—.—.0/24"
    val ticker = when {
        state.scanning -> "SCANNING MDNS + SSDP · LISTENING FOR CAST, AIRPLAY & DLNA"
        !state.hasScanned -> "TAP SCAN TO LOOK FOR SPEAKERS"
        speakers == 0 -> "NO SPEAKERS FOUND · CHECK WI-FI & ROUTER ISOLATION"
        else -> "$speakers SPEAKERS FOUND · $usable CAN TAKE AUDIO · TAP A TILE FOR DETAILS"
    }

    Box(Modifier.fillMaxSize().background(SB.Ink)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        ) {
            item(key = "header") { HeaderGrid(scanning = state.scanning, onScan = vm::scan) }
            item(key = "floor") {
                SpeakerFloor(
                    devices = visible,
                    scanning = state.scanning,
                    revision = state.revision,
                    sheetOpen = selected != null,
                    scroll = { floorScroll },
                    ticker = ticker,
                    leftCoord = "NET $subnet",
                    rightCoord = if (state.scanning) "T+ SCAN" else "${state.devices.size} NODES",
                    onTileClick = { selected = it },
                )
                Hairline()
            }
            item(key = "hero") {
                Hero(state, speakers, usable)
                Hairline()
            }
            val playable = visible.filter { it.status != "not_speaker" && TestSound.canPlay(it) }
            if (playable.isNotEmpty()) {
                item(key = "together") {
                    PlayTogether(state, stream, playable, vm::toggleTogether, vm::playTogether,
                        onStream = startStream, onStopStream = { PhoneStream.stop(context) })
                    Hairline()
                }
            }
            for (status in SECTION_ORDER) {
                val group = visible.filter { it.status == status }
                if (group.isEmpty()) continue
                item(key = "h-$status") {
                    SectionLabel(SECTION_TITLE.getValue(status), group.size, Modifier.animateItem())
                }
                itemsIndexed(group, key = { _, d -> "${d.key}@${d.ip}" }) { i, d ->
                    DeviceRow(
                        d, state.revision,
                        Modifier.animateItem().reveal(delayMillis = (i * 70).coerceAtMost(420), rise = 10.dp),
                    ) { selected = d }
                }
            }
            item(key = "footer") {
                ToggleRow("Show non-speakers", state.showAll) { vm.setShowAll(!state.showAll) }
                Hairline()
                Footer()
            }
        }
    }

    selected?.let { d ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SB.Panel,
            contentColor = SB.Cream,
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
            dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 3.dp).background(SB.Line)) },
        ) {
            DeviceSheet(
                d = d,
                revision = state.revision,
                statusLabel = vm.compat.statusLabels[d.status].orEmpty(),
                onToggleVerified = { vm.toggleVerified(d) },
                testMessage = state.testMessages[d.key],
                testing = d.key in state.testing,
                onPlayTest = { vm.playTestSound(d) },
            )
        }
    }
}

/* ------------------------------------------------------------------ header */

@Composable
private fun HeaderGrid(scanning: Boolean, onScan: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, tween(110), label = "press")
    val t = rememberInfiniteTransition(label = "button")
    val breath by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Column {
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            Cell(Modifier.width(60.dp).fillMaxHeight().reveal(0, 6.dp)) { SpeakerGlyph(30.dp) }
            Cell(Modifier.weight(1f).fillMaxHeight().reveal(90, 6.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SBType.WordSans.toSpanStyle()) { append("Speaker") }
                        append(" ")
                        withStyle(SBType.WordSerif.toSpanStyle()) { append("Bridge") }
                    },
                    maxLines = 1,
                )
            }
            Box(
                Modifier.width(112.dp).fillMaxHeight().reveal(180, 6.dp).border(0.5.dp, SB.Line).padding(7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { scaleX = press; scaleY = press }
                        // While scanning the button breathes between two reds.
                        .drawBehind {
                            val c = if (scanning) lerp(SB.RedDeep, SB.Red, breath) else SB.Red
                            drawRoundRect(c, cornerRadius = CornerRadius(4.dp.toPx()))
                        }
                        .clickable(interactionSource = src, indication = null, enabled = !scanning, onClick = onScan),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (scanning) "Scanning…" else "Scan Again", style = SBType.Button)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(34.dp)) {
            Cell(Modifier.weight(1f).fillMaxHeight().reveal(260, 6.dp)) {
                Text("Cast + AirPlay + DLNA", style = SBType.Tagline, maxLines = 1)
            }
            Cell(Modifier.weight(1f).fillMaxHeight().reveal(340, 6.dp)) {
                Text("Every Brand, One Sound", style = SBType.Tagline, maxLines = 1)
            }
        }
        ScanLine(active = scanning)
    }
}

@Composable
private fun Cell(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.border(0.5.dp, SB.Line).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        content()
    }
}

/* -------------------------------------------------------------------- hero */

@Composable
private fun Hero(state: UiState, speakers: Int, usable: Int) {
    val headline = when {
        state.scanning && state.devices.isEmpty() -> "LISTENING FOR SPEAKERS."
        !state.onWifi -> "GET ON WI-FI FIRST."
        state.hasScanned && speakers == 0 -> "NOTHING HEARD. YET."
        speakers == 1 -> "WE FOUND ONE SPEAKER."
        speakers > 1 -> "WE FOUND $speakers SPEAKERS."
        else -> "EVERY SPEAKER. ONE SOUND."
    }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The headline rolls up out of the way when the message changes.
        AnimatedContent(
            targetState = headline,
            transitionSpec = {
                (slideInVertically(tween(480, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(480))) togetherWith
                    (slideOutVertically(tween(300)) { -it / 2 } + fadeOut(tween(220)))
            },
            label = "headline",
            modifier = Modifier.reveal(0, 18.dp),
        ) { h -> Text(h, style = SBType.Display) }

        SweepText(
            text = when {
                !state.onWifi -> "Your phone isn't on Wi-Fi. Join the same network as your speakers, then scan again."
                state.error != null -> "The scan hit a problem: ${state.error}"
                state.hasScanned && speakers == 0 ->
                    "Make sure your phone and speakers share the same Wi-Fi (not a guest network) and that " +
                        "your router doesn't isolate devices from each other."
                else ->
                    "SpeakerBridge listens on your Wi-Fi for Sony, Vizio, Google and every other speaker that " +
                        "speaks Cast, AirPlay or DLNA, and tells you honestly which ones it can play to."
            },
            style = SBType.Body,
            modifier = Modifier.reveal(140),
        )
        if (speakers > 0) {
            Text(
                "$usable of them can take SpeakerBridge audio or already play in sync as a group. " +
                    "Tap any tile or row for the details.",
                style = SBType.Body.copy(color = SB.Muted),
                modifier = Modifier.reveal(260),
            )
        }
        Column(Modifier.reveal(380)) {
            Text("STATUS", style = SBType.MonoLabel, modifier = Modifier.padding(bottom = 6.dp))
            for (s in SECTION_ORDER.dropLast(1)) {
                val n = state.devices.count { it.status == s }
                // The counters tick up to their value.
                val shown by animateIntAsState(n, tween(800, easing = FastOutSlowInEasing), label = "count-$s")
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(SECTION_TITLE.getValue(s), style = SBType.BodySmall.copy(color = if (n > 0) SB.Cream else SB.Dim),
                        modifier = Modifier.weight(1f))
                    Text(shown.toString().padStart(2, '0'), style = SBType.Mono.copy(color = if (n > 0) statusColor(s) else SB.Dim))
                }
                Hairline()
            }
        }
    }
}

/* --------------------------------------------------------- play together */

@Composable
private fun PlayTogether(
    state: UiState, stream: StreamState, playable: List<Device>, onToggle: (Device) -> Unit, onPlay: () -> Unit,
    onStream: () -> Unit, onStopStream: () -> Unit,
) {
    val picked = playable.count { it.key in state.together }
    val busy = state.togetherBusy || stream.running
    Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PLAY TOGETHER", style = SBType.MonoLabel)
        Text("Pick speakers, then stream whatever your phone is playing to all of them, or play the test " +
            "chime on two or more at the same moment.",
            style = SBType.BodySmall)
        for (d in playable) {
            val on = d.key in state.together
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy) { onToggle(d) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(16.dp).border(1.dp, if (on) SB.Red else SB.Line, RoundedCornerShape(3.dp))
                        .padding(3.dp).background(if (on) SB.Red else Color.Transparent, RoundedCornerShape(1.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Text(d.name.ifEmpty { "(unnamed)" }, style = SBType.Body, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (d.isCastGroup) "GROUP" else d.protocols.keys.joinToString(" · ") { shortProto(it) }.uppercase(),
                    style = SBType.Mono.copy(color = SB.Dim), maxLines = 1)
            }
        }
        when {
            !PhoneStream.supported -> PillButton("Streaming needs Android 10", enabled = false) {}
            stream.running -> PillButton("Stop streaming", enabled = true, onClick = onStopStream)
            picked == 0 -> PillButton("Pick a speaker to stream", enabled = false) {}
            else -> PillButton("Stream phone audio ($picked)", enabled = !state.togetherBusy, onClick = onStream)
        }
        if (stream.running) when (stream.hearing) {
            true -> Text("Sending your phone's sound.", style = SBType.BodySmall.copy(color = SB.Cream))
            false -> Text(
                "Only silence is reaching the speakers. Is something playing? Some apps (many video and " +
                    "music apps) don't allow their sound to be shared. If this appeared just as you turned the " +
                    "phone's volume down, your phone lowers what it shares too: turn it back up a little.",
                style = SBType.BodySmall.copy(color = SB.Red),
            )
            null -> {}
        }
        stream.phone?.let { Text(it, style = SBType.BodySmall.copy(color = SB.Cream)) }
        stream.lines.forEach { Text(it, style = SBType.BodySmall.copy(color = SB.Cream)) }
        PillButton(
            when {
                state.togetherBusy -> "Playing…"
                picked < 2 -> "Chime needs two"
                else -> "Play chime together ($picked)"
            },
            enabled = picked >= 2 && !busy,
            onClick = onPlay,
        )
        state.togetherLines.forEach { Text(it, style = SBType.BodySmall.copy(color = SB.Cream)) }
        Text(
            "Streaming sends your phone's sound, never its picture, and only over your Wi-Fi. Android asks to " +
                "share your screen and to record audio because that's how it lets an app capture other apps' " +
                "sound; the microphone isn't used. Some apps block capture (many video apps do).",
            style = SBType.BodySmall.copy(color = SB.Dim),
        )
        Text(
            "Android copies the sound rather than moving it, so SpeakerBridge mutes your phone once music " +
                "starts (and unmutes it when you stop), so it doesn't play ahead of the speakers. If your phone " +
                "can't stay muted while sharing, the app says so.",
            style = SBType.BodySmall.copy(color = SB.Dim),
        )
        Text(
            "Speakers of different brands can still land a fraction of a second apart. For Google Cast " +
                "speakers, a speaker group made in the Google Home app keeps them perfectly in sync; it then " +
                "shows up here as one speaker.",
            style = SBType.BodySmall.copy(color = SB.Dim),
        )
    }
}

@Composable
private fun PillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (enabled) SB.Red else Color.Transparent, RoundedCornerShape(4.dp))
            .border(1.dp, if (enabled) SB.Red else SB.Line, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(text, style = SBType.Button)
    }
}

/* -------------------------------------------------------------------- list */

@Composable
private fun SectionLabel(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 6.dp)) {
        Text(title.uppercase(), style = SBType.MonoLabel, modifier = Modifier.weight(1f))
        Text(count.toString().padStart(2, '0'), style = SBType.Mono)
    }
}

@Composable
private fun DeviceRow(
    d: Device,
    @Suppress("UNUSED_PARAMETER") revision: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Press feedback: the name lights up, a green line draws under the row, the arrow nudges.
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val nameColor by animateColorAsState(if (pressed) SB.Verified else SB.Cream, tween(140), label = "name")
    val bg by animateColorAsState(if (pressed) SB.Panel else Color.Transparent, tween(140), label = "bg")
    val line by animateFloatAsState(
        if (pressed) 1f else 0f,
        tween(if (pressed) 380 else 160, easing = FastOutSlowInEasing),
        label = "line",
    )
    val nudge by animateFloatAsState(if (pressed) 1f else 0f, tween(180), label = "nudge")

    Column(
        modifier.fillMaxWidth().background(bg)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    d.name.ifEmpty { "(unnamed)" },
                    style = SBType.Row.copy(color = nameColor),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(d.manufacturer.ifEmpty { "Unknown brand" }, d.model).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = SBType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(STATUS_SHORT[d.status] ?: d.status, style = SBType.Mono.copy(color = statusColor(d.status)))
                Text(d.protocols.keys.joinToString(" · ") { shortProto(it) }.uppercase().ifEmpty { "—" },
                    style = SBType.Mono.copy(color = SB.Dim), maxLines = 1)
            }
            Text(
                "  ↗",
                style = SBType.Mono.copy(color = SB.Muted),
                modifier = Modifier.graphicsLayer {
                    translationX = nudge * 6f
                    translationY = -nudge * 6f
                },
            )
        }
        Box(Modifier.padding(horizontal = 18.dp)) {
            Hairline()
            Box(Modifier.fillMaxWidth(line).height(1.dp).background(SB.Verified))
        }
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    val t by animateFloatAsState(if (on) 1f else 0f, tween(220, easing = FastOutSlowInEasing), label = "toggle")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = SBType.Mono.copy(color = SB.Cream), modifier = Modifier.weight(1f))
        Box(
            Modifier.size(38.dp, 20.dp).border(1.dp, lerp(SB.Line, SB.Red, t), RoundedCornerShape(10.dp)).padding(3.dp),
        ) {
            // The knob slides across and warms from grey to red.
            Box(Modifier.offset(x = 18.dp * t).size(14.dp).background(lerp(SB.Dim, SB.Red, t), RoundedCornerShape(7.dp)))
        }
    }
}

/* ------------------------------------------------------------------ footer */

@Composable
private fun Footer() {
    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ONE HOUSE.\nONE SOUND.", style = SBType.Headline)
            Text(
                "Found isn't the same as playable. A speaker is only marked Verified after you've heard " +
                    "SpeakerBridge audio come out of it.",
                style = SBType.BodySmall,
            )
            Text("SPEAKERBRIDGE V0.1 · LOCAL ONLY · NO CLOUD", style = SBType.Mono.copy(color = SB.Dim))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 12.dp)) {
            SpeakerGlyph(26.dp)
            Text("FOR EVERY\nBRAND READY\nTO SYNC", style = SBType.Mono.copy(color = SB.Cream, fontSize = 8.sp),
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/* ------------------------------------------------------------- detail sheet */

@Composable
private fun DeviceSheet(
    d: Device,
    @Suppress("UNUSED_PARAMETER") revision: Int,
    statusLabel: String,
    onToggleVerified: () -> Unit,
    testMessage: String?,
    testing: Boolean,
    onPlayTest: () -> Unit,
) {
    // Each block rises in just after the one above it, like the panels in the reference.
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            (d.name.ifEmpty { "Unnamed speaker" }).uppercase(),
            style = SBType.Display.copy(fontSize = 40.sp, lineHeight = 38.sp),
            modifier = Modifier.reveal(0, 18.dp),
        )
        Text(statusLabel.uppercase(), style = SBType.Mono.copy(color = statusColor(d.status)), modifier = Modifier.reveal(70))
        Field("Brand", d.manufacturer.ifEmpty { "Unknown" }, Modifier.fillMaxWidth().reveal(120))
        Field("Model", d.model.ifEmpty { "Unknown" }, Modifier.fillMaxWidth().reveal(170))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Address", d.ip, Modifier.weight(1f).reveal(220))
            Field("Sync tier", "Tier ${d.syncTier}", Modifier.weight(1f).reveal(270))
        }
        Field("Protocols", d.protocols.entries.joinToString(", ") { (k, hit) ->
            if (k == "airplay" && hit.details["airplay2"] == "true") "AirPlay 2" else shortProto(k)
        }.ifEmpty { "None advertised" }, Modifier.fillMaxWidth().reveal(320))
        if (d.notes.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().reveal(370).border(1.dp, SB.Line, RoundedCornerShape(3.dp)).padding(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NOTES", style = SBType.MonoLabel)
                    d.notes.forEach { Text(it, style = SBType.BodySmall.copy(color = SB.Cream)) }
                }
            }
        }
        Field("Device key", d.key, Modifier.fillMaxWidth().reveal(420), mono = true)
        if (d.status != "not_speaker" && TestSound.canPlay(d)) {
            Spacer(Modifier.height(4.dp))
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val press by animateFloatAsState(if (pressed) 0.95f else 1f, tween(110), label = "test-press")
            Box(
                Modifier
                    .reveal(445)
                    .graphicsLayer { scaleX = press; scaleY = press }
                    .border(1.dp, if (testing) SB.Line else SB.Cream, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = src, indication = null, enabled = !testing, onClick = onPlayTest)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(if (testing) "Playing…" else "Play Test Sound", style = SBType.Button)
            }
            if (testMessage != null) {
                Text(testMessage, style = SBType.BodySmall.copy(color = SB.Cream))
            }
        }
        if (d.status != "not_speaker") {
            Spacer(Modifier.height(4.dp))
            val verified = d.status == "verified"
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val press by animateFloatAsState(if (pressed) 0.95f else 1f, tween(110), label = "verify-press")
            Box(
                Modifier
                    .reveal(470)
                    .graphicsLayer { scaleX = press; scaleY = press }
                    .background(if (verified) Color.Transparent else SB.Red, RoundedCornerShape(4.dp))
                    .border(1.dp, if (verified) SB.Line else SB.Red, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = src, indication = null, onClick = onToggleVerified)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(if (verified) "Remove Verified Mark" else "I Heard It Play!", style = SBType.Button)
            }
            Text(
                if (TestSound.canPlay(d)) "Tap Play Test Sound first. Only mark it Verified once you've heard the chime on this speaker."
                else "This speaker has no Cast or DLNA, so SpeakerBridge can't send it a test sound yet.",
                style = SBType.BodySmall.copy(color = SB.Dim),
                modifier = Modifier.reveal(520),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), mono: Boolean = false) {
    Column(modifier.border(1.dp, SB.Line, RoundedCornerShape(3.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label.uppercase(), style = SBType.Mono.copy(color = SB.Dim, fontSize = 8.sp))
        Text(value, style = if (mono) SBType.Mono.copy(color = SB.Cream) else SBType.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
