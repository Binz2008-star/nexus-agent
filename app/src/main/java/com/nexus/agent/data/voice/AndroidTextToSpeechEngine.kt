package com.nexus.agent.data.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nexus.agent.domain.voice.SpeechEngine
import com.nexus.agent.domain.voice.VoiceOption
import com.nexus.agent.domain.voice.VoiceSelectionPolicy
import java.util.UUID

/**
 * A local-only TextToSpeech adapter. Public calls are UI-facing; engine lifecycle and
 * callbacks are serialized on the main looper. No network-dependent voice is selected.
 */
class AndroidTextToSpeechEngine(context: Context) : SpeechEngine {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private enum class State { IDLE, INITIALIZING, READY, FAILED }
    private var state = State.IDLE
    private var revision = 0L
    private var engine: TextToSpeech? = null
    private val initCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var currentUtterance: String? = null
    private var currentFinished: (() -> Unit)? = null
    @Volatile private var speaking = false

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else handler.post(block)
    }

    override fun initialize(onReady: (Boolean) -> Unit) = onMain {
        when (state) {
            State.READY -> { onReady(true); return@onMain }
            State.INITIALIZING -> { initCallbacks += onReady; return@onMain }
            State.IDLE, State.FAILED -> Unit
        }
        initCallbacks += onReady
        state = State.INITIALIZING
        val expected = ++revision
        // The listener always posts back onto main, including synchronous platform callbacks.
        try {
            engine = TextToSpeech(appContext) { status ->
                handler.post {
                    if (expected != revision || state != State.INITIALIZING) return@post
                    val ready = status == TextToSpeech.SUCCESS && engine != null
                    state = if (ready) State.READY else State.FAILED
                    if (ready) engine?.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) { finish(utteranceId) }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) { finish(utteranceId) }
                        }
                    ) else {
                        engine?.shutdown()
                        engine = null
                    }
                    val pending = initCallbacks.toList()
                    initCallbacks.clear()
                    pending.forEach { it(ready) }
                }
            }
        } catch (_: Exception) {
            state = State.FAILED
            engine = null
            val pending = initCallbacks.toList()
            initCallbacks.clear()
            pending.forEach { it(false) }
        }
    }

    private fun finish(id: String?) = onMain {
        if (id != null && id == currentUtterance) {
            currentUtterance = null
            speaking = false
            val callback = currentFinished
            currentFinished = null
            callback?.invoke()
        }
    }

    override fun selectVoice(voiceId: String): Boolean {
        // Called by the Compose UI after initialization. Only installed offline voices qualify.
        if (state != State.READY || voiceId.isBlank()) return false
        val tts = engine ?: return false
        val allowed = VoiceSelectionPolicy.available(voices()).any { it.id == voiceId }
        if (!allowed) return false
        return try {
            val voice = tts.voices.firstOrNull {
                it.name == voiceId && !it.isNetworkConnectionRequired
            } ?: return false
            tts.setVoice(voice) == TextToSpeech.SUCCESS
        } catch (_: Exception) { false }
    }

    override fun speak(text: String, rate: Float, pitch: Float, onDone: () -> Unit) = onMain {
        val tts = engine
        if (state != State.READY || tts == null || text.isBlank()) {
            onDone()
            return@onMain
        }
        // Do not allow Android's default TTS engine to silently select a network voice.
        val currentVoice = try { tts.voice } catch (_: Exception) { null }
        if (currentVoice == null || currentVoice.isNetworkConnectionRequired) {
            val fallback = VoiceSelectionPolicy.resolveOffline(voices(), "")
            if (fallback == null || !selectVoice(fallback.id)) {
                onDone()
                return@onMain
            }
        }
        stopInternal()
        tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
        val id = UUID.randomUUID().toString()
        currentUtterance = id
        currentFinished = onDone
        speaking = true
        try {
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                finish(id)
            }
        } catch (_: Exception) {
            finish(id)
        }
    }

    private fun stopInternal() {
        val callback = currentFinished
        currentFinished = null
        currentUtterance = null
        speaking = false
        try { engine?.stop() } catch (_: Exception) { }
        callback?.invoke()
    }

    override fun stop() = onMain { stopInternal() }

    override fun shutdown() = onMain {
        ++revision // Reject a late callback from a destroyed engine.
        stopInternal()
        try { engine?.shutdown() } catch (_: Exception) { }
        engine = null
        state = State.IDLE
        val pending = initCallbacks.toList()
        initCallbacks.clear()
        pending.forEach { it(false) }
    }

    override fun isSpeaking(): Boolean = speaking

    override fun voices(): List<VoiceOption> {
        if (state != State.READY) return emptyList()
        val tts = engine ?: return emptyList()
        return try {
            val pkg = tts.defaultEngine
            tts.voices.orEmpty().map { voice ->
                VoiceOption(
                    id = voice.name,
                    name = voice.name,
                    locale = voice.locale.toLanguageTag(),
                    enginePackage = pkg,
                    isNetworkRequired = voice.isNetworkConnectionRequired
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}
