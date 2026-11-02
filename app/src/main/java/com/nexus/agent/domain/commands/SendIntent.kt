package com.nexus.agent.domain.commands

/**
 * Trusted, deterministic classification of a user message's EMAIL intent.
 *
 * This is computed by caller code from the raw user text only — never from a model response or
 * retrieved content, so a model cannot redefine "write" as "send" to skip the COMMIT approval gate.
 *
 * Only messages that are genuinely email-related (an email noun or a literal address) are
 * classified; a generic "send me the file" is [NONE] and does not force an action contract.
 */
enum class SendIntent { SEND, WRITE, NONE }

object SendIntentClassifier {
    private val emailNounEn = Regex("\\b(email|e-mail|mail|gmail)\\b", RegexOption.IGNORE_CASE)
    private val emailNounAr = Regex("(رسالة|رساله|بريد|ايميل|إيميل|الإيميل|البريد|جيميل|بريدًا|بريدا)")
    private val emailAddress = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    private val sendEn = Regex("\\b(send|deliver|dispatch|forward|fire off)\\b", RegexOption.IGNORE_CASE)
    private val sendAr = Regex("(أرسل|ارسل|ابعت|ابعث|أوصل|اوصل|حوّل)")
    private val writeEn = Regex("\\b(compose|draft|write|prepare|type|author|reply|respond|create)\\b", RegexOption.IGNORE_CASE)
    private val writeAr = Regex("(اكتب|أكتب|صغ|أصغ|جهّز|جهز|أعد|اعد|حضّر|حضر|ألّف|الف|دوّن|دون|رد)")

    fun classify(message: String): SendIntent {
        val normalized = message.trim().lowercase()
        val emailContext = emailNounEn.containsMatchIn(normalized) ||
            emailNounAr.containsMatchIn(normalized) || emailAddress.containsMatchIn(message)
        if (!emailContext) return SendIntent.NONE
        // A send verb always wins over a write verb ("send the draft" / "forward this email").
        if (sendEn.containsMatchIn(normalized) || sendAr.containsMatchIn(normalized)) return SendIntent.SEND
        if (writeEn.containsMatchIn(normalized) || writeAr.containsMatchIn(normalized)) return SendIntent.WRITE
        return SendIntent.NONE
    }
}
