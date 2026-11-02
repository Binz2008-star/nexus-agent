package com.nexus.agent.domain.tool

/**
 * Pure, deterministic relevance ranking for Drive results.
 *
 * Fixes the reported failure where a query for "my ID card" returned programming files whose
 * name merely contained an `id-card` substring, and where a different person's identity
 * document could be presented as the user's. Code/asset files are heavily deprioritized and
 * genuine identity documents (PDF/image with an identity term in the name) are boosted.
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

    /** Best-first. Code files sink; identity documents float up when an identity doc was requested. */
    fun rank(files: List<DriveFile>, intent: DriveSearchIntent): List<DriveFile> =
        files.sortedByDescending { score(it, intent) }

    private fun score(file: DriveFile, intent: DriveSearchIntent): Int {
        val name = file.name.lowercase()
        var score = 0
        if (isCodeLike(file.name, file.mimeType)) score -= 100
        if (intent.wantsIdentityDocument) {
            if (intent.identityTerms.any { name.contains(it) }) score += 50
            if (file.mimeType.contains("pdf") || file.mimeType.startsWith("image/")) score += 20
        }
        return score
    }
}
