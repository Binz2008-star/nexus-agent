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
    fun importsBothKeysWithoutMisclassification() {
        // Distinct fake values: test a combined credential file without leaking live keys.
        val content = """
            deepseek api sk-deepseek-fake-123
            OpenRouter key sk-or-v1-router-fake-456
            jdbc:postgresql://h/db?sslmode=require&user=nexus_reader&password=x
        """.trimIndent()
        val creds = CredentialFileParser.parse(content)
        assertEquals("sk-deepseek-fake-123", creds.deepSeekKey)
        assertEquals("sk-or-v1-router-fake-456", creds.openRouterKey)
        assertEquals("jdbc:postgresql://h/db?sslmode=require&user=nexus_reader&password=x", creds.readUrl)
    }

    @Test
    fun importingOnlyOpenRouterDoesNotOverwriteDeepSeek() {
        val creds = CredentialFileParser.parse("sk-or-v1-example-fake-key")
        assertEquals("sk-or-v1-example-fake-key", creds.openRouterKey)
        assertNull(creds.deepSeekKey)
        assertNull(creds.readUrl)
        assertNull(creds.writeUrl)
        assertTrue(!creds.isEmpty)
    }

    @Test
    fun rejectsOpenRouterTokenInsideJdbcPassword() {
        val creds = CredentialFileParser.parse(
            "jdbc:postgresql://h/db?sslmode=require&user=nexus_reader&password=sk-or-v1-fake"
        )
        assertNull(creds.openRouterKey)
    }

    @Test
    fun overlyLongOpenRouterTokenIsIgnored() {
        val token = "sk-or-v1-" + "x".repeat(CredentialFileParser.MAX_KEY_LENGTH)
        assertNull(CredentialFileParser.parse(token).openRouterKey)
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
    fun importsOpenRouterKeyFromUrlLine() {
        val creds = CredentialFileParser.parse(
            "jdbc:postgresql://read-host/neondb?sslmode=require&user=nexus_reader&password=dbpass sk-or-v1-router-key"
        )
        assertEquals("sk-or-v1-router-key", creds.openRouterKey)
        assertEquals("jdbc:postgresql://read-host/neondb?sslmode=require&user=nexus_reader&password=dbpass", creds.readUrl)
    }

    @Test
    fun ignoresOpenRouterTokenEmbeddedInPasswordParam() {
        val creds = CredentialFileParser.parse(
            "jdbc:postgresql://h/db?sslmode=require&user=nexus_reader&password=sk-or-v1-fake-password"
        )
        assertNull(creds.openRouterKey)
        assertEquals("jdbc:postgresql://h/db?sslmode=require&user=nexus_reader&password=sk-or-v1-fake-password", creds.readUrl)
    }

    @Test
    fun ignoresOpenRouterShapedPasswordInUriUserinfo() {
        // A Neon URI password that happens to look like an OpenRouter key must never be
        // imported as one: it would be stored as the OpenRouter credential and sent as a bearer.
        val creds = CredentialFileParser.parse(
            "postgresql://nexus:sk-or-v1-db-secret@h/db?sslmode=require"
        )
        assertNull(creds.openRouterKey)
    }

    @Test
    fun ignoresOpenRouterShapedTokenInsideUrlQuery() {
        val creds = CredentialFileParser.parse(
            "jdbc:postgresql://h/db?sslmode=require&user=nexus&api_key=sk-or-v1-query-secret"
        )
        assertNull(creds.openRouterKey)
    }

    @Test
    fun rejectsOversizedOpenRouterToken() {
        val token = "sk-or-v1-" + "x".repeat(CredentialFileParser.MAX_KEY_LENGTH)
        assertNull(CredentialFileParser.parse(token).openRouterKey)
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
