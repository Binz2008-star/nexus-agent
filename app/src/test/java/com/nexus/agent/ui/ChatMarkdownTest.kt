package com.nexus.agent.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {

    @Test
    fun `plain text renders unchanged`() {
        assertEquals("hello world\n", ChatMarkdown.render("hello world").text)
    }

    @Test
    fun `headings strip the hash markers`() {
        assertEquals("Title\n", ChatMarkdown.render("### Title").text)
        assertEquals("Sub\n", ChatMarkdown.render("## Sub").text)
        assertFalse(ChatMarkdown.render("### Title").text.contains("#"))
    }

    @Test
    fun `heading is bold`() {
        val r = ChatMarkdown.render("## Heading")
        assertTrue(r.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `bold strips asterisks and applies weight`() {
        val r = ChatMarkdown.render("**bold**")
        assertEquals("bold\n", r.text)
        assertFalse(r.text.contains("**"))
        assertTrue(r.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `italic applies font style`() {
        val r = ChatMarkdown.render("*italic*")
        assertEquals("italic\n", r.text)
        assertTrue(r.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `inline code applies monospace`() {
        val r = ChatMarkdown.render("`code`")
        assertEquals("code\n", r.text)
        assertTrue(r.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `bullet list renders a bullet and drops the dash`() {
        val r = ChatMarkdown.render("- item one")
        assertTrue(r.text.contains("•"))
        assertTrue(r.text.contains("item one"))
        assertFalse(r.text.contains("- item"))
    }

    @Test
    fun `numbered list is preserved`() {
        val r = ChatMarkdown.render("1. first")
        assertTrue(r.text.contains("1. first"))
    }

    @Test
    fun `fenced code block renders without backticks`() {
        val r = ChatMarkdown.render("```\nval x = 1\n```")
        assertFalse(r.text.contains("```"))
        assertTrue(r.text.contains("val x = 1"))
    }

    @Test
    fun `mixed inline is preserved without raw symbols`() {
        val r = ChatMarkdown.render("see **bold** and *italic* and `code`")
        assertTrue(r.text.contains("bold"))
        assertTrue(r.text.contains("italic"))
        assertTrue(r.text.contains("code"))
        assertFalse(r.text.contains("**"))
        assertFalse(r.text.contains("`"))
    }

    @Test
    fun `blank input is safe`() {
        assertEquals("", ChatMarkdown.render("").text)
    }
}
