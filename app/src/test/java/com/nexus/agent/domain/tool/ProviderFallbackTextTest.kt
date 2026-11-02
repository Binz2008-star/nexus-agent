package com.nexus.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackTextTest {
    @Test fun quotaAndMissingKeyDoNotClaimEntireAppIsDown() {
        val quota = ProviderFallbackText.unavailable("quota")
        assertTrue(quota.contains("DeepSeek"))
        assertTrue(quota.contains("Gmail"))
        assertTrue(ProviderFallbackText.unavailable("no_key").contains("مفتاح"))
        assertFalse(quota.contains("تم الإرسال"))
    }

    @Test fun rendersOnlyBoundedRealSources() {
        val sources = (1..10).map {
            ToolSource(title = "source" + it, snippet = "z".repeat(2_500))
        }
        val output = ProviderFallbackText.renderSources(sources, "Actual results")
        assertTrue(output.contains("source1"))
        assertTrue(output.contains("source8"))
        assertFalse(output.contains("source9"))
        assertTrue(output.contains("أول 8"))
        assertFalse(output.contains("z".repeat(2_001)))
    }

    @Test fun emptySourcesDoNotFabricateContent() {
        assertEquals("No results", ProviderFallbackText.renderSources(emptyList(), "No results"))
    }
}
