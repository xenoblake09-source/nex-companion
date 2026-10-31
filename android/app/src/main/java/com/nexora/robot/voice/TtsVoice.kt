package com.nexora.robot.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nexora.robot.util.NexLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * NEX's voice: warm, cute, clear and respectful, on top of the platform TTS engine.
 *
 * Voice design rules (product requirement, not decoration):
 *  * Android TTS is the reliable baseline — there is no server voice dependency;
 *  * pitch is nudged up slightly and the rate down slightly, which reads as friendly and
 *    gentle. It deliberately does **not** imitate a real person, and it is not a deceptive
 *    childlike voice;
 *  * spoken language follows the reply language (Hindi replies get a Hindi voice when the
 *    device has one, otherwise NEX says so and stays in English);
 *  * completion is reported reliably, because the whole microphone timing contract depends on
 *    knowing exactly when speech ended.
 *
 * All listener callbacks are marshalled onto the main thread before they leave this class.
 */
class TtsVoice(
    context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onEngineReady()
        fun onEngineUnavailable(reason: String)
        fun onSpeechStarted(utteranceId: String)
        fun onSpeechCompleted(utteranceId: String)
        fun onSpeechFailed(utteranceId: String)
        fun onLanguageFallback(requested: String, used: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val utteranceIds = AtomicInteger(0)

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var stoppedByCaller: Boolean = false

    @Volatile
    private var activeLanguage: String = "en"

    val isReady: Boolean get() = ready

    val isSpeaking: Boolean get() = engine?.isSpeaking == true

    /** Initialise the engine. Safe to call more than once. */
    fun prepare() {
        if (engine != null) return
        NexLog.i("preparing TTS engine")
        engine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configure(engine)
            } else {
                ready = false
                NexLog.w("TTS init failed status=$status")
                post { listener.onEngineUnavailable("This device has no working text-to-speech engine.") }
            }
        }
    }

    private fun configure(tts: TextToSpeech?) {
        if (tts == null) return
        tts.setPitch(PITCH)
        tts.setSpeechRate(RATE)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    post { listener.onSpeechStarted(utteranceId.orEmpty()) }
                }

                override fun onDone(utteranceId: String?) {
                    post { listener.onSpeechCompleted(utteranceId.orEmpty()) }
                }

                @Deprecated("Deprecated in the platform API")
                override fun onError(utteranceId: String?) {
                    post { listener.onSpeechFailed(utteranceId.orEmpty()) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    NexLog.w("TTS error code=$errorCode utterance=${utteranceId.orEmpty()}")
                    post { listener.onSpeechFailed(utteranceId.orEmpty()) }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    // A caller-initiated stop is not a failure: it must still release the mic gate.
                    post {
                        if (stoppedByCaller) listener.onSpeechCompleted(utteranceId.orEmpty())
                        else listener.onSpeechFailed(utteranceId.orEmpty())
                    }
                }
            },
        )
        applyLanguage(activeLanguage)
        ready = true
        post { listener.onEngineReady() }
    }

    /**
     * Speak one reply.
     *
     * @return the utterance id, so the caller can correlate callbacks
     */
    fun speak(text: String, language: String): String {
        val utteranceId = nextUtteranceId()
        val tts = engine
        if (tts == null || !ready || text.isBlank()) {
            // Never leave the state machine waiting for a callback that cannot arrive.
            post { listener.onSpeechFailed(utteranceId) }
            return utteranceId
        }
        stoppedByCaller = false
        applyLanguage(language)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, VOLUME) }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            NexLog.w("TTS speak rejected result=$result")
            post { listener.onSpeechFailed(utteranceId) }
        }
        return utteranceId
    }

    /** Stop immediately. The caller must still receive a completion callback. */
    fun stop() {
        stoppedByCaller = true
        runCatching { engine?.stop() }.onFailure { NexLog.w("TTS stop failed", it) }
    }

    fun shutdown() {
        ready = false
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
    }

    /** Languages this device can actually speak, as far as the engine reports. */
    fun supports(language: String): Boolean {
        val tts = engine ?: return false
        val locale = localeFor(language)
        val result = tts.isLanguageAvailable(locale)
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun applyLanguage(language: String) {
        val tts = engine ?: return
        if (language == activeLanguage && ready) return
        val locale = localeFor(language)
        val result = tts.setLanguage(locale)
        val usable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (usable) {
            activeLanguage = language
        } else {
            val fallback = localeFor("en")
            tts.setLanguage(fallback)
            activeLanguage = "en"
            val requested = language
            post { listener.onLanguageFallback(requested, "en") }
            NexLog.w("TTS language $language unavailable, fell back to English")
        }
    }

    private fun localeFor(language: String): Locale = when {
        language.lowercase().startsWith("hi") -> Locale("hi", "IN")
        else -> Locale.US
    }

    private fun nextUtteranceId(): String = "nex-${utteranceIds.incrementAndGet()}"

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        /** Slightly bright, never shrill: warm and cute without being deceptive. */
        const val PITCH = 1.15f

        /** A touch slower than default so every word stays clear. */
        const val RATE = 0.94f

        const val VOLUME = 1.0f
    }
}
