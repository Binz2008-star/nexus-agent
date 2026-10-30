package com.nexus.agent.domain.culture

/**
 * Geographic/cultural region a user belongs to. Ported from Mashaaer's
 * `culture_context_engine.py` (LOCALE_TO_REGION mapping).
 */
enum class Region(val id: String) {
    GULF("gulf"),
    EGYPT("egypt"),
    LEVANT("levant"),
    IRAQ("iraq"),
    SUDAN("sudan"),
    YEMEN("yemen"),
    MAGHREB("maghreb"),
    EN_GLOBAL("en-global"),
    FR_GLOBAL("fr-global");

    companion object {
        fun fromId(id: String?): Region = entries.firstOrNull { it.id == id } ?: GULF
    }
}

/** How sensitive/responsive the tone should be toward the user. */
enum class ToneSensitivity(val id: String) {
    SOFT("soft"),
    NEUTRAL("neutral"),
    ASSERTIVE("assertive");

    companion object {
        fun fromId(id: String?): ToneSensitivity = entries.firstOrNull { it.id == id } ?: SOFT
    }
}

/**
 * A user's cultural/linguistic profile. Use [copy] to update fields (the Kotlin equivalent
 * of Mashaaer's `set_user_context` dict update).
 */
data class CultureContext(
    val region: Region = Region.GULF,
    val toneSensitivity: ToneSensitivity = ToneSensitivity.SOFT,
    val dialect: String? = null,
    val locale: String = "ar-SA",
    val preferredLanguage: String = "ar"
)

/**
 * Pure helpers for inferring cultural context from a locale tag (e.g. the `Accept-Language`
 * header or the device locale). Ported from Mashaaer's `culture_context_engine.py`.
 */
object CultureContextEngine {

    private val LOCALE_TO_REGION: Map<String, Region> = mapOf(
        "ar-SA" to Region.GULF, "ar-AE" to Region.GULF, "ar-KW" to Region.GULF,
        "ar-QA" to Region.GULF, "ar-BH" to Region.GULF, "ar-OM" to Region.GULF,
        "ar-EG" to Region.EGYPT,
        "ar-SY" to Region.LEVANT, "ar-LB" to Region.LEVANT,
        "ar-JO" to Region.LEVANT, "ar-PS" to Region.LEVANT,
        "ar-IQ" to Region.IRAQ,
        "ar-SD" to Region.SUDAN,
        "ar-YE" to Region.YEMEN,
        "en-US" to Region.EN_GLOBAL, "en-GB" to Region.EN_GLOBAL,
        "fr-FR" to Region.FR_GLOBAL
    )

    /** Infers the cultural region from a locale tag, defaulting to the Gulf. */
    fun detectRegionFromLocale(locale: String): Region =
        LOCALE_TO_REGION[locale] ?: Region.GULF

    /** Extracts the language code from a locale tag (e.g. "ar-SA" -> "ar"). */
    fun preferredLanguageFromLocale(locale: String): String =
        locale.substringBefore("-").ifBlank { locale }
}
