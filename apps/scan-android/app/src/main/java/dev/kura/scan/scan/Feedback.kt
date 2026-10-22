package dev.kura.scan.scan

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Retour vibration + bip (ADR 0006). Chaque appel est protégé : certains appareils n'ont ni vibreur ni son. */
class Feedback(context: Context) {
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: RuntimeException) {
        null
    }

    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
    } catch (e: RuntimeException) {
        null
    }

    fun success() {
        vibrate(longArrayOf(0, 60))
        beep(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    fun failure() {
        vibrate(longArrayOf(0, 80, 90, 80))
        beep(ToneGenerator.TONE_PROP_NACK, 200)
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (e: RuntimeException) {
            // Vibreur indisponible : sans conséquence.
        }
    }

    private fun beep(type: Int, durationMs: Int) {
        try {
            tone?.startTone(type, durationMs)
        } catch (e: RuntimeException) {
            tone = null
        }
    }

    fun release() {
        tone?.release()
        tone = null
    }
}
