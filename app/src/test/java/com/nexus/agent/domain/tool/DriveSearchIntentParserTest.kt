package com.nexus.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSearchIntentParserTest {
    @Test fun identityRequestsAreNormalizedToBoundedQueries() {
        listOf(
            "find my ID card", "where is my Emirates ID",
            "ابحث عن بطاقتي", "الهوية", "هويتي"
        ).forEach {
            val intent = DriveSearchIntentParser.parse(it)
            assertTrue("expected identity intent for: $it", intent.wantsIdentityDocument)
            assertEquals(it, "emirates id", intent.queries.first())
            assertTrue(intent.queries.size <= 3)
            assertTrue(intent.identityTerms.isNotEmpty())
        }
    }

    @Test fun passportRequestsUsePassportQueries() {
        listOf("my passport", "جوازي", "find my passport scan").forEach {
            val intent = DriveSearchIntentParser.parse(it)
            assertTrue(it, intent.wantsIdentityDocument)
            assertEquals(it, "passport", intent.queries.first())
        }
    }

    @Test fun genericCardAndCountryWordsAreNotIdentityRequests() {
        listOf("ابحث عن بطاقة الصعود", "فواتير الإمارات", "find my boarding pass").forEach {
            assertFalse(it, DriveSearchIntentParser.parse(it).wantsIdentityDocument)
        }
    }

    @Test fun ordinaryDocumentRequestsStaySingleQuery() {
        listOf("find the invoice", "report", "ابحث عن فاتورة").forEach {
            val intent = DriveSearchIntentParser.parse(it)
            assertFalse(it, intent.wantsIdentityDocument)
            assertEquals(it, 1, intent.queries.size)
            assertTrue(intent.identityTerms.isEmpty())
        }
    }

    @Test fun identityQueriesAreFixedAndNotModelSupplied() {
        val intent = DriveSearchIntentParser.parse("my id card please")
        assertEquals(listOf("emirates id", "هوية", "بطاقة"), intent.queries)
    }
}
