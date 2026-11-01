package com.nexus.agent.domain.voice

/**
 * A voice option exposed by a TTS engine. Gender is deliberately NOT inferred from the name
 * (Android's [android.speech.tts.Voice] metadata does not guarantee it) — the user auditions.
 */
data class VoiceOption(
    val id: String,
    val name: String,
    val locale: String,
    val enginePackage: String?,
    val isNetworkRequired: Boolean
)

/**
 * Minimal speech engine contract. Voice (spoken sound) and persona (warm response style) are
 * separate concerns — neither may affect tool permissions or approval gates.
 */
interface SpeechEngine {
    /** Async one-shot init; [onReady] fires with success/failure. Must never block the main thread. */
    fun initialize(onReady: (Boolean) -> Unit)

    /** Speaks [text]; [onDone] fires after the utterance completes (or is cancelled). */
    fun speak(text: String, rate: Float, pitch: Float, onDone: () -> Unit)

    /**
     * Select an already-installed, strictly offline voice. Returns false for missing,
     * removed, or network-dependent voices. No silent fallback for saved IDs.
     */
    fun selectVoice(voiceId: String): Boolean

    /** Stops the current utterance immediately. */
    fun stop()

    /** Releases the underlying engine. Call when no longer needed. */
    fun shutdown()

    /** Currently synthesizing/speaking? */
    fun isSpeaking(): Boolean

    /** Voices offered by the engine (may be empty until [initialize] completes). */
    fun voices(): List<VoiceOption>
}
