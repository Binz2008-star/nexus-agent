package com.nexus.agent.domain.tool

/** Raw Google Drive file metadata (read-only). Never includes file contents. */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val url: String? = null
)

/**
 * A bounded, deterministic Drive search intent normalized from the user request.
 *
 * The model never supplies arbitrary query text: for a known document type (e.g. an identity
 * card) the parser derives a small, fixed set of read-only `name contains` queries, so a
 * filename cannot be used to steer the query or to smuggle instructions into it.
 */
data class DriveSearchIntent(
    val queries: List<String>,
    val identityTerms: List<String>,
    val wantsIdentityDocument: Boolean
)

object DriveSearchIntentParser {
    private val identityEn = Regex(
        "\\b(id\\s*card|emirates\\s*id|identity\\s*card|national\\s*id|my\\s*id|passport)\\b",
        RegexOption.IGNORE_CASE
    )
    private val identityAr = Regex("(بطاقة|بطاقتي|هوية|الهوية|هويتي|جواز|جوازي|الإمارات|الامارات)")

    // Bounded, fixed query set for identity documents (at most MAX_QUERIES searches).
    private val identityQueries = listOf("emirates id", "هوية", "بطاقة")
    private val identityTerms = listOf("id", "هوية", "بطاقة", "emirates", "passport", "جواز")

    fun parse(message: String): DriveSearchIntent {
        val trimmed = message.trim()
        val wantsIdentity = identityEn.containsMatchIn(trimmed) || identityAr.containsMatchIn(trimmed)
        return if (wantsIdentity) {
            DriveSearchIntent(identityQueries, identityTerms, wantsIdentityDocument = true)
        } else {
            DriveSearchIntent(
                queries = listOf(trimmed.take(80)).filter { it.isNotBlank() },
                identityTerms = emptyList(),
                wantsIdentityDocument = false
            )
        }
    }
}
