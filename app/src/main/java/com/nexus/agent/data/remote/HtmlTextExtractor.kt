package com.nexus.agent.data.remote

import org.jsoup.Jsoup

/** The extracted title + readable text from an HTML document. */
data class ExtractedText(val title: String, val text: String)

/**
 * Pure HTML → readable-text extraction using jsoup. Returns the document title and the collapsed
 * visible body text, bounded to [maxChars]. No network access — operates on an in-memory string,
 * so it is unit-testable against canned HTML.
 */
class HtmlTextExtractor {

    fun extract(html: String, maxChars: Int = DEFAULT_MAX_CHARS): ExtractedText {
        if (html.isBlank()) return ExtractedText("", "")
        val doc = Jsoup.parse(html)
        val title = doc.title().trim()
        val text = doc.body()?.text()?.let(::collapseWhitespace).orEmpty()
        return ExtractedText(title, text.take(maxChars))
    }

    private fun collapseWhitespace(s: String): String =
        s.replace(Regex("\\s+"), " ").trim()

    companion object {
        const val DEFAULT_MAX_CHARS = 12_000
    }
}
