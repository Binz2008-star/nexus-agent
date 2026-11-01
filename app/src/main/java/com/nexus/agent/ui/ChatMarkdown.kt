package com.nexus.agent.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer for chat bubbles. Supports the subset a model typically emits:
 * `#`/`##`/`###` headings, `**bold**`, `*italic*`, `` `inline code` ``, fenced ```` ``` ````
 * blocks, and `-`/`*`/`1.` list items. Anything unrecognized renders as plain text — never raw
 * markup symbols. Pure (no Android/network deps) apart from the Compose text types, so it is
 * unit-testable.
 */
object ChatMarkdown {

    fun render(raw: String): AnnotatedString = buildAnnotatedString {
        if (raw.isEmpty()) return@buildAnnotatedString

        var inCodeBlock = false
        for (line in raw.split('\n')) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    inCodeBlock = !inCodeBlock
                    append('\n')
                }
                inCodeBlock -> {
                    withStyle(CODE_STYLE) { append(line) }
                    append('\n')
                }
                trimmed.startsWith("### ") -> appendHeading(trimmed.removePrefix("### "), 3)
                trimmed.startsWith("## ") -> appendHeading(trimmed.removePrefix("## "), 2)
                trimmed.startsWith("# ") -> appendHeading(trimmed.removePrefix("# "), 1)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    append("•  ")
                    appendInline(trimmed.drop(2))
                    append('\n')
                }
                trimmed.matches(NUMBERED_LIST) -> {
                    appendInline(trimmed)
                    append('\n')
                }
                else -> {
                    appendInline(line)
                    append('\n')
                }
            }
        }
    }

    private fun AnnotatedString.Builder.appendHeading(text: String, level: Int) {
        val size = when (level) {
            1 -> 18.sp
            2 -> 17.sp
            else -> 16.sp
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) {
            appendInline(text)
        }
        append('\n')
    }

    /** Renders inline `**bold**`, `*italic*`, and `` `code` `` tokens; the rest stays plain. */
    private fun AnnotatedString.Builder.appendInline(text: String) {
        var last = 0
        for (match in INLINE_TOKEN.findAll(text)) {
            if (match.range.first > last) append(text.substring(last, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") -> withStyle(BOLD_STYLE) { append(token.removeSurrounding("**")) }
                token.startsWith("`") -> withStyle(CODE_STYLE) { append(token.removeSurrounding("`")) }
                else -> withStyle(ITALIC_STYLE) { append(token.removeSurrounding("*")) }
            }
            last = match.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }

    private val INLINE_TOKEN = Regex("(\\*\\*.+?\\*\\*|\\*.+?\\*|`.+?`)")
    private val NUMBERED_LIST = Regex("^\\d+\\.\\s.*")

    private val BOLD_STYLE = SpanStyle(fontWeight = FontWeight.Bold)
    private val ITALIC_STYLE = SpanStyle(fontStyle = FontStyle.Italic)
    private val CODE_STYLE = SpanStyle(fontFamily = FontFamily.Monospace)
}
