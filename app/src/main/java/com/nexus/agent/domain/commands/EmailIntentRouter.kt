package com.nexus.agent.domain.commands

/**
 * Deterministic dispatch for Gmail-related messages.
 *
 * A service name is a request for the LOCAL connection state, never a mailbox search.
 * Explicit read/search instructions enter the existing Gmail search tool.
 * Writing/sending/drafting/questions enter the model tool loop (which enforces COMMIT approval).
 * The classifier performs no OAuth request and cannot elevate Google scopes.
 */
enum class EmailIntent { STATUS, READ, AGENT }

object EmailIntentRouter {
    private val statusWords = setOf(
        "gmail", "email", "emails", "inbox", "mail",
        "بريد", "البريد", "الايميل", "الإيميل", "ايميل", "إيميل",
        "جيميل", "جي ميل", "الجي ميل", "بريدي الالكتروني", "البريد الالكتروني"
    )
    private val englishReadVerb = Regex("^(check|read|search|show|list|find|review|summarize|fetch|scan)\\b")
    private val arabicReadVerb = Regex("^(تفقد|اقرأ|ابحث|اعرض|أظهر|افحص|استعرض|راجع|لخص|شوف|ورجيني|اطلع)(?:\\s|$)")
    private val englishWriteVerb = Regex("\\b(send|compose|draft|reply|forward|write|create|delete|archive|modify|label)\\b")
    private val arabicWriteVerb = Regex("(أرسل|ارسل|ابعت|ابعث|اكتب|صغ|جهز|رد على|أجب|حوّل|حول|احذف|امسح|أرشف|ارشِف)")

    fun classify(message: String): EmailIntent {
        val normalized = message.trim().lowercase().replace(Regex("\\s+"), " ")
            .trim('?', '؟', '!', '.', ':', '،')
            .trim()
        if (normalized in statusWords) return EmailIntent.STATUS
        // A write request must never be silently converted to a read-only search.
        if (englishWriteVerb.containsMatchIn(normalized) ||
            arabicWriteVerb.containsMatchIn(normalized)
        ) return EmailIntent.AGENT
        if (englishReadVerb.containsMatchIn(normalized) ||
            arabicReadVerb.containsMatchIn(normalized)
        ) return EmailIntent.READ
        return EmailIntent.AGENT
    }
}

/**
 * Local, evidence-based status and failure explanations.
 * Never tells the user an API call succeeded without tool success.
 */
object EmailTaskMessages {
    fun connectionStatus(readGranted: Boolean): String =
        if (readGranted) {
            "Gmail: صلاحية القراءة متصلة داخل NEXUS. " +
                "اكتب «اعرض آخر رسائلي» أو «ابحث في بريدي عن فاتورة». " +
                "الإرسال يحتاج صلاحية Gmail Send منفصلة وموافقتك قبل كل رسالة."
        } else {
            "Gmail غير متصل بصلاحية القراءة داخل NEXUS. " +
                "افتح ⚙ الإعدادات ← Google services ← Gmail · قراءة ← Connect، " +
                "وأكمل موافقة Google. بعدها اكتب «اعرض آخر رسائلي». " +
                "هذه حالة اتصال NEXUS وليست حالة تطبيق Gmail في هاتفك."
        }

    fun searchFailure(errorCode: String): String = when (errorCode) {
        "missing_scope", "no_scope", "no_token", "auth", "forbidden", "scope_not_granted" ->
            "لم أتمكن من قراءة Gmail: صلاحية الوصول غير متاحة أو انتهت. " +
                "تحقق من ربط Gmail · قراءة في إعدادات NEXUS، ثم أعد المحاولة."
        "rate" -> "توقف البحث في Gmail مؤقتًا بسبب حد طلبات Google. حاول لاحقًا."
        "network", "timeout" -> "تعذر الوصول إلى Gmail بسبب الشبكة أو انتهاء المهلة. حاول مجددًا."
        "empty" -> "لم يتم تحديد بحث صالح في Gmail."
        else -> "تعذر تنفيذ البحث في Gmail. لم أقرأ رسائلك، ولم أعتمد على إجابة عامة بديلة."
    }

    const val NO_MATCHES = "اكتمل البحث في Gmail ولم أجد رسائل مطابقة. لم أستخدم بيانات افتراضية."
}
