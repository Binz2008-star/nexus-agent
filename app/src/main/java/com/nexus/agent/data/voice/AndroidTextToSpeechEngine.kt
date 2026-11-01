package com.nexus.agent.data.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nexus.agent.domain.voice.SpeechEngine
import com.nexus.agent.domain.voice.VoiceOption
import java.util.Locale

/**
 * Android built-in TextToSpeech wrapper. Zero-cost, offline-capable (when the selected voice is
 * installed locally). Lifecycle-safe: no work is done on the caller's thread, utterances are
 * queued with stable ids, and disposal shuts down the engine. Network-dependent voices are
 * surfaced via [VoiceOption.isNetworkRequired] so the UI can label them truthfully.
 */
class AndroidTextToSpeechEngine(private val context: Context) : SpeechEngine {

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var speaking = false

    override fun initialize(onReady: (Boolean) -> Unit) {
        if (tts != null) { onReady(true); return }
        TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false }
                })
            }
            onReady(ok)
        }
    }

    override fun speak(text: String, rate: Float, pitch: Float, onDone: () -> Unit) {
        val engine = tts ?: run { onDone(); return }
        if (text.isBlank()) { onDone(); return }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
        val id = "nexus-${System.currentTimeMillis()}"
        speaking = true
        // onDone via a per-call listener requires a separate listener; use a one-shot wrapper.
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                speaking = false
                onDone()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speaking = false
                onDone()
            }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        speaking = false
        tts?.stop()
    }

    override fun shutdown() {
        speaking = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    override fun isSpeaking(): Boolean = speaking

    override fun voices(): List<VoiceOption> =
        tts?.voices?.map { v ->
            VoiceOption(
                id = v.name,
                name = v.name,
                locale = v.locale?.toLanguageTag() ?: "",
                enginePackage = null,
                isNetworkRequired = v.isNetworkConnectionRequired
            )
        }.orEmpty()

    companion object {
        fun defaultLocale(): Locale = Locale.getDefault()
    }
}
