package com.nexus.agent.domain.culture

import org.junit.Assert.assertEquals
import org.junit.Test

class CultureContextTest {

    @Test
    fun detectRegionFromLocale_gulfLocales() {
        assertEquals(Region.GULF, CultureContextEngine.detectRegionFromLocale("ar-SA"))
        assertEquals(Region.GULF, CultureContextEngine.detectRegionFromLocale("ar-AE"))
        assertEquals(Region.GULF, CultureContextEngine.detectRegionFromLocale("ar-OM"))
    }

    @Test
    fun detectRegionFromLocale_otherRegions() {
        assertEquals(Region.EGYPT, CultureContextEngine.detectRegionFromLocale("ar-EG"))
        assertEquals(Region.LEVANT, CultureContextEngine.detectRegionFromLocale("ar-SY"))
        assertEquals(Region.IRAQ, CultureContextEngine.detectRegionFromLocale("ar-IQ"))
        assertEquals(Region.SUDAN, CultureContextEngine.detectRegionFromLocale("ar-SD"))
        assertEquals(Region.YEMEN, CultureContextEngine.detectRegionFromLocale("ar-YE"))
    }

    @Test
    fun detectRegionFromLocale_englishAndUnknown() {
        assertEquals(Region.EN_GLOBAL, CultureContextEngine.detectRegionFromLocale("en-US"))
        assertEquals(Region.EN_GLOBAL, CultureContextEngine.detectRegionFromLocale("en-GB"))
        // Unknown locale falls back to Gulf (Mashaaer default).
        assertEquals(Region.GULF, CultureContextEngine.detectRegionFromLocale("xx-XX"))
    }

    @Test
    fun preferredLanguageFromLocale() {
        assertEquals("ar", CultureContextEngine.preferredLanguageFromLocale("ar-SA"))
        assertEquals("en", CultureContextEngine.preferredLanguageFromLocale("en-US"))
        assertEquals("fr", CultureContextEngine.preferredLanguageFromLocale("fr-FR"))
    }

    @Test
    fun regionAndToneSensitivityFromId() {
        assertEquals(Region.EGYPT, Region.fromId("egypt"))
        assertEquals(Region.GULF, Region.fromId(null))
        assertEquals(Region.GULF, Region.fromId("unknown"))
        assertEquals(ToneSensitivity.NEUTRAL, ToneSensitivity.fromId("neutral"))
        assertEquals(ToneSensitivity.SOFT, ToneSensitivity.fromId("bogus"))
    }

    @Test
    fun cultureContextDefaults() {
        val ctx = CultureContext()
        assertEquals(Region.GULF, ctx.region)
        assertEquals(ToneSensitivity.SOFT, ctx.toneSensitivity)
        assertEquals("ar", ctx.preferredLanguage)
        // copy() mirrors Mashaaer's set_user_context dict update.
        val updated = ctx.copy(region = Region.EGYPT, dialect = "egypt")
        assertEquals(Region.EGYPT, updated.region)
        assertEquals("egypt", updated.dialect)
        assertEquals(ToneSensitivity.SOFT, updated.toneSensitivity)
    }
}
