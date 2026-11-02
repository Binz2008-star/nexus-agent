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
 *  - the OpenRouter key: an `sk-or-…` token on any line (URL or not), unless it is embedded
 *    inside a JDBC `password=` parameter;
 *  - the Neon read URL: a `jdbc:postgresql://`/`postgresql://` line whose `user=` is `nexus_reader`;
 *  - the Neon write URL: the same, with `user=` = `nexus`.
 *
 * No I/O here so it is unit-testable without a file. The parsed values are stored encrypted.
 */
object CredentialFileParser {

    private val KEY = Regex("sk-(?!or-)[a-zA-Z0-9_-]+")
    private val OPENROUTER_KEY = Regex("sk-or-[a-zA-Z0-9_-]+")
    private val USER = Regex("[?&]user=([^&#\\s]+)", RegexOption.IGNORE_CASE)

    fun parse(content: String): ImportedCredentials {
        var key: String? = null
        var routerKey: String? = null
        var read: String? = null
        var write: String? = null
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (isUrl(line)) {
                // URL lines are attributed only by their `user=` role, and only when they parse
                // as a valid Neon connection (host + database + secure sslmode).
                val user = USER.find(line)?.groupValues?.get(1)?.lowercase()
                if (NeonConnectionParser.parse(line) == null) continue
                when (user) {
                    "nexus_reader" -> if (read == null) read = line
                    "nexus" -> if (write == null) write = line
                }
                // Also scan URL lines for an OpenRouter key. An OpenRouter token embedded in a
                // `password=` parameter is not attributed (it is a DB password, not an API key).
                if (routerKey == null) {
                    OPENROUTER_KEY.find(line)?.value?.let { candidate ->
                        if (candidate.length <= MAX_KEY_LENGTH && !isEmbeddedInPasswordParam(line, candidate)) {
                            routerKey = candidate
                        }
                    }
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

    private fun isUrl(line: String): Boolean =
        line.startsWith("jdbc:postgresql://") || line.startsWith("postgresql://")

    /**
     * Returns true if [candidate] occurs in [line] immediately after `password=`, i.e. it is a
     * database password value, not an OpenRouter API key.
     */
    private fun isEmbeddedInPasswordParam(line: String, candidate: String): Boolean {
        var start = 0
        while (true) {
            val idx = line.indexOf(candidate, start)
            if (idx < 0) return false
            val preceding = line.substring(0, idx)
            if (preceding.endsWith("password=")) return true
            start = idx + candidate.length
        }
    }

    /** Upper bound for either provider's API key token. */
    const val MAX_KEY_LENGTH = 256
}
