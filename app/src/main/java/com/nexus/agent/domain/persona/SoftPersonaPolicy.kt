package com.nexus.agent.domain.persona

/**
 * Mashaaer "Good Mode" (الوضع الحنون) — an opt-in, persisted communication-style overlay.
 *
 * This is NOT a new LLM, a therapy feature, or an unrestricted agent role. It only layers a
 * calm/warm/respectful phrasing instruction on top of the existing dialect/tone persona. It never
 * changes facts, safety policy, permissions, scope checks, tool selection, tool arguments, or
 * approval gates, and user text is never copied into system instructions.
 */
object SoftPersonaPolicy {

    sealed interface Command {
        data object Enable : Command
        data object Disable : Command
    }

    // Exact bilingual activation/deactivation phrases. Normalized (diacritics/hamza/case/space)
    // before comparison so common typing variations still match.
    private val enablePhrases = listOf(
        "الوضع الحنون تفعيل",
        "فعّل الوضع الناعم",
        "فعل الوضع الناعم",
        "شغل الوضع الناعم",
        "enable soft mode",
    )
    private val disablePhrases = listOf(
        "الوضع الحنون تعطيل",
        "أوقف الوضع الناعم",
        "اوقف الوضع الناعم",
        "عطل الوضع الناعم",
        "disable soft mode",
    )

    fun detectCommand(message: String): Command? {
        val normalized = normalize(message)
        if (normalized.isEmpty()) return null
        if (enablePhrases.any { normalize(it) == normalized }) return Command.Enable
        if (disablePhrases.any { normalize(it) == normalized }) return Command.Disable
        return null
    }

    /** Strips diacritics/hamza variants, normalizes taa-marbuta, lowercases, collapses spaces. */
    private fun normalize(input: String): String =
        input.lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Soft-mode style instruction (Arabic). Constant vocabulary — never embeds user text. */
    fun buildStyleInstruction(dialect: Dialect, tone: Tone): String =
        "أجب بأسلوب حنون وهادئ ودود ومحترم وموجز. خاطب المستخدم باحترام دون ألقاب مصطنعة أو " +
            "تملّق أو ادّعاء رابطة حصرية أو نصائح علاجية أو يقين عاطفي مفترى. حافظ على لهجة " +
            "${dialect.arabicLabel} وأسلوب ${tone.arabicLabel}. لا تغيّر أبدًا الحقائق أو " +
            "السلامة أو الأدوات أو الموافقات بسبب هذا الأسلوب."

    /** Soft-mode style instruction (English). */
    fun buildStyleInstructionEnglish(dialect: Dialect, tone: Tone): String =
        "Respond in a gentle, calm, warm, respectful, and concise tone. Address the user " +
            "respectfully with no manufactured pet names, flattery, implied exclusive human bond, " +
            "therapy claims, or fabricated emotional certainty. Keep the ${dialect.id} dialect and " +
            "${tone.id} tone. Never change facts, safety, tools, or approvals because of this style."
}
