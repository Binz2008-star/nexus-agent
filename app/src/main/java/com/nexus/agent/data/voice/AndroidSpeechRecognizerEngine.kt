package com.nexus.agent.data.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.nexus.agent.domain.voice.VoiceInputEngine
import com.nexus.agent.domain.voice.VoiceInputListener
import com.nexus.agent.domain.voice.VoiceInputPolicy
import com.nexus.agent.domain.voice.VoiceInputState

/**
 * On-device-first speech-to-text adapter.
 *
 * Prefers the offline recognizer (Android 12+) so the default experience costs nothing and the
 * audio does not leave the phone; falls back to the platform recognizer when no offline model
 * is installed. Audio is never persisted and a transcript only ever lands in the composer.
 *
 * All engine lifecycle calls are serialized on the main looper (SpeechRecognizer requirement).
 */
class AndroidSpeechRecognizerEngine(context: Context) : VoiceInputEngine {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listener: VoiceInputListener? = null
    private var listening = false
    private var cancelled = false

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    override fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(appContext) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext))
    } catch (_: Exception) {
        false
    }

    override fun start(locale: String, listener: VoiceInputListener) = onMain {
        if (listening) {
            listener.onError(VoiceInputPolicy.busy())
            return@onMain
        }
        if (!isAvailable()) {
            listener.onError(VoiceInputPolicy.unavailable())
            listener.onState(VoiceInputState.UNAVAILABLE)
            return@onMain
        }
        val current = ensureRecognizer()
        if (current == null) {
            listener.onError(VoiceInputPolicy.unavailable())
            listener.onState(VoiceInputState.UNAVAILABLE)
            return@onMain
        }
        this.listener = listener
        cancelled = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Prefer the offline model; the platform may ignore this when none is installed.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        listening = true
        listener.onState(VoiceInputState.LISTENING)
        try {
            current.startListening(intent)
        } catch (_: Exception) {
            listening = false
            listener.onError(VoiceInputPolicy.clientError())
            listener.onState(VoiceInputState.ERROR)
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        val created = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        } catch (_: Exception) {
            null
        } ?: return null
        created.setRecognitionListener(adapter)
        recognizer = created
        return created
    }

    private val adapter = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!cancelled) listener?.onState(VoiceInputState.PROCESSING)
        }

        override fun onError(error: Int) {
            val current = listener
            listening = false
            if (cancelled) {
                // ERROR_CLIENT after an intentional cancel is expected; stay silent.
                cancelled = false
                return
            }
            current?.onError(VoiceInputPolicy.describeError(error))
            current?.onState(VoiceInputState.ERROR)
        }

        override fun onResults(results: Bundle?) {
            val current = listener
            listening = false
            if (cancelled) {
                cancelled = false
                return
            }
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            val clean = VoiceInputPolicy.sanitizeTranscript(text)
            if (clean.isEmpty()) {
                current?.onError(VoiceInputPolicy.describeError(7)) // ERROR_NO_MATCH
                current?.onState(VoiceInputState.ERROR)
            } else {
                current?.onFinal(clean)
                current?.onState(VoiceInputState.IDLE)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (cancelled) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                listener?.onPartial(VoiceInputPolicy.sanitizeTranscript(text))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun stop() = onMain {
        if (!listening) return@onMain
        listener?.onState(VoiceInputState.PROCESSING)
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            listening = false
        }
    }

    override fun cancel() = onMain {
        cancelled = true
        listening = false
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
            // Nothing to recover from: the recognizer is being discarded either way.
        }
        listener?.onState(VoiceInputState.IDLE)
    }

    override fun destroy() = onMain {
        cancelled = true
        listening = false
        listener = null
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
            // Ignore: teardown must never throw into the UI.
        }
        recognizer = null
    }
}
