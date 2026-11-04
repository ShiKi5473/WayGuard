package com.wayguard.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks alerts in Mandarin (Taiwan) over navigation-guidance audio, ducking music
 * for the duration, and plays short warning beeps.
 */
class Announcer(context: Context) : TextToSpeech.OnInitListener {

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
    @Volatile var voiceEnabled = true

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            return
        }
        val lang = tts.setLanguage(Locale.TAIWAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fall back to any Chinese voice the device has.
            tts.setLanguage(Locale.CHINESE)
        }
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = releaseFocusIfIdle()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = releaseFocusIfIdle()
        })
        ready = true
    }

    /** [urgent] interrupts whatever is being said (e.g. an overspeed warning). */
    fun say(text: String, urgent: Boolean = false) {
        if (!voiceEnabled || !ready) return
        audio.requestAudioFocus(focus)
        tts.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "wg-${ids.incrementAndGet()}")
    }

    fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
    }

    private fun releaseFocusIfIdle() {
        if (!tts.isSpeaking) audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        ready = false
        runCatching { tts.stop(); tts.shutdown() }
        tone?.release()
        audio.abandonAudioFocusRequest(focus)
    }

    private companion object {
        const val TAG = "Announcer"
    }
}
