package com.nexus.agent.domain.neon

/**
 * A parsed Neon (Postgres) connection. [jdbcUrl] carries the credentials and must never be
 * logged; [display] is the safe, credential-free form shown in the UI.
 */
data class NeonConnection(
    val jdbcUrl: String,
    val host: String,
    val database: String
) {
    val display: String get() = "$host/$database"
}

/**
 * Parses and validates a connection string the user pastes into Settings. Accepts:
 *
 *   jdbc:postgresql://host[:port]/database?sslmode=require&user=...&password=...   (app form)
 *   postgresql://user:password@host[:port]/database?sslmode=require                (Neon console form)
 *
 * The URI form is normalized back to a JDBC URL (userinfo moved into query params) for the driver.
 * Pure — no I/O — so it is fully unit-testable. Returns null for malformed input.
 */
object NeonConnectionParser {

    private const val JDBC_SCHEME = "jdbc:postgresql://"
    private const val URI_SCHEME = "postgresql://"

    fun parse(raw: String): NeonConnection? {
        val jdbcUrl = normalize(raw.trim()) ?: return null
        val host = extractHost(jdbcUrl) ?: return null
        val database = extractDatabase(jdbcUrl) ?: return null
        return NeonConnection(jdbcUrl = jdbcUrl, host = host, database = database)
    }

    /** Normalizes either accepted form into a valid `jdbc:postgresql://` URL, or null. */
    fun normalize(raw: String): String? {
        if (raw.isEmpty()) return null
        val url = when {
            raw.startsWith(JDBC_SCHEME) -> raw
            raw.startsWith(URI_SCHEME) -> uriToJdbc(raw) ?: return null
            else -> return null
        }
        if (!hasHost(url)) return null
        if (!hasSecureSslMode(url)) return null
        return url
    }

    /**
     * Rejects `sslmode=disable|allow|prefer` so the Neon password/query traffic can never go
     * over the wire in cleartext — only `require` / `verify-ca` / `verify-full` are accepted.
     */
    private fun hasSecureSslMode(url: String): Boolean {
        val m = Regex("[?&]sslmode=([^&]+)", RegexOption.IGNORE_CASE).find(url) ?: return false
        return m.groupValues[1].trim().lowercase() in SECURE_SSL_MODES
    }

    private val SECURE_SSL_MODES = setOf("require", "verify-ca", "verify-full")

    private fun uriToJdbc(raw: String): String? {
        val rest = raw.removePrefix(URI_SCHEME) // [user[:pass]@]host[:port]/db[?query]
        val at = rest.lastIndexOf('@')
        val userInfo = if (at >= 0) rest.substring(0, at) else null
        val hostAndRest = if (at >= 0) rest.substring(at + 1) else rest
        val host = hostAndRest.substringBefore('/').substringBefore('?')
        if (host.isBlank()) return null
        val afterHost = hostAndRest.substringAfter('/', "")
        val database = afterHost.substringBefore('?')
        val query = afterHost.substringAfter('?', "")

        val params = mutableListOf<String>()
        if (query.isNotBlank()) params.add(query)
        userInfo?.let {
            val user = it.substringBefore(':')
            val password = it.substringAfter(':', "")
            if (user.isNotBlank()) params.add("user=$user")
            if (password.isNotBlank()) params.add("password=$password")
        }

        var out = "jdbc:postgresql://$host"
        if (database.isNotBlank()) out += "/$database"
        if (params.isNotEmpty()) out += "?" + params.joinToString("&")
        return out
    }

    private fun hasHost(url: String): Boolean =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').isNotBlank()

    private fun extractHost(url: String): String? =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?')
            .takeIf { it.isNotBlank() }

    private fun extractDatabase(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return null
        return afterScheme.substring(slash + 1).substringBefore('?').takeIf { it.isNotBlank() }
    }
}
