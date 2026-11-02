package com.nexus.agent.domain.tool

/** Raw Google Drive file metadata (read-only). Never includes file contents. */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val url: String? = null,
    val ownedByMe: Boolean = false
)

/**
 * A bounded, deterministic Drive search intent normalized from the user request.
 *
 * For identity lookups the model never supplies the query text: the parser derives a small,
 * fixed set of read-only `name contains` queries for the known document type, so a filename
 * cannot steer the query or smuggle instructions into it.
 */
data class DriveSearchIntent(
    val queries: List<String>,
    val identityTerms: List<String>,
    val wantsIdentityDocument: Boolean
)

object DriveSearchIntentParser {
    // Narrow on purpose: bare "بطاقة" (a card: boarding pass, bank card, …) and "الإمارات"
    // (the country) are NOT identity requests on their own.
    private val identityEn = Regex(
        "\\b(id\\s*card|emirates\\s*id|identity\\s*card|national\\s*id|my\\s*id|passport)\\b",
        RegexOption.IGNORE_CASE
    )
    private val identityAr = Regex(
        "(بطاقة الهوية|بطاقة هوية|الهوية الإماراتية|هويتي|بطاقتي|جواز|جوازي|هوية|الهوية)"
    )
    private val passportEn = Regex("\\bpassport\\b", RegexOption.IGNORE_CASE)
    private val passportAr = Regex("(جواز|جوازي)")

    // Bounded, fixed query sets (at most MAX_QUERIES searches). Never model text.
    private val idQueries = listOf("emirates id", "هوية", "بطاقة")
    private val passportQueries = listOf("passport", "جواز")
    // Ranking terms. "بطاقة الهوية" (not bare "بطاقة") avoids boosting boarding passes.
    private val idTerms = listOf("id", "هوية", "بطاقة الهوية")
    private val passportTerms = listOf("passport", "جواز")

    fun parse(message: String): DriveSearchIntent {
        val trimmed = message.trim()
        val wantsIdentity = identityEn.containsMatchIn(trimmed) || identityAr.containsMatchIn(trimmed)
        if (!wantsIdentity) {
            return DriveSearchIntent(
                queries = listOf(trimmed.take(80)).filter { it.isNotBlank() },
                identityTerms = emptyList(),
                wantsIdentityDocument = false
            )
        }
        val isPassport = passportEn.containsMatchIn(trimmed) || passportAr.containsMatchIn(trimmed)
        return DriveSearchIntent(
            queries = if (isPassport) passportQueries else idQueries,
            identityTerms = if (isPassport) passportTerms else idTerms,
            wantsIdentityDocument = true
        )
    }
}
