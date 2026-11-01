package com.nexus.agent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextExtractorTest {

    private val extractor = HtmlTextExtractor()

    @Test
    fun `extracts title and collapses whitespace`() {
        val html = "<html><head><title>  My Page  </title></head>" +
            "<body><h1>Heading</h1><p>First   line.\n\nSecond line.</p></body></html>"
        val result = extractor.extract(html)
        assertEquals("My Page", result.title)
        assertTrue(result.text.contains("First line."))
        assertTrue(result.text.contains("Second line."))
        assertFalse(result.text.contains("  ")) // collapsed
    }

    @Test
    fun `empty html returns empty result`() {
        val result = extractor.extract("")
        assertEquals("", result.title)
        assertEquals("", result.text)
    }

    @Test
    fun `bounded to maxChars`() {
        val html = "<html><body>" + "a".repeat(5000) + "</body></html>"
        val result = extractor.extract(html, maxChars = 100)
        assertEquals(100, result.text.length)
    }

    @Test
    fun `tolerates malformed html`() {
        val html = "<html><body><p>unclosed paragraph</body>"
        val result = extractor.extract(html)
        assertTrue(result.text.contains("unclosed paragraph"))
    }
}
