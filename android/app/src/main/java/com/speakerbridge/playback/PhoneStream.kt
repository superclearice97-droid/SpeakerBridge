package com.speakerbridge.playback

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import com.speakerbridge.MainActivity
import com.speakerbridge.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class StreamState(
    val running: Boolean = false,
    val lines: List<String> = emptyList(),   // one status line per speaker, or a single error
)

/**
 * Streams whatever the phone is playing to one or more speakers, over Google Cast or DLNA.
 * Android 10+ only: it uses Android's audio playback capture, which needs the user's consent
 * every time and leaves out apps that don't allow capture.
 */
object PhoneStream {
    const val TITLE = "This phone (SpeakerBridge)"
    internal const val ACTION_STOP = "com.speakerbridge.STOP_STREAM"

    private val _state = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = _state

    /** The consent result and speakers, handed to the service as it starts. */
    internal var pending: Triple<Int, Intent, List<Device>>? = null

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** [resultCode] and [consent] come from the screen-capture consent prompt. */
    fun start(ctx: Context, resultCode: Int, consent: Intent, devices: List<Device>) {
        if (_state.value.running || devices.isEmpty()) return
        pending = Triple(resultCode, consent, devices)
        _state.value = StreamState(true, devices.map { "${it.name}: starting…" })
        runCatching { ctx.startForegroundService(Intent(ctx, PhoneStreamService::class.java)) }
            .onFailure { ended("Couldn't start streaming: ${it.message ?: it.javaClass.simpleName}") }
    }

    fun stop(ctx: Context) {
        if (_state.value.running) ctx.startService(Intent(ctx, PhoneStreamService::class.java).setAction(ACTION_STOP))
    }

    fun note(msg: String) { _state.value = StreamState(false, listOf(msg)) }

    internal fun setLine(i: Int, line: String) = _state.update {
        it.copy(lines = it.lines.toMutableList().also { l -> if (i in l.indices) l[i] = line })
    }

    internal fun ended(lastError: String? = null) = _state.update {
        StreamState(false, if (lastError != null) listOf(lastError) else it.lines)
    }
}

/** Foreground service that keeps the capture and the speaker connections alive while streaming. */
class PhoneStreamService : Service() {
    @Volatile private var stopped = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PhoneStream.ACTION_STOP) {
            stopped = true
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        val job = PhoneStream.pending
        PhoneStream.pending = null
        if (job == null || worker != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (job != null) PhoneStream.ended("Still stopping the last stream. Try again in a moment.")
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        val projection = runCatching {
            // The notification must be up before Android hands over the capture.
            startForeground(NOTIFICATION_ID, notification(job.third.size), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(job.first, job.second)
        }.getOrNull()
        if (projection == null) {
            PhoneStream.ended("Android didn't allow the audio capture. Try again.")
            finish()
            return START_NOT_STICKY
        }
        // Stopped from Android's own "stop sharing" control.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopped = true }
        }, Handler(mainLooper))
        worker = Thread { run(projection, job.third) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun run(projection: MediaProjection, devices: List<Device>) {
        val power = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "speakerbridge:stream")
        @Suppress("DEPRECATION")
        val wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "speakerbridge:stream")
        var record: AudioRecord? = null
        var server: LiveStreamServer? = null
        var error: String? = null
        try {
            power.acquire(12 * 60 * 60 * 1000L)
            wifi.acquire()
            server = LiveStreamServer(TestSound.localAddressFor(devices.first().ip))
            record = capture(projection)
            record.startRecording()
            val s = server
            val r = record
            val capture = Thread {
                val buf = ByteArray(CHUNK)
                while (!stopped) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) s.push(buf.copyOf(n)) else if (n < 0) break
                }
            }.also { it.start() }
            val players = devices.mapIndexed { i, d -> Thread { play(i, d, s.url) }.also { it.start() } }
            players.forEach { it.join() }   // ends on Stop, or once every speaker has failed
            stopped = true
            capture.join(1000)
        } catch (e: Exception) {
            error = "Couldn't stream: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            stopped = true
            runCatching { record?.stop() }
            record?.release()
            server?.close()
            projection.stop()
            if (wifi.isHeld) wifi.release()
            if (power.isHeld) power.release()
            PhoneStream.ended(error)
            Handler(mainLooper).post { finish() }
        }
    }

    /** Streams to one speaker until stopped; its status line says how it went. */
    private fun play(i: Int, d: Device, url: String) {
        val say = { msg: String -> PhoneStream.setLine(i, "${d.name}: $msg") }
        val cast = d.protocols["googlecast"]
        val result = runCatching {
            if (cast != null) {
                CastPlayer(d.ip, if (cast.port > 0) cast.port else 8009)
                    .stream(url, LiveStreamServer.CONTENT_TYPE, PhoneStream.TITLE, say) { stopped }
            } else {
                val location = TestSound.dlnaLocation(d)
                require(location.isNotEmpty()) { "this speaker only offers AirPlay, which SpeakerBridge can't send to yet" }
                try {
                    say("Starting the stream over DLNA…")
                    DlnaPlayer.play(location, url, LiveStreamServer.CONTENT_TYPE, PhoneStream.TITLE, StartGate(1).party())
                    say("Streaming over DLNA.")
                    while (!stopped) Thread.sleep(250)
                } finally {
                    runCatching { DlnaPlayer.stop(location) }
                }
            }
        }
        say(result.fold({ "stopped." }, { "couldn't stream: ${it.message ?: it.javaClass.simpleName}" }))
    }

    @SuppressLint("NewApi")   // only reached on Android 10+
    private fun capture(projection: MediaProjection): AudioRecord {
        // Android requires the microphone permission for this, but only app audio is recorded.
        check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "SpeakerBridge needs Android's audio recording permission to capture what your phone plays"
        }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(LiveStreamServer.RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(LiveStreamServer.RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 4, CHUNK * 10))
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    private fun notification(count: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PhoneStreamService::class.java).setAction(PhoneStream.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Streaming this phone's audio")
            .setContentText(if (count == 1) "To 1 speaker, on your Wi-Fi only" else "To $count speakers, on your Wi-Fi only")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private companion object {
        const val CHANNEL = "stream"
        const val NOTIFICATION_ID = 1
        const val CHUNK = LiveStreamServer.RATE * LiveStreamServer.CHANNELS * 2 / 50   // 20 ms
    }
}
