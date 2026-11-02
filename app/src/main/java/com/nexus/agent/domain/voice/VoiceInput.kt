package com.nexus.agent.domain.voice

/** Lifecycle of a single dictation attempt. */
enum class VoiceInputState { IDLE, LISTENING, PROCESSING, ERROR, UNAVAILABLE }

/** Stable error categories so the UI can message the user without leaking platform codes. */
enum class VoiceInputErrorCode {
    PERMISSION_DENIED,
    NO_RECOGNIZER,
    BUSY,
    NO_MATCH,
    SPEECH_TIMEOUT,
    NETWORK,
    AUDIO,
    CLIENT,
    SERVER,
    UNKNOWN
}

data class VoiceInputError(val code: VoiceInputErrorCode, val message: String)

/** Callbacks from a speech-to-text engine. Always delivered on the main thread. */
interface VoiceInputListener {
    fun onState(state: VoiceInputState)
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(error: VoiceInputError)
}

/**
 * Minimal speech-to-text contract (the input mirror of [SpeechEngine]).
 *
 * Dictation only produces text for the composer: it never sends a message, never triggers a
 * tool and never bypasses an approval gate. Voice (sound) and persona stay separate concerns.
 */
interface VoiceInputEngine {
    /** True when the device has a usable recognizer (offline or platform). */
    fun isAvailable(): Boolean

    /** Start dictating in [locale] (BCP-47, e.g. "ar-AE"). No-op if already listening. */
    fun start(locale: String, listener: VoiceInputListener)

    /** Stop and let the engine deliver the final transcript. */
    fun stop()

    /** Abort immediately; no final result is delivered. */
    fun cancel()

    /** Release the engine. Call when the owner leaves composition. */
    fun destroy()
}
