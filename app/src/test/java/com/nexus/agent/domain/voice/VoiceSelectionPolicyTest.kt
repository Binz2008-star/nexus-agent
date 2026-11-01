package com.nexus.agent.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSelectionPolicyTest {
    private val localArabic = VoiceOption("ar-local", "Arabic", "ar-AE", "test.engine", false)
    private val cloudArabic = VoiceOption("ar-network", "Cloud", "ar-AE", "test.engine", true)
    private val localEnglish = VoiceOption("en-local", "English", "en-US", "test.engine", false)

    @Test fun networkVoicesAreExcludedFromOfflineLibrary() {
        assertEquals(listOf(localArabic, localEnglish),
            VoiceSelectionPolicy.available(listOf(localEnglish, cloudArabic, localArabic)))
    }

    @Test fun savedLocalVoiceWins() {
        assertEquals(localEnglish, VoiceSelectionPolicy.resolveOffline(
            listOf(localArabic, localEnglish), "en-local"))
    }

    @Test fun missingSavedVoiceDoesNotSilentlyUseAnotherVoice() {
        assertNull(VoiceSelectionPolicy.resolveOffline(listOf(localArabic), "removed-voice"))
        assertNull(VoiceSelectionPolicy.resolveOffline(listOf(cloudArabic), "ar-network"))
    }

    @Test fun noPreferenceDefaultsToOfflineArabicBeforeEnglish() {
        assertEquals(localArabic, VoiceSelectionPolicy.resolveOffline(
            listOf(localEnglish, localArabic, cloudArabic), ""))
        assertTrue(VoiceSelectionPolicy.available(listOf(cloudArabic)).isEmpty())
    }
}
