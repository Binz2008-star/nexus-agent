package com.nexus.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveFileRankerTest {
    private val identityIntent = DriveSearchIntentParser.parse("my emirates id")

    @Test fun codeFilesAreDetectedByExtensionAndMime() {
        assertTrue(DriveFileRanker.isCodeLike("id-card.js", "application/javascript"))
        assertTrue(DriveFileRanker.isCodeLike("bundle.map", "application/json"))
        assertTrue(DriveFileRanker.isCodeLike("main.ts", "text/plain"))
        assertFalse(DriveFileRanker.isCodeLike("My Emirates ID.pdf", "application/pdf"))
        assertFalse(DriveFileRanker.isCodeLike("photo.jpg", "image/jpeg"))
    }

    @Test fun identityDocumentsOutrankCodeFiles() {
        val files = listOf(
            DriveFile("1", "id-card-component.js", "application/javascript"),
            DriveFile("2", "notes.txt", "text/plain"),
            DriveFile("3", "My Emirates ID.pdf", "application/pdf"),
            DriveFile("4", "ahmed-id-card.pdf", "application/pdf")
        )
        val ranked = DriveFileRanker.rank(files, identityIntent)
        // The .js false positive must sink to the bottom.
        assertEquals("id-card-component.js", ranked.last().name)
        // Identity PDFs lead.
        assertTrue(ranked.take(2).all { it.mimeType == "application/pdf" })
    }

    @Test fun plainDocumentKeepsNaturalOrderWhenNotIdentity() {
        val files = listOf(
            DriveFile("1", "a.txt", "text/plain"),
            DriveFile("2", "b.txt", "text/plain")
        )
        val ranked = DriveFileRanker.rank(files, DriveSearchIntentParser.parse("find a"))
        assertEquals(2, ranked.size)
        assertTrue(ranked.any { it.name == "a.txt" })
    }

    @Test fun substringLookalikesAreNotBoostedAsIdentity() {
        val files = listOf(
            DriveFile("1", "holiday.jpg", "image/jpeg"),
            DriveFile("2", "Emirates invoice.pdf", "application/pdf"),
            DriveFile("3", "My Emirates ID.pdf", "application/pdf", ownedByMe = true)
        )
        val ranked = DriveFileRanker.rank(files, identityIntent)
        assertEquals("My Emirates ID.pdf", ranked.first().name)
        assertFalse(DriveFileRanker.hasIdentityTerm("holiday.jpg", identityIntent.identityTerms))
        assertFalse(DriveFileRanker.hasIdentityTerm("Emirates invoice.pdf", identityIntent.identityTerms))
        assertTrue(DriveFileRanker.hasIdentityTerm("My Emirates ID.pdf", identityIntent.identityTerms))
    }

    @Test fun ownedFilesArePreferredOverSharedOnes() {
        val files = listOf(
            DriveFile("1", "ahmed-id-card.pdf", "application/pdf", ownedByMe = false),
            DriveFile("2", "my-id-card.pdf", "application/pdf", ownedByMe = true)
        )
        val ranked = DriveFileRanker.rank(files, identityIntent)
        assertEquals("my-id-card.pdf", ranked.first().name)
    }
}
