package com.nexus.agent.domain.emotion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of the pre-network safety check. The chat flow aborts the network request and shows
 * [Blocked.localResponse] to the user whenever the analyzer returns [Blocked].
 */
sealed interface EmotionAnalysis {
    data object Passed : EmotionAnalysis

    /** The message was unsafe. [localResponse] is shown verbatim and no request is sent. */
    data class Blocked(
        val localResponse: String,
        val category: String
    ) : EmotionAnalysis
}

/**
 * A single safety rule: a category, its local (Arabic) refusal response, an optional list of
 * keywords, and optional explicit [Regex] patterns. A rule matches when either its keyword
 * regex or any of its patterns matches the normalized text.
 */
class IntentRule(
    val category: String,
    val response: String,
    keywords: List<String> = emptyList(),
    patterns: List<Regex> = emptyList()
) {
    private val keywordMatcher: Regex? = buildKeywordRegex(keywords)

    fun matches(normalized: String): Boolean {
        if (keywordMatcher?.containsMatchIn(normalized) == true) return true
        return patterns.any { it.containsMatchIn(normalized) }
    }
}

/**
 * Detects harmful requests (weapons, self-harm, threats, hacking, drugs, adult content) from
 * the user's text *before* it ever reaches the network. This is a port of the Mashaeer
 * `regex_intent_matcher.py` / `bad_keywords.json` / `intent_filter.py` logic, using Kotlin
 * [Regex] with Unicode-aware word boundaries (the Java `\b` is ASCII-only, which would wrongly
 * match Arabic sub-strings such as "سم" inside "اسم").
 *
 * Analysis runs on [Dispatchers.Default]; the network layer is never touched from here.
 */
class EmotionAnalyzer(
    private val rules: List<IntentRule> = defaultRules()
) {

    /**
     * Analyzes [text] and returns [EmotionAnalysis.Passed] when safe, or
     * [EmotionAnalysis.Blocked] with a local response to show the user.
     */
    suspend fun analyze(text: String): EmotionAnalysis = withContext(Dispatchers.Default) {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return@withContext EmotionAnalysis.Passed
        for (rule in rules) {
            if (rule.matches(normalized)) {
                return@withContext EmotionAnalysis.Blocked(rule.response, rule.category)
            }
        }
        EmotionAnalysis.Passed
    }
}

// ---- Normalization -----------------------------------------------------------

private val TASHKEEL = Regex("[\u064B-\u0652\u0670\u0640]") // diacritics, superscript alef, tatweel
private val WHITESPACE = Regex("\\s+")

/** Collapses whitespace, strips Arabic diacritics/tatweel, and lowercases ASCII. */
private fun normalize(text: String): String =
    text.replace(TASHKEEL, "")
        .replace(WHITESPACE, " ")
        .trim()
        .lowercase()

/**
 * Builds one regex matching any keyword with Unicode word boundaries on both sides, so
 * "hack" does not match "hacker" and Arabic "سم" does not match "اسم". Keywords are
 * regex-escaped and matched case-insensitively.
 */
private fun buildKeywordRegex(keywords: List<String>): Regex? {
    val parts = keywords.asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .map { keyword ->
            val escaped = Regex.escape(keyword)
            "(?<![\\p{L}\\p{N}_])(?:$escaped)(?![\\p{L}\\p{N}_])"
        }
        .distinct()
        .toList()
    if (parts.isEmpty()) return null
    return Regex(parts.joinToString("|"), RegexOption.IGNORE_CASE)
}

// ---- Rules (priority order matters: most sensitive first) ---------------------

