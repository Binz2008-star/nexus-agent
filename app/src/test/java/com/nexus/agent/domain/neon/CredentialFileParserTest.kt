package com.nexus.agent.domain.neon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialFileParserTest {

    @Test
    fun parsesFullFile() {
        val content = """
            NEXUS Neon credentials — ROTATED.

            Enter these into NEXUS Settings:

            Read (Second Brain search):
            jdbc:postgresql://read-host/neondb?sslmode=require&user=nexus_reader&password=readpass

            Write (chat & memory):
            jdbc:postgresql://write-host/neondb?sslmode=require&user=nexus&password=writepass

            deepseek api sk-fake-not-a-real-key
        """.trimIndent()
        val creds = CredentialFileParser.parse(content)
        assertEquals("sk-fake-not-a-real-key", creds.deepSeekKey)
        assertEquals(
            "jdbc:postgresql://read-host/neondb?sslmode=require&user=nexus_reader&password=readpass",
            creds.readUrl
        )
        assertEquals(
            "jdbc:postgresql://write-host/neondb?sslmode=require&user=nexus&password=writepass",
            creds.writeUrl
        )
    }

    @Test
    fun distinguishesReaderFromWriterByRole() {
        val content = """
            jdbc:postgresql://h/db?sslmode=require&user=nexus&password=x
            jdbc:postgresql://h/db?sslmode=require&user=nexus_reader&password=y
        """.trimIndent()
        val creds = CredentialFileParser.parse(content)
        assertEquals("jdbc:postgresql://h/db?sslmode=require&user=nexus&password=x", creds.writeUrl)
        assertEquals("jdbc:postgresql://h/db?sslmode=require&user=nexus_reader&password=y", creds.readUrl)
    }

    @Test
    fun uriFormWithoutUserParamIsNotAttributed() {
        // The parser attributes URLs only by their `user=` query param (the JDBC form the file
        // uses). A bare postgresql:// URI without `user=` is left unattributed.
        val creds = CredentialFileParser.parse("postgresql://nexus:wp@write-host/db?sslmode=require")
        assertNull(creds.readUrl)
        assertNull(creds.writeUrl)
    }

    @Test
    fun emptyFileYieldsEmptyCredentials() {
        val creds = CredentialFileParser.parse("")
        assertNull(creds.deepSeekKey)
        assertNull(creds.readUrl)
        assertNull(creds.writeUrl)
        assertTrue(creds.isEmpty)
    }

    @Test
    fun ignoresUnrelatedLines() {
        val creds = CredentialFileParser.parse("hello\nworld\n")
        assertTrue(creds.isEmpty)
    }

    @Test
    fun rejectsOversizedKey() {
        val longKey = "sk-" + "a".repeat(CredentialFileParser.MAX_KEY_LENGTH)
        assertNull(CredentialFileParser.parse(longKey).deepSeekKey)
    }

    @Test
    fun acceptsKeyAtMaxLength() {
        val key = "sk-" + "b".repeat(CredentialFileParser.MAX_KEY_LENGTH - 3)
        assertEquals(key, CredentialFileParser.parse(key).deepSeekKey)
    }

    @Test
    fun rejectsMalformedUrlMissingSslMode() {
        val creds = CredentialFileParser.parse(
            "jdbc:postgresql://read-host/db?user=nexus_reader&password=x"
        )
        assertNull(creds.readUrl)
    }

    @Test
    fun rejectsInsecureSslMode() {
        val creds = CredentialFileParser.parse(
            "jdbc:postgresql://read-host/db?sslmode=disable&user=nexus_reader&password=x"
        )
        assertNull(creds.readUrl)
    }

    @Test
    fun acceptsSecureSslMode() {
        val url = "jdbc:postgresql://read-host/db?sslmode=require&user=nexus_reader&password=x"
        assertEquals(url, CredentialFileParser.parse(url).readUrl)
    }
}
