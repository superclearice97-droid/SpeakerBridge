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
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.speakerbridge.MainActivity
import com.speakerbridge.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class StreamState(
    val running: Boolean = false,
    val lines: List<String> = emptyList(),   // one status line per speaker, or a single error
    val hearing: Boolean? = null,            // is any sound being captured? null until it's known
    val phone: String? = null,               // whether the phone itself has been quietened
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

    internal fun hearing(sound: Boolean) = _state.update { if (it.running) it.copy(hearing = sound) else it }

    internal fun phoneNote(msg: String) = _state.update { if (it.running) it.copy(phone = msg) else it }

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
        val audio = getSystemService(AudioManager::class.java)
        val phone = PhoneQuiet(
            mute = {
                when {
                    audio.isVolumeFixed -> PhoneQuiet.Mute.NOT_ALLOWED
                    audio.isStreamMute(AudioManager.STREAM_MUSIC) -> PhoneQuiet.Mute.ALREADY
                    else -> {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                        PhoneQuiet.Mute.DONE
                    }
                }
            },
            unmute = { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) },
            say = PhoneStream::phoneNote,
        )
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
                val quiet = SilenceWatch()
                while (!stopped) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) {
                        s.push(buf.copyOf(n))
                        quiet.feed(buf, n)?.let(PhoneStream::hearing)
                        phone.feed(isLoud(buf, n), SystemClock.elapsedRealtime())
                    } else if (n < 0) break
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
            runCatching { phone.release() }   // the phone plays out loud again
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

/**
 * Tells whether captured audio is real sound or silence, so the app can say when only silence
 * is reaching the speakers (nothing playing, or an app that doesn't allow its sound to be
 * shared). feed() returns the new answer when it changes, else null.
 */
internal class SilenceWatch(private val rate: Int = LiveStreamServer.RATE, private val channels: Int = LiveStreamServer.CHANNELS) {
    private var silentBytes = 0L
    private var answer: Boolean? = null

    fun feed(pcm: ByteArray, n: Int): Boolean? {
        val loud = isLoud(pcm, n)
        silentBytes = if (loud) 0 else silentBytes + n
        val now = when {
            loud -> true
            silentBytes >= QUIET_SECONDS * rate * channels * 2 -> false
            else -> answer   // a short pause between songs doesn't count
        }
        return now.takeIf { it != answer }.also { if (now != null) answer = now }
    }

    private companion object {
        const val QUIET_SECONDS = 3
    }
}

/** Whether 16-bit little-endian PCM has any real sound in it (not just silence or dither). */
internal fun isLoud(pcm: ByteArray, n: Int): Boolean {
    var i = 0
    while (i + 1 < n) {
        val v = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
        if (v > 64 || v < -64) return true   // of 32767: well below anything audible, above dither
        i += 2
    }
    return false
}

/**
 * Keeps the phone itself quiet while it streams. Android copies the sound rather than moving
 * it, so the phone would otherwise go on playing, a second or two ahead of the speakers. Once
 * sound is flowing, the phone's media volume is muted. On some phones muting also silences what
 * is shared, so the moments after muting are checked: if the speakers would only get silence,
 * the phone is unmuted straight away and the user is told. release() unmutes when streaming ends.
 */
internal class PhoneQuiet(
    private val mute: () -> Mute,
    private val unmute: () -> Unit,
    private val say: (String) -> Unit,
) {
    enum class Mute { DONE, ALREADY, NOT_ALLOWED }
    private enum class Step { WAITING, CHECKING, MUTED, LEFT_ALONE }

    private var step = Step.WAITING
    private var soundSince = -1L
    private var mutedAt = 0L
    private var lastLoud = -1L

    /** One captured chunk: whether it had sound, and when (ms, any steady clock). */
    @Synchronized fun feed(loud: Boolean, nowMs: Long) {
        if (loud) lastLoud = nowMs
        when (step) {
            Step.WAITING -> {   // wait for music, so there's something to check with
                if (!loud) { soundSince = -1; return }
                if (soundSince < 0) soundSince = nowMs
                if (nowMs - soundSince < SOUND_FIRST_MS) return
                when (mute()) {
                    Mute.DONE -> { step = Step.CHECKING; mutedAt = nowMs }
                    Mute.ALREADY -> step = Step.LEFT_ALONE   // the user muted it already: theirs to undo
                    Mute.NOT_ALLOWED -> {
                        step = Step.LEFT_ALONE
                        say("This phone doesn't let apps change its volume, so it keeps playing too. Turn it down yourself.")
                    }
                }
            }
            Step.CHECKING -> {
                if (nowMs - mutedAt < CHECK_MS) return
                // Audio captured just before muting can still be arriving for a few hundred ms,
                // so only sound well after the mute proves the speakers still get it.
                if (lastLoud >= mutedAt + PROOF_AFTER_MS) {
                    step = Step.MUTED
                    say("Your phone is muted while it streams, so only the speakers play. It unmutes when you stop.")
                } else {
                    unmute()
                    step = Step.LEFT_ALONE
                    say("This phone also silences what it shares when it's muted, so it can't stay quiet while " +
                        "streaming. Turn its volume down as far as you can while the speakers still play.")
                }
            }
            else -> {}
        }
    }

    @Synchronized fun release() {
        if (step == Step.CHECKING || step == Step.MUTED) unmute()
        step = Step.LEFT_ALONE
    }

    private companion object {
        const val SOUND_FIRST_MS = 300L
        const val CHECK_MS = 1500L
        const val PROOF_AFTER_MS = 700L
    }
}
