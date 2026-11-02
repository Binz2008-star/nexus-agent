package com.nexus.agent.domain.tool

/**
 * Pure, deterministic relevance ranking for Drive results.
 *
 * Fixes the reported failure where a query for "my ID card" returned programming files whose
 * name merely contained an `id-card` substring, and where a different person's identity
 * document could be presented as the user's. Code/asset files are heavily deprioritized,
 * genuine identity documents (identity term + PDF/image) are boosted, and files the user owns
 * are preferred over shared/other-owner files.
 */
object DriveFileRanker {
    private val codeExtensions = listOf(
        ".js", ".mjs", ".cjs", ".map", ".ts", ".tsx", ".jsx", ".py", ".java", ".kt",
        ".css", ".scss", ".html", ".htm", ".json", ".xml", ".sh", ".gradle"
    )

    fun isCodeLike(name: String, mimeType: String): Boolean {
        val lower = name.lowercase()
        val mime = mimeType.lowercase()
        return codeExtensions.any { lower.endsWith(it) } ||
            mime.contains("javascript") || mime.contains("ecmascript") ||
            mime.contains("json") || mime.contains("x-python") || mime.contains("x-sh")
    }

    /**
     * Token-safe identity-term match: a short term like "id" must be a whole token (so
     * "holiday.jpg" is not mistaken for an ID), while longer Arabic terms match as substrings.
     */
    fun hasIdentityTerm(name: String, terms: List<String>): Boolean {
        val lower = name.lowercase()
        val tokens = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return terms.any { term ->
            val t = term.lowercase()
            if (t.length <= 3) tokens.any { it == t } else lower.contains(t)
        }
    }

    /** Best-first. Code files sink; owned identity documents float up. */
    fun rank(files: List<DriveFile>, intent: DriveSearchIntent): List<DriveFile> =
        files.sortedByDescending { score(it, intent) }

    private fun score(file: DriveFile, intent: DriveSearchIntent): Int {
        var score = 0
        if (isCodeLike(file.name, file.mimeType)) score -= 100
        if (intent.wantsIdentityDocument) {
            val documentLike = file.mimeType.contains("pdf") || file.mimeType.startsWith("image/")
            if (documentLike && hasIdentityTerm(file.name, intent.identityTerms)) score += 50
            if (documentLike) score += 10
            // Prefer the user's own files over shared/other-owner files (never a hard filter).
            if (file.ownedByMe) score += 40
        }
        return score
    }
}
