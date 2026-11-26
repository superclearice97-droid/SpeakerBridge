package com.speakerbridge.playback

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Records the phone's microphone (mono, 48 kHz) in memory while it is open, for timing the
 * speakers' clicks. Only created after the user agrees; nothing is ever saved or sent, and
 * the buffer is wiped by clear() and close().
 */
@SuppressLint("MissingPermission")   // the caller asks for the recording permission first
class MicRecorder : RoomRecorder {
    private val record: AudioRecord
    private val lock = Any()
    private var samples = ShortArray(MicTiming.RATE * 20)
    private var count = 0
    private var t0 = Double.POSITIVE_INFINITY   // time of sample 0 (the least-delayed estimate)
    @Volatile private var closed = false

    init {
        val min = AudioRecord.getMinBufferSize(MicTiming.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_RECOGNITION is the microphone with the least processing (no noise suppression or gain riding).
        record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MicTiming.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, MicTiming.RATE))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicError("the microphone couldn't be opened (is another app using it?)")
        }
        record.startRecording()
        Thread {
            val buf = ShortArray(MicTiming.RATE / 50)   // 20 ms
            while (!closed) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                val now = System.nanoTime() / 1e9
                synchronized(lock) {
                    if (count + n > samples.size) samples = samples.copyOf(samples.size * 2)
                    System.arraycopy(buf, 0, samples, count, n)
                    count += n
                    t0 = minOf(t0, now - count.toDouble() / MicTiming.RATE)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun between(fromS: Double, toS: Double): Pair<ShortArray, Double> = synchronized(lock) {
        if (count == 0) throw MicError("the microphone isn't recording (check SpeakerBridge is allowed to use it)")
        val i = ((fromS - t0) * MicTiming.RATE).toInt().coerceIn(0, count)
        val j = ((toS - t0) * MicTiming.RATE).toInt().coerceIn(i, count)
        samples.copyOfRange(i, j) to t0 + i.toDouble() / MicTiming.RATE
    }

    override fun clear() = synchronized(lock) {
        samples.fill(0, 0, count)
        count = 0
        t0 = Double.POSITIVE_INFINITY   // re-estimated from the next reads
    }

    override fun close() {
        closed = true
        runCatching { record.stop() }
        record.release()
        clear()
    }

    companion object {
        const val CONSENT = "SpeakerBridge will use this phone's microphone to time the speakers: each one plays " +
            "clicks for about 15 seconds while it listens. The recording stays in the phone's memory, is cleared " +
            "as soon as each speaker is measured, and is never saved or sent anywhere.\n\nBefore starting: hold " +
            "or put the phone where you listen, set the speakers to a normal volume and keep the room quiet. " +
            "Keep this screen open until it finishes."
    }
}
