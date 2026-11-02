package com.nexus.agent.domain.voice

/**
 * Voice choices must be local for the default no-cost, privacy-preserving experience.
 * Do not infer gender from voice identifiers; audition is the only reliable choice.
 */
object VoiceSelectionPolicy {
    fun available(voices: List<VoiceOption>): List<VoiceOption> =
        voices.filter { !it.isNetworkRequired && it.id.isNotBlank() }
            .distinctBy { it.enginePackage.orEmpty() to it.id }
            .sortedWith(
                compareByDescending<VoiceOption> { it.locale.startsWith("ar", ignoreCase = true) }
                    .thenBy { it.locale }.thenBy { it.name }
            )

    fun resolveOffline(voices: List<VoiceOption>, savedId: String): VoiceOption? {
        val offline = available(voices)
        return if (savedId.isNotBlank()) offline.firstOrNull { it.id == savedId }
        else offline.firstOrNull { it.locale.startsWith("ar", ignoreCase = true) }
            ?: offline.firstOrNull()
    }
}