private fun defaultRules(): List<IntentRule> = listOf(
    IntentRule(
        category = "self_harm",
        response = "أهتم لأمرك وأشعر بالقلق عليك. إذا كنت تفكر في إيذاء نفسك، من فضلك تحدث مع شخص " +
            "تثق به أو تواصل مع خط المساعدة في بلدك فورًا. أنا هنا للاستماع إليك ومساعدتك فيما أستطيع.",
        keywords = listOf(
            "انتحار", "انتحر", "أنتحر", "أقتل نفسي", "اقتل نفسي", "أذى النفس", "إيذاء النفس",
            "ايذاء النفس", "أذي نفسي", "اذي نفسي", "جرح نفسي", "أقطع نفسي", "اقطع وريدي",
            "شنق نفسي", "أنهي حياتي", "انهي حياتي", "أريد الموت", "اريد الموت", "أموت",
            "suicide", "suicidal", "kill myself", "hurt myself", "cut myself", "self harm",
            "self-harm", "end my life", "want to die", "take my own life"
        )
    ),
    IntentRule(
        category = "threat",
        response = "لا يمكنني المساعدة في تهديد أو إيذاء الآخرين. إذا كنت تمر بموقف صعب، يمكنني " +
            "مساعدتك بطريقة بنّاءة.",
        keywords = listOf(
            "سأقتلك", "سوف أقتلك", "سأدمرك", "سوف أدمرك", "سأضربك", "سأحرقك", "سأفجرك",
            "سأنتقم", "i will kill you", "i'll kill you", "i will destroy you", "i will hurt you",
            "i'm going to kill you", "i am going to kill you", "i will shoot you", "i will stab you"
        ),
        patterns = listOf(
            Regex("(?:سوف\\s|س)?[أاإآ](?:قتل|دمر|ضرب|حرق|فجر|خرب|هجم|هدد|طعن|خنق)(?:ك|ه|ها|هم)", RegexOption.IGNORE_CASE)
        )
    ),
    IntentRule(
        category = "harm",
        response = "لا يمكنني المساعدة في محتوى يتضمن عنفًا أو إيذاءً للآخرين.",
        keywords = listOf(
            "قتل", "اقتل", "يقتل", "قاتل", "قتل جماعي", "دمر", "تدمير", "هجوم", "سلاح",
            "سلاح ناري", "أسلحة", "اسلحة", "أذى", "اذى", "عنف", "تعذيب", "إيذاء", "ايذاء",
            "حرق", "تفجير", "متفجر", "قنبلة", "رصاص", "مسدس", "بندقية", "سكين", "ذبح", "نحر",
            "قصف", "طعن", "خنق", "سم", "تسميم", "اغتيال", "kill", "murder", "weapon", "bomb", "gun",
            "knife", "explosive", "poison", "torture", "massacre", "shoot", "detonate",
            "how to kill", "make a bomb", "build a bomb", "make a weapon"
        )
    ),
    IntentRule(
        category = "security_hack",
        response = "لا يمكنني المساعدة في أنشطة الاختراق أو انتهاك الخصوصية.",
        keywords = listOf(
            "اختراق", "اخترق", "أخترق", "يخترق", "هاك", "هكر", "تهكير", "تهكر", "قرصنة",
            "فايروس", "تجسس", "باسورد", "مالوير", "تروجان", "كسر الحماية", "كراك", "hack",
            "hacking", "hacker", "exploit", "phishing", "malware", "ddos", "steal password",
            "crack password", "keylogger"
        )
    ),
    IntentRule(
        category = "drugs",
        response = "لا يمكنني المساعدة في محتوى يتعلق بالمخدرات أو المواد المحظورة.",
        keywords = listOf(
            "مخدرات", "حشيش", "ماريجوانا", "كوكايين", "هيروين", "أفيون", "افيون", "مخدر",
            "مهلوسات", "ترامادول", "كبتاجون", "ليسد", "مورفين", "cocaine", "heroin",
            "methamphetamine", "meth", "lsd", "ecstasy", "weed", "marijuana", "narcotics"
        )
    ),
    IntentRule(
        category = "adult_content",
        response = "لا يمكنني المساعدة في محتوى للبالغين أو محتوى جنسي.",
        keywords = listOf(
            "اباحية", "إباحية", "عري", "دعارة", "مواقع للكبار", "افلام اباحية", "porn",
            "pornography", "xxx", "naked", "explicit", "nsfw", "adult content"
        )
    )
)
