package com.nexus.agent.data.voice

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.nexus.agent.domain.voice.VoiceInputEngine
import com.nexus.agent.domain.voice.VoiceInputError
import com.nexus.agent.domain.voice.VoiceInputListener
import com.nexus.agent.domain.voice.VoiceInputPolicy
import com.nexus.agent.domain.voice.VoiceInputState

/**
 * Android speech-to-text adapter.
 *
 * Privacy: dictation is **offline by default**. With `offlineOnly = true` the engine refuses to
 * start unless a strictly on-device recognizer is available, so audio cannot be uploaded. Only
 * when the user explicitly turns the setting off does it fall back to the platform recognizer,
 * which may use the network. Audio is never written to disk and never logged.
 *
 * Every recognizer is created for exactly one dictation session and released when that session
 * ends, so no speech-service binding is held between dictations and a freshly installed offline
 * model is picked up next time.
 *
 * All SpeechRecognizer interaction happens on the main thread.
 */
class AndroidSpeechRecognizerEngine(context: Context) : VoiceInputEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: VoiceInputListener? = null
    private var watchdog: Runnable? = null

    /**
     * Identifies the current dictation session. Every callback captures its session id and is
     * ignored if it no longer matches, which removes the cancel/restart race that a boolean
     * `cancelled` flag cannot express.
     */
    private var session = 0L
    private var listening = false
    private var destroyed = false

    override fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    } catch (_: Exception) {
        false
    }

    override fun hasOfflineRecognizer(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
    } catch (_: Exception) {
        false
    }

    override fun start(locale: String, offlineOnly: Boolean, listener: VoiceInputListener) {
        onMain {
            if (destroyed) return@onMain
            if (listening) {
                listener.onError(VoiceInputPolicy.busy())
                return@onMain
            }
            if (!isAvailable()) {
                listener.onError(VoiceInputPolicy.unavailable())
                listener.onState(VoiceInputState.UNAVAILABLE)
                return@onMain
            }
            val offlineAvailable = hasOfflineRecognizer()
            if (offlineOnly && !offlineAvailable) {
                listener.onError(VoiceInputPolicy.offlineUnavailable())
                listener.onState(VoiceInputState.UNAVAILABLE)
                return@onMain
            }

            releaseRecognizer()
            val created = try {
                if (offlineAvailable) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }
            } catch (_: Exception) {
                null
            }
            if (created == null) {
                listener.onError(VoiceInputPolicy.unavailable())
                listener.onState(VoiceInputState.UNAVAILABLE)
                return@onMain
            }

            val sessionId = ++session
            try {
                created.setRecognitionListener(adapterFor(sessionId))
            } catch (_: Exception) {
                safeDestroy(created)
                listener.onError(VoiceInputPolicy.unavailable())
                listener.onState(VoiceInputState.UNAVAILABLE)
                return@onMain
            }

            recognizer = created
            this.listener = listener
            listening = true
            listener.onState(VoiceInputState.LISTENING)

            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineOnly)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                // EXTRA_PREFER_OFFLINE is only a hint, so offline-only mode additionally requires
                // an on-device recognizer (createOnDeviceSpeechRecognizer) before we get here.
            }

            try {
                created.startListening(intent)
            } catch (_: Exception) {
                listening = false
                releaseRecognizer()
                listener.onError(VoiceInputPolicy.clientError())
                listener.onState(VoiceInputState.ERROR)
            }
        }
    }

    override fun stop() {
        onMain {
            if (!listening) return@onMain
            listening = false
            listener?.onState(VoiceInputState.PROCESSING)
            armWatchdog()
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
                // stopListening is best-effort; if it fails the session is dead. Release it and
                // surface an error rather than leaving the UI stuck on "processing".
                val error = VoiceInputPolicy.clientError()
                releaseRecognizer()
                listener?.onError(error)
                listener?.onState(VoiceInputState.ERROR)
            }
        }
    }

    override fun cancel() {
        onMain {
            // Invalidate the session first so any late callback from the cancelled recognizer is
            // dropped instead of being attributed to the next dictation.
            session++
            listening = false
            releaseRecognizer()
            listener?.onState(VoiceInputState.IDLE)
        }
    }

    override fun destroy() {
        onMain {
            destroyed = true
            session++
            listening = false
            releaseRecognizer()
            listener = null
        }
    }

    private fun adapterFor(sessionId: Long): RecognitionListener = object : RecognitionListener {
        private fun alive(): Boolean = sessionId == session && !destroyed

        override fun onReadyForSpeech(params: Bundle?) {
            if (alive()) listener?.onState(VoiceInputState.LISTENING)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!alive()) return
            listening = false
            listener?.onState(VoiceInputState.PROCESSING)
            armWatchdog()
        }

        override fun onError(error: Int) {
            if (!alive()) return
            listening = false
            cancelWatchdog()
            val sessionListener = listener
            val mapped = VoiceInputPolicy.describeError(error)
            releaseRecognizer()
            sessionListener?.onError(mapped)
            sessionListener?.onState(VoiceInputState.ERROR)
        }

        override fun onResults(results: Bundle?) {
            if (!alive()) return
            listening = false
            cancelWatchdog()
            val sessionListener = listener
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            releaseRecognizer()
            if (text.isBlank()) {
                sessionListener?.onError(VoiceInputPolicy.describeError(SpeechRecognizer.ERROR_NO_MATCH))
                sessionListener?.onState(VoiceInputState.ERROR)
            } else {
                sessionListener?.onFinal(text)
                sessionListener?.onState(VoiceInputState.IDLE)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!alive()) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) listener?.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * Some recognizers deliver neither `onResults` nor `onError`. Without a watchdog the composer
     * would stay on the "processing" spinner forever.
     */
    private fun armWatchdog() {
        cancelWatchdog()
        val sessionId = session
        val task = Runnable {
            watchdog = null
            if (sessionId != session || destroyed) return@Runnable
            listening = false
            val sessionListener = listener
            val error = VoiceInputPolicy.clientError()
            releaseRecognizer()
            sessionListener?.onError(error)
            sessionListener?.onState(VoiceInputState.ERROR)
        }
        watchdog = task
        mainHandler.postDelayed(task, WATCHDOG_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    private fun releaseRecognizer() {
        cancelWatchdog()
        val current = recognizer
        recognizer = null
        listening = false
        if (current != null) safeDestroy(current)
    }

    private fun safeDestroy(target: SpeechRecognizer) {
        try {
            target.destroy()
        } catch (_: Exception) {
            // Nothing useful to do; the binding is released by the framework.
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private companion object {
        const val WATCHDOG_TIMEOUT_MS = 12_000L
    }
}
