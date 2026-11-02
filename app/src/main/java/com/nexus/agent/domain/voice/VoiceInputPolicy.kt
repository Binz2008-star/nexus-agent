package com.nexus.agent.domain.voice

/**
 * Pure, deterministic policies for voice input: recognition locale, platform-error mapping and
 * transcript handling. Free of Android APIs so it is unit-testable on the JVM.
 */
object VoiceInputPolicy {
    const val MAX_TRANSCRIPT_LENGTH = 2000
    const val DEFAULT_ARABIC_LOCALE = "ar-AE"

    /**
     * Map the app's stored dialect preference to a BCP-47 recognition locale.
     * "auto" keeps an Arabic/English device locale, otherwise falls back to Emirati Arabic.
     */
    fun localeForDialect(dialect: String, deviceLanguageTag: String): String = when (dialect) {
        "gulf" -> "ar-AE"
        "levant" -> "ar-SY"
        "egypt" -> "ar-EG"
        "maghreb" -> "ar-MA"
        "iraq" -> "ar-IQ"
        "sudan" -> "ar-SD"
        "yemen" -> "ar-YE"
        "en-us" -> "en-US"
        "en-uk" -> "en-GB"
        "auto" -> deviceLanguageTag.trim().takeIf {
            it.startsWith("ar", ignoreCase = true) || it.startsWith("en", ignoreCase = true)
        } ?: DEFAULT_ARABIC_LOCALE
        else -> DEFAULT_ARABIC_LOCALE
    }

    // Android SpeechRecognizer error codes (android.speech.SpeechRecognizer.ERROR_*).
    // Kept as literals so this object stays platform-free and JVM-testable.
    private const val ERROR_NETWORK_TIMEOUT = 1
    private const val ERROR_NETWORK = 2
    private const val ERROR_AUDIO = 3
    private const val ERROR_SERVER = 4
    private const val ERROR_CLIENT = 5
    private const val ERROR_SPEECH_TIMEOUT = 6
    private const val ERROR_NO_MATCH = 7
    private const val ERROR_RECOGNIZER_BUSY = 8
    private const val ERROR_INSUFFICIENT_PERMISSIONS = 9

    fun describeError(code: Int): VoiceInputError = when (code) {
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT -> VoiceInputError(
            VoiceInputErrorCode.NETWORK,
            "تعذّر الوصول إلى خدمة التعرّف على الكلام. تحقّق من الاتصال أو ثبّت حزمة تعرّف محلية."
        )
        ERROR_AUDIO -> VoiceInputError(
            VoiceInputErrorCode.AUDIO,
            "تعذّر تسجيل الصوت. أغلق التطبيقات التي تستخدم الميكروفون وحاول مجدداً."
        )
        ERROR_SERVER -> VoiceInputError(
            VoiceInputErrorCode.SERVER,
            "خدمة التعرّف على الكلام غير متاحة الآن."
        )
        ERROR_CLIENT -> VoiceInputError(
            VoiceInputErrorCode.CLIENT,
            "توقّف الإدخال الصوتي. حاول مجدداً."
        )
        ERROR_SPEECH_TIMEOUT -> VoiceInputError(
            VoiceInputErrorCode.SPEECH_TIMEOUT,
            "لم أسمع كلاماً. اضغط الميكروفون وتحدّث بوضوح."
        )
        ERROR_NO_MATCH -> VoiceInputError(
            VoiceInputErrorCode.NO_MATCH,
            "لم أفهم الكلام. أعد المحاولة بجملة أوضح."
        )
        ERROR_RECOGNIZER_BUSY -> VoiceInputError(
            VoiceInputErrorCode.BUSY,
            "المحرّك مشغول. انتظر لحظة ثم حاول مجدداً."
        )
        ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputError(
            VoiceInputErrorCode.PERMISSION_DENIED,
            "لم يُمنح إذن الميكروفون. فعّله من إعدادات التطبيق."
        )
        else -> VoiceInputError(
            VoiceInputErrorCode.UNKNOWN,
            "تعذّر الإدخال الصوتي. حاول مجدداً."
        )
    }

    /** The device has no speech recognizer at all. */
    fun unavailable(): VoiceInputError = VoiceInputError(
        VoiceInputErrorCode.NO_RECOGNIZER,
        "لا يوجد محرّك تعرّف على الكلام على هذا الجهاز. ثبّت «التعرّف على الكلام من Google» من المتجر."
    )

    fun busy(): VoiceInputError = describeError(ERROR_RECOGNIZER_BUSY)

    fun clientError(): VoiceInputError = describeError(ERROR_CLIENT)

    /** Trim, collapse whitespace and cap the length of a dictated phrase. */
    fun sanitizeTranscript(raw: String, maxLength: Int = MAX_TRANSCRIPT_LENGTH): String =
        raw.replace(Regex("\\s+"), " ").trim().take(maxLength)

    /** Append a dictated phrase to existing composer text without losing what was typed. */
    fun appendTranscript(existing: String, spoken: String): String {
        val clean = sanitizeTranscript(spoken)
        if (clean.isEmpty()) return existing
        return if (existing.isBlank()) clean else existing.trimEnd() + " " + clean
    }
}
