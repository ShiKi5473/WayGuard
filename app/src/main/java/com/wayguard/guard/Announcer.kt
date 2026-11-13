package com.wayguard.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks alerts in Mandarin (Taiwan) over navigation-guidance audio, ducking music
 * for the duration, and plays short warning beeps.
 */
class Announcer(
    context: Context,
    private val onError: (String?) -> Unit = {},
) : TextToSpeech.OnInitListener {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val tts = TextToSpeech(context.applicationContext, this)
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    private val ids = AtomicInteger()

    @Volatile private var ready = false
    @Volatile var ttsError: String? = null
        private set
    @Volatile var voiceEnabled = true

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            ttsError = "語音引擎無法使用，改用提示音"
            onError(ttsError)
            return
        }
        var lang = tts.setLanguage(Locale.TAIWAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fall back to any Chinese voice the device has.
            lang = tts.setLanguage(Locale.CHINESE)
        }
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Chinese TTS language unsupported: $lang")
            ttsError = "裝置缺少中文語音，改用提示音"
            onError(ttsError)
            return
        }
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = onUtteranceEnded()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onUtteranceEnded()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = onUtteranceEnded()
        })
        ttsError = null
        onError(null)
        ready = true
    }

    private class Utterance(val text: String, val urgent: Boolean, val key: String?, val atMs: Long)

    private val lock = Any()
    /** Waiting to be spoken, urgent ones first. Guarded by [lock]. */
    private val pending = ArrayList<Utterance>()
    /** When the utterance being spoken started; 0 = idle. Guarded by [lock]. */
    private var speakingSinceMs = 0L

    /** Something is being said or waiting to be said — a beep now would talk over it. */
    val isBusy: Boolean
        get() = synchronized(lock) { speakingSinceMs != 0L || pending.isNotEmpty() }

    /**
     * Speaks [text] after whatever is being said; nothing is cut off mid-sentence (an
     * approach warning followed a second later by an overspeed one must both be heard).
     * [urgent] ones go ahead of normal ones still waiting. A [key] replaces a waiting
     * utterance with the same key, e.g. a camera's near warning its unspoken approach one.
     */
    fun say(text: String, urgent: Boolean = false, key: String? = null) {
        if (!voiceEnabled) return
        if (!ready) {
            if (ttsError != null) beep()
            return
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            pending.removeAll { it.text == text || (key != null && it.key == key) }
            val u = Utterance(text, urgent, key, now)
            if (urgent) {
                val firstNormal = pending.indexOfFirst { !it.urgent }
                pending.add(if (firstNormal < 0) pending.size else firstNormal, u)
            } else {
                pending.add(u)
            }
            // A TTS engine that never reports the end must not silence everything after it.
            if (speakingSinceMs != 0L && now - speakingSinceMs > STUCK_MS) {
                Log.w(TAG, "TTS did not finish an utterance, moving on")
                speakingSinceMs = 0L
            }
            if (speakingSinceMs == 0L) speakNextLocked()
        }
    }

    fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
    }

    private fun onUtteranceEnded() = synchronized(lock) {
        speakingSinceMs = 0L
        speakNextLocked()
    }

    /** Starts the next still-relevant utterance, or releases audio focus when there is none. */
    private fun speakNextLocked() {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            // A distance or speed said long after the fact is wrong; drop it.
            pending.removeAll { now - it.atMs > MAX_WAIT_MS }
            if (pending.isEmpty() || !ready) {
                pending.clear()
                audio.abandonAudioFocusRequest(focus)
                return
            }
            val next = pending.removeAt(0)
            audio.requestAudioFocus(focus)
            if (tts.speak(next.text, TextToSpeech.QUEUE_ADD, null, "wg-${ids.incrementAndGet()}") == TextToSpeech.SUCCESS) {
                speakingSinceMs = now
                return
            }
        }
    }

    fun shutdown() {
        ready = false
        synchronized(lock) {
            pending.clear()
            speakingSinceMs = 0L
        }
        runCatching { tts.stop(); tts.shutdown() }
        tone?.release()
        audio.abandonAudioFocusRequest(focus)
    }

    private companion object {
        const val TAG = "Announcer"
        /** Waiting longer than this makes an alert stale (≈ 200 m at 100 km/h). */
        const val MAX_WAIT_MS = 8_000L
        /** No alert takes this long to say; past it the end report is assumed lost. */
        const val STUCK_MS = 15_000L
    }
}
