package org.sableos.games

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sound and vibration for Tartarus Breach. The sounds are short tones made in code when the game starts,
 * so there are no audio files and no permission is needed; vibration needs the VIBRATE permission, which
 * only the Tartarus app declares, and is skipped where it is missing.
 */
internal class TbFeedback(
    context: Context,
) {
    var sound = true
    var vibrate = true

    private val vibrator: Vibrator? =
        if (context.checkSelfPermission(android.Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } else {
            null
        }
    private val tracks = HashMap<TbEvent, AudioTrack>()
    private val lastPlayed = HashMap<TbEvent, Long>()

    @SuppressLint("MissingPermission")
    fun play(e: TbEvent) {
        val now = System.nanoTime() / 1_000_000L
        if (sound && now - (lastPlayed[e] ?: 0L) >= GAP_MS.getValue(e)) {
            lastPlayed[e] = now
            track(e)?.let {
                try {
                    it.stop()
                    it.setPlaybackHeadPosition(0)
                    it.play()
                } catch (_: IllegalStateException) {
                    // The device took the audio away; the game goes on silently.
                }
            }
        }
        val ms = BUZZ_MS[e] ?: 0L
        if (vibrate && ms > 0L && vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun release() {
        tracks.values.forEach { it.release() }
        tracks.clear()
    }

    private fun track(e: TbEvent): AudioTrack? {
        tracks[e]?.let { return it }
        val pcm = Synth.render(e)
        val track =
            try {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Synth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (_: UnsupportedOperationException) {
                return null
            }
        track.write(pcm, 0, pcm.size)
        tracks[e] = track
        return track
    }

    private companion object {
        /** The least time between two plays of a sound, so a held trigger doesn't turn into a buzz. */
        val GAP_MS = TbEvent.entries.associateWith { if (it == TbEvent.Hit) 60L else 40L }
        val BUZZ_MS = mapOf(TbEvent.Hurt to 45L, TbEvent.Kill to 12L, TbEvent.Fall to 220L, TbEvent.Seal to 120L)
    }
}

/** Builds each effect as 16-bit mono PCM: a sweep from one pitch to another, with a little noise. */
internal object Synth {
    const val RATE = 22_050

    fun render(e: TbEvent): ShortArray =
        when (e) {
            TbEvent.Fire -> tone(120, 1000f, 320f, 0.25f, 0.5f)
            TbEvent.Hit -> tone(55, 260f, 200f, 0.1f, 0.5f)
            TbEvent.Kill -> tone(170, 220f, 70f, 0.45f, 0.6f)
            TbEvent.Hurt -> tone(200, 140f, 70f, 0.35f, 0.7f)
            TbEvent.Dash -> tone(150, 300f, 900f, 0.8f, 0.3f)
            TbEvent.Draft -> arpeggio(floatArrayOf(392f, 494f, 587f, 784f), 90)
            TbEvent.Seal -> arpeggio(floatArrayOf(262f, 330f, 392f, 523f), 140)
            TbEvent.Fall -> tone(650, 260f, 45f, 0.25f, 0.6f)
        }

    fun tone(
        ms: Int,
        from: Float,
        to: Float,
        noise: Float,
        volume: Float,
    ): ShortArray {
        val n = RATE * ms / 1000
        val rnd = Random(ms)
        var phase = 0.0
        return ShortArray(n) { i ->
            val t = i.toFloat() / n
            val f = from + (to - from) * t
            phase += 2.0 * PI * f / RATE
            val env = (1f - t) * minOf(1f, i / 80f)
            val v = (sin(phase).toFloat() * (1f - noise) + (rnd.nextFloat() * 2f - 1f) * noise) * env * volume
            (v * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun arpeggio(
        notes: FloatArray,
        noteMs: Int,
    ): ShortArray = notes.fold(ShortArray(0)) { acc, f -> acc + tone(noteMs, f, f, 0f, 0.4f) }
}
