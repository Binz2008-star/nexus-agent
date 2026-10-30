package com.nexus.agent.domain.neon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeonConnectionParserTest {

    @Test
    fun parsesJdbcForm() {
        val c = NeonConnectionParser.parse(
            "jdbc:postgresql://ep-x.us-east-1.aws.neon.tech/neondb?sslmode=require&user=u&password=p"
        )
        assertEquals("ep-x.us-east-1.aws.neon.tech", c?.host)
        assertEquals("neondb", c?.database)
        assertEquals("ep-x.us-east-1.aws.neon.tech/neondb", c?.display)
    }

    @Test
    fun parsesJdbcFormWithPort() {
        val c = NeonConnectionParser.parse("jdbc:postgresql://localhost:5432/mydb?sslmode=require&user=u&password=p")
        assertEquals("localhost:5432", c?.host)
        assertEquals("mydb", c?.database)
    }

    @Test
    fun normalizesUriFormToJdbcUrl() {
        val c = NeonConnectionParser.parse("postgresql://alice:s3cret@ep-x.aws.neon.tech/neondb?sslmode=require")
        assertEquals("ep-x.aws.neon.tech", c?.host)
        assertEquals("neondb", c?.database)
        val url = c?.jdbcUrl
        assertTrue(url!!.startsWith("jdbc:postgresql://ep-x.aws.neon.tech/neondb?"))
        assertTrue(url.contains("user=alice"))
        assertTrue(url.contains("password=s3cret"))
        assertTrue(url.contains("sslmode=require"))
    }

    @Test
    fun rejectsEmpty() = assertNull(NeonConnectionParser.parse(""))

    @Test
    fun rejectsNonUrl() = assertNull(NeonConnectionParser.parse("not a url"))

    @Test
    fun rejectsMissingSslmode() =
        assertNull(NeonConnectionParser.parse("jdbc:postgresql://host/db?user=u"))

    @Test
    fun rejectsMissingDatabase() =
        assertNull(NeonConnectionParser.parse("jdbc:postgresql://host?sslmode=require"))
}
