package com.speakerbridge.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speakerbridge.compat.Compat
import com.speakerbridge.data.VerifiedStore
import com.speakerbridge.discovery.Scanner
import com.speakerbridge.model.Device
import com.speakerbridge.playback.TestSound
import kotlinx.coroutines.Dispatchers
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
) {
    val visible: List<Device> get() = devices.filter { showAll || it.status != "not_speaker" }
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    val compat: Compat = Compat.fromAssets(app)
    private val scanner = Scanner(compat)
    private val verified = VerifiedStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { scan() }

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
                _state.update { it.copy(scanning = false, hasScanned = true, devices = devices) }
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
            val msg = try {
                TestSound.play(d) { step -> setTest(key, step, busy = true) }
            } catch (e: java.net.ConnectException) {
                "Couldn't reach the speaker. Check it's on and on the same Wi-Fi, then scan again."
            } catch (e: Exception) {
                "Couldn't play the test sound: ${e.message ?: e.javaClass.simpleName}"
            }
            setTest(key, msg, busy = false)
        }
    }

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
