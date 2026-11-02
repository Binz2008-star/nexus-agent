package com.nexus.agent.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputPolicyTest {
    @Test fun dialectMapsToRecognitionLocale() {
        assertEquals("ar-AE", VoiceInputPolicy.localeForDialect("gulf", "en-US"))
        assertEquals("ar-EG", VoiceInputPolicy.localeForDialect("egypt", "en-US"))
        assertEquals("ar-SY", VoiceInputPolicy.localeForDialect("levant", "en-US"))
        assertEquals("ar-MA", VoiceInputPolicy.localeForDialect("maghreb", "en-US"))
        assertEquals("ar-IQ", VoiceInputPolicy.localeForDialect("iraq", "en-US"))
        assertEquals("ar-SD", VoiceInputPolicy.localeForDialect("sudan", "en-US"))
        assertEquals("ar-YE", VoiceInputPolicy.localeForDialect("yemen", "en-US"))
        assertEquals("en-US", VoiceInputPolicy.localeForDialect("en-us", "ar-AE"))
        assertEquals("en-GB", VoiceInputPolicy.localeForDialect("en-uk", "ar-AE"))
    }

    @Test fun autoUsesAnArabicOrEnglishDeviceLocale() {
        assertEquals("ar-SA", VoiceInputPolicy.localeForDialect("auto", "ar-SA"))
        assertEquals("ar", VoiceInputPolicy.localeForDialect("auto", "ar"))
        assertEquals("en-GB", VoiceInputPolicy.localeForDialect("auto", "en-GB"))
    }

    @Test fun autoDefaultsToEmiratiArabicForOtherDeviceLocales() {
        assertEquals("ar-AE", VoiceInputPolicy.localeForDialect("auto", "fr-FR"))
        assertEquals("ar-AE", VoiceInputPolicy.localeForDialect("auto", ""))
        assertEquals("ar-AE", VoiceInputPolicy.localeForDialect("auto", "   "))
    }

    @Test fun unknownDialectFallsBackToEmiratiArabic() {
        assertEquals("ar-AE", VoiceInputPolicy.localeForDialect("klingon", "en-US"))
        assertEquals("ar-AE", VoiceInputPolicy.localeForDialect("", "en-US"))
    }

    @Test fun androidErrorCodesMapToStableCategories() {
        assertEquals(VoiceInputErrorCode.NETWORK, VoiceInputPolicy.describeError(1).code)
        assertEquals(VoiceInputErrorCode.NETWORK, VoiceInputPolicy.describeError(2).code)
        assertEquals(VoiceInputErrorCode.AUDIO, VoiceInputPolicy.describeError(3).code)
        assertEquals(VoiceInputErrorCode.SERVER, VoiceInputPolicy.describeError(4).code)
        assertEquals(VoiceInputErrorCode.CLIENT, VoiceInputPolicy.describeError(5).code)
        assertEquals(VoiceInputErrorCode.SPEECH_TIMEOUT, VoiceInputPolicy.describeError(6).code)
        assertEquals(VoiceInputErrorCode.NO_MATCH, VoiceInputPolicy.describeError(7).code)
        assertEquals(VoiceInputErrorCode.BUSY, VoiceInputPolicy.describeError(8).code)
        assertEquals(VoiceInputErrorCode.PERMISSION_DENIED, VoiceInputPolicy.describeError(9).code)
        assertEquals(VoiceInputErrorCode.UNKNOWN, VoiceInputPolicy.describeError(0).code)
        assertEquals(VoiceInputErrorCode.UNKNOWN, VoiceInputPolicy.describeError(99).code)
    }

    @Test fun everyErrorHasAnArabicMessage() {
        (1..10).forEach { code ->
            val message = VoiceInputPolicy.describeError(code).message
            assertTrue("code $code must not be blank", message.isNotBlank())
            assertTrue(
                "code $code must be Arabic",
                message.any { it.code in 0x0600..0x06FF }
            )
        }
        assertTrue(VoiceInputPolicy.unavailable().message.isNotBlank())
        assertEquals(VoiceInputErrorCode.NO_RECOGNIZER, VoiceInputPolicy.unavailable().code)
    }

    @Test fun sanitizeCollapsesWhitespaceAndCapsLength() {
        assertEquals(
            "مرحبا بالعالم",
            VoiceInputPolicy.sanitizeTranscript("  مرحبا   \n\t بالعالم  ")
        )
        assertEquals("", VoiceInputPolicy.sanitizeTranscript("   "))
        assertEquals(10, VoiceInputPolicy.sanitizeTranscript("abcdefghijklmnop", 10).length)
    }

    @Test fun appendKeepsExistingComposerText() {
        assertEquals("hello world", VoiceInputPolicy.appendTranscript("hello", "world"))
        assertEquals("hello", VoiceInputPolicy.appendTranscript("hello", "   "))
        assertEquals("world", VoiceInputPolicy.appendTranscript("", "world"))
        assertEquals("sentence", VoiceInputPolicy.appendTranscript("sentence  ", ""))
        assertEquals("مرحبا بك", VoiceInputPolicy.appendTranscript("مرحبا", "  بك "))
    }
}
