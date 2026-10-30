package com.nexus.agent.domain.persona

import com.nexus.agent.domain.culture.Region

/**
 * Maps a cultural [Region] to the closest supported [Dialect]. English/French regions map to
 * the American-English dialect (the default).
 */
fun regionToDialect(region: Region): Dialect = when (region) {
    Region.GULF -> Dialect.GULF
    Region.EGYPT -> Dialect.EGYPT
    Region.LEVANT -> Dialect.LEVANT
    Region.IRAQ -> Dialect.IRAQ
    Region.SUDAN -> Dialect.SUDAN
    Region.YEMEN -> Dialect.YEMEN
    Region.MAGHREB -> Dialect.MAGHREB
    Region.EN_GLOBAL -> Dialect.EN_US
    Region.FR_GLOBAL -> Dialect.EN_US
}

/**
 * Arabic dialects (plus English variants) supported for tone/dialect adaptation.
 * Ported from Mashaaer's `adaptive_tone_translator.py` (SUPPORTED_DIALECTS).
 */
enum class Dialect(val id: String, val arabicLabel: String) {
    GULF("gulf", "الخليج العربي"),
    LEVANT("levant", "بلاد الشام"),
    EGYPT("egypt", "مصر"),
    MAGHREB("maghreb", "المغرب العربي"),
    IRAQ("iraq", "العراق"),
    SUDAN("sudan", "السودان"),
    YEMEN("yemen", "اليمن"),
    EN_US("en-us", "الإنجليزية الأمريكية"),
    EN_UK("en-uk", "الإنجليزية البريطانية");

    companion object {
        fun fromId(id: String?): Dialect = entries.firstOrNull { it.id == id } ?: GULF
    }
}

/** Response tones supported for adaptation. Ported from SUPPORTED_TONES. */
enum class Tone(val id: String, val arabicLabel: String) {
    CALM("calm", "هادئة"),
    EMOTIONAL("emotional", "عاطفية"),
    ASSERTIVE("assertive", "حازمة"),
    FRIENDLY("friendly", "ودية"),
    PROFESSIONAL("professional", "مهنية");

    companion object {
        fun fromId(id: String?): Tone = entries.firstOrNull { it.id == id } ?: CALM
    }
}

/**
 * Builds the instruction used to ask the model to restate a reply in a target dialect and
 * tone (Arabic or English). This is the local, testable part of Mashaaer's
 * `rewrite_reply` — the actual model call stays elsewhere.
 */
object AdaptiveTone {

    fun buildRewriteInstruction(dialect: Dialect, tone: Tone): String =
        "تحدث باللهجة ${dialect.arabicLabel} وبأسلوب ${tone.arabicLabel} في ردودك، " +
            "مع الحفاظ على المعنى الأصلي وعدم إضافة معلومات جديدة."

    fun buildRewriteInstructionEnglish(dialect: Dialect, tone: Tone): String =
        "Respond in ${dialect.id} dialect with a ${tone.id} tone, " +
            "preserving the original meaning and not adding new information."
}
