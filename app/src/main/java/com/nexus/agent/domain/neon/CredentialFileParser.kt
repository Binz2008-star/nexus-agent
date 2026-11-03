package com.nexus.agent.domain.neon

/**
 * Credentials extracted from a user-supplied file (e.g. `nexus-neon-rotation.txt`). Fields are
 * null when absent. Values are secrets — never log them.
 */
data class ImportedCredentials(
    val deepSeekKey: String? = null,
    val readUrl: String? = null,
    val writeUrl: String? = null,
    val openRouterKey: String? = null
) {
    val isEmpty: Boolean get() = deepSeekKey == null && readUrl == null && writeUrl == null && openRouterKey == null
}

/**
 * Pure parser for the credentials file the user places on their device. Extracts:
 *  - the DeepSeek key: a `sk-…` token, excluding `sk-or-…` OpenRouter keys;
 *  - the OpenRouter key: an `sk-or-…` token on a non-URL line, or a token placed *after* a
 *    connection string on a URL line. Tokens inside the URL itself (userinfo passwords,
 *    query parameters) are credentials, never API keys, so they are never imported;
 *  - the Neon read URL: a line containing a `jdbc:postgresql://`/`postgresql://` connection
 *    string whose `user=` is `nexus_reader`;
 *  - the Neon write URL: the same, with `user=` = `nexus`.
 *
 * No I/O here so it is unit-testable without a file. The parsed values are stored encrypted.
 */
object CredentialFileParser {

    private val KEY = Regex("sk-(?!or-)[a-zA-Z0-9_-]+")
    private val OPENROUTER_KEY = Regex("sk-or-[a-zA-Z0-9_-]+")
    private val USER = Regex("[?&]user=([^&#\\s]+)", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")

    fun parse(content: String): ImportedCredentials {
        var key: String? = null
        var routerKey: String? = null
        var read: String? = null
        var write: String? = null
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            val start = urlStart(line)
            if (start >= 0) {
                // The connection string may be prefixed by a label, a bullet, a quote or a BOM
                // (files edited on Windows), so it is located anywhere in the line. Everything
                // from that offset to the first whitespace is the URL; only the text *after* it
                // is scanned for keys, because a token inside the URL is a credential (userinfo
                // password or query parameter), never an API key.
                val afterStart = line.substring(start)
                val parts = WHITESPACE.split(afterStart, limit = 2)
                val urlPart = parts[0].trim()
                val trailing = parts.getOrElse(1) { "" }.trim()
                // A key appended to the line is independent of whether the URL itself parses.
                if (routerKey == null && trailing.isNotEmpty()) {
                    OPENROUTER_KEY.find(trailing)?.value?.let { candidate ->
                        if (candidate.length <= MAX_KEY_LENGTH) routerKey = candidate
                    }
                }
                // URL lines are attributed only by their `user=` role, and only when they parse
                // as a valid Neon connection (host + database + secure sslmode).
                val user = USER.find(urlPart)?.groupValues?.get(1)?.lowercase()
                if (NeonConnectionParser.parse(urlPart) == null) continue
                when (user) {
                    "nexus_reader" -> if (read == null) read = urlPart
                    "nexus" -> if (write == null) write = urlPart
                }
            } else {
                // Restrict token extraction to non-URL lines. The two families are
                // mutually exclusive so an OpenRouter key cannot replace DeepSeek.
                if (routerKey == null) {
                    OPENROUTER_KEY.find(line)?.value?.let {
                        if (it.length <= MAX_KEY_LENGTH) routerKey = it
                    }
                }
                if (key == null) {
                    KEY.find(line)?.value?.let {
                        if (it.length <= MAX_KEY_LENGTH) key = it
                    }
                }
            }
        }
        return ImportedCredentials(key, read, write, routerKey)
    }

    /**
     * Offset of the first connection string in [line], or -1. Searching anywhere (instead of
     * `startsWith`) keeps the URL branch in charge when the line is prefixed by a label, a
     * bullet, a quote or a UTF-8 BOM: otherwise the whole line would fall through to the
     * free-text branch and a password shaped like an API key could be imported as one.
     */
    private fun urlStart(line: String): Int {
        val jdbc = line.indexOf("jdbc:postgresql://")
        return if (jdbc >= 0) jdbc else line.indexOf("postgresql://")
    }

    /** Upper bound for either provider's API key token. */
    const val MAX_KEY_LENGTH = 256
}
