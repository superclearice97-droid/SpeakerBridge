package com.speakerbridge.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speakerbridge.compat.Compat
import com.speakerbridge.data.SyncStore
import com.speakerbridge.data.VerifiedStore
import com.speakerbridge.discovery.Scanner
import com.speakerbridge.model.Device
import com.speakerbridge.playback.ClickRun
import com.speakerbridge.playback.Clicks
import com.speakerbridge.playback.Diagnostics
import com.speakerbridge.playback.MicCalibration
import com.speakerbridge.playback.MicError
import com.speakerbridge.playback.MicRecorder
import com.speakerbridge.playback.Pcm
import com.speakerbridge.playback.PhoneStream
import com.speakerbridge.playback.Route
import com.speakerbridge.playback.TestSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.NetworkInterface

data class UiState(
    val scanning: Boolean = false,
    val hasScanned: Boolean = false,
    val devices: List<Device> = emptyList(),
    val showAll: Boolean = false,
    val onWifi: Boolean = true,
    val error: String? = null,
    val revision: Int = 0,   // bumps when a device changes in place (verified toggle)
    val testMessages: Map<String, String> = emptyMap(),   // device key -> latest test-sound status
    val testing: Set<String> = emptySet(),                // device keys with a test sound in progress
    val together: Set<String> = emptySet(),               // device keys picked for Play Together
    val togetherBusy: Boolean = false,
    val togetherLines: List<String> = emptyList(),        // one status line per picked speaker
    val delays: Map<String, Int> = emptyMap(),            // device key -> sync delay (ms)
    val routes: Map<String, String> = emptyMap(),         // device key -> route picked by hand
    val lab: String = "",                                 // "" idle, "clicks" or "mic"
    val labLines: List<String> = emptyList(),             // what the sync clicks / microphone line-up is doing
) {
    val visible: List<Device> get() = devices.filter { showAll || it.status != "not_speaker" }
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    val compat: Compat = Compat.fromAssets(app)
    private val scanner = Scanner(compat)
    private val verified = VerifiedStore(app)
    private val sync = SyncStore(app)
    @Volatile private var clickRun: ClickRun? = null
    @Volatile private var labStop = false
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        Diagnostics.header = runCatching {
            val v = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            "App $v · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        }.getOrDefault("")
        scan()
    }

    fun setShowAll(v: Boolean) = _state.update { it.copy(showAll = v) }

    fun scan() {
        if (_state.value.scanning) return
        val ctx = getApplication<Application>()
        val (onWifi, iface) = wifiInterface(ctx)
        _state.update { it.copy(scanning = true, error = null, onWifi = onWifi) }
        viewModelScope.launch {
            // Some Wi-Fi drivers drop multicast unless an app holds this lock.
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("speakerbridge-scan").apply { setReferenceCounted(false) }
            try {
                lock.acquire()
                val devices = scanner.scan(verified.keys(), 4000, iface)
                _state.update {
                    it.copy(scanning = false, hasScanned = true, devices = devices,
                        delays = devices.associate { d -> d.key to sync.delayMs(d.key) }.filterValues { ms -> ms != 0 },
                        routes = devices.mapNotNull { d -> sync.forcedRoute(d.key)?.let { r -> d.key to r } }.toMap())
                }
            } catch (e: Exception) {
                _state.update { it.copy(scanning = false, hasScanned = true, error = e.message ?: e.toString()) }
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }

    fun toggleVerified(d: Device) {
        if (d.status == "verified") verified.unmark(d.key)
        else verified.mark(d.key, d.name, d.protocols.keys.joinToString(","))
        compat.classify(d, verified.keys())
        _state.update { it.copy(revision = it.revision + 1) }
    }

    fun playTestSound(d: Device) {
        val key = d.key
        if (key in _state.value.testing) return
        setTest(key, "Connecting…", busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { TestSound.play(d) { step -> setTest(key, step, busy = true) } }
                .getOrElse { failure(it) }
            setTest(key, msg, busy = false)
        }
    }

    fun toggleTogether(d: Device) = _state.update {
        it.copy(together = if (d.key in it.together) it.together - d.key else it.together + d.key)
    }

    /** Plays the chime on every picked speaker, started at the same moment. */
    fun playTogether() {
        val s = _state.value
        val picked = picked()
        if (s.togetherBusy || picked.size < 2) return
        val lines = picked.associate { it.key to "${it.name}: connecting…" }.toMutableMap()
        fun show(busy: Boolean) = _state.update { st ->
            st.copy(togetherBusy = busy, togetherLines = synchronized(lines) { picked.map { lines.getValue(it.key) } })
        }
        show(true)
        viewModelScope.launch(Dispatchers.IO) {
            val results = runCatching {
                TestSound.playMany(picked) { d, step ->
                    synchronized(lines) { lines[d.key] = "${d.name}: $step" }
                    show(true)
                }
            }
            synchronized(lines) {
                for (d in picked) {
                    val r: Result<String> = results.fold({ it.getValue(d) }, { e -> Result.failure(e) })
                    lines[d.key] = "${d.name}: " + r.fold({ "played" }, { failure(it) })
                }
            }
            show(false)
        }
    }

    private fun picked(): List<Device> = _state.value.let { s -> s.visible.filter { it.key in s.together && TestSound.canPlay(it) } }

    /* ------------------------------------------------------------------ sync */

    /** Changes a speaker's sync delay; saved, and applied straight away to whatever is playing. */
    fun changeDelay(d: Device, byMs: Int) {
        val ms = ((_state.value.delays[d.key] ?: 0) + byMs).coerceIn(0, Pcm.MAX_DELAY_MS)
        sync.setDelay(d.key, ms)
        PhoneStream.setDelay(d.key, ms)
        clickRun?.session?.setDelay(d.key, ms)
        _state.update { it.copy(delays = if (ms == 0) it.delays - d.key else it.delays + (d.key to ms)) }
    }

    /** Steps a speaker through "work it out" and each way it could be reached. Applies from the next start. */
    fun cycleRoute(d: Device) {
        val options = listOf<String?>(null) + Route.possible(d).map { it.id }
        val next = options[(options.indexOf(_state.value.routes[d.key]) + 1) % options.size]
        sync.setForcedRoute(d.key, next)
        _state.update { it.copy(routes = if (next == null) it.routes - d.key else it.routes + (d.key to next)) }
    }

    private fun labLine(i: Int, line: String) = _state.update {
        it.copy(labLines = it.labLines.toMutableList().also { l -> if (i in l.indices) l[i] = line })
    }

    /** Starts or stops a click every second on the picked speakers, for lining them up by ear. */
    fun toggleClicks() {
        if (_state.value.lab == "clicks") { labStop = true; return }
        val devices = picked()
        if (_state.value.lab.isNotEmpty() || devices.isEmpty()) return
        labStop = false
        _state.update { it.copy(lab = "clicks", labLines = devices.map { d -> "${d.name}: starting…" }) }
        viewModelScope.launch(Dispatchers.IO) {
            var run: ClickRun? = null
            try {
                run = ClickRun(devices, sync, Clicks.EVERY_SECOND, ::labLine).also { clickRun = it; it.start() }
                while (!labStop && !run.ended) delay(100)
            } catch (e: Exception) {
                _state.update { it.copy(labLines = listOf("Couldn't play the clicks: ${e.message ?: e.javaClass.simpleName}")) }
            } finally {
                clickRun = null
                runCatching { run?.close() }
                _state.update { it.copy(lab = "") }
            }
        }
    }

    /** Lines the picked speakers up with the microphone. Call only once the user has agreed. */
    fun lineUpWithMicrophone() {
        val devices = picked()
        if (_state.value.lab.isNotEmpty() || devices.size < 2) return
        labStop = false
        _state.update { it.copy(lab = "mic", labLines = listOf("Getting ready…")) }
        fun say(line: String) = _state.update { it.copy(labLines = (it.labLines + line).takeLast(6)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (delays, problems) = MicCalibration.calibrate(devices, sync, { MicRecorder() }, { labStop }, ::say)
                val result = mutableListOf<String>()
                if (delays.size < 2) {
                    result += "Couldn't measure enough speakers to line them up. Delays not changed."
                } else {
                    for ((key, ms) in delays) { sync.setDelay(key, ms); PhoneStream.setDelay(key, ms) }
                    _state.update { it.copy(delays = (it.delays + delays).filterValues { ms -> ms != 0 }) }
                    result += "Lined up and saved: " + devices.filter { it.key in delays }
                        .joinToString { "${it.name} ${delays.getValue(it.key)} ms" } + "."
                }
                problems.forEach { result += "Not measured: $it" }
                _state.update { it.copy(labLines = result) }
            } catch (e: MicError) {
                _state.update { it.copy(labLines = listOf(if (e.message == "stopped") "Stopped. Delays not changed."
                    else "Couldn't line the speakers up: ${e.message}.")) }
            } catch (e: Exception) {
                _state.update { it.copy(labLines = listOf("Couldn't line the speakers up: ${e.message ?: e.javaClass.simpleName}.")) }
            } finally {
                _state.update { it.copy(lab = "") }
            }
        }
    }

    fun stopLab() { labStop = true }

    /** What recent streams and line-ups did, for working out why a speaker didn't play. */
    fun report(): String = listOfNotNull(Diagnostics.text(),
        (PhoneStream.session ?: clickRun?.session)?.let { "Still running:\n" + it.report() }).joinToString("\n\n")

    override fun onCleared() { labStop = true }

    private fun failure(e: Throwable): String =
        if (e is java.net.ConnectException) "Couldn't reach the speaker. Check it's on and on the same Wi-Fi, then scan again."
        else "Couldn't play the test sound: ${e.message ?: e.javaClass.simpleName}"

    private fun setTest(key: String, msg: String, busy: Boolean) = _state.update {
        it.copy(testMessages = it.testMessages + (key to msg), testing = if (busy) it.testing + key else it.testing - key)
    }

    private fun wifiInterface(ctx: Context): Pair<Boolean, NetworkInterface?> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false to null
        val net = cm.activeNetwork ?: return false to null
        val caps = cm.getNetworkCapabilities(net)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val name = cm.getLinkProperties(net)?.interfaceName
        val iface = name?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
        return onWifi to iface
    }
}
