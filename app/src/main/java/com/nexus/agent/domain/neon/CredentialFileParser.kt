package com.nexus.agent.domain.neon

/**
 * Credentials extracted from a user-supplied file (e.g. `nexus-neon-rotation.txt`). Fields are
 * null when absent. Values are secrets — never log them.
 */
data class ImportedCredentials(
    val deepSeekKey: String? = null,
    val readUrl: String? = null,
    val writeUrl: String? = null
) {
    val isEmpty: Boolean get() = deepSeekKey == null && readUrl == null && writeUrl == null
}

/**
 * Pure parser for the credentials file the user places on their device. Extracts:
 *  - the DeepSeek key: a `sk-…` token anywhere on a line;
 *  - the Neon read URL: a `jdbc:postgresql://`/`postgresql://` line whose `user=` is `nexus_reader`;
 *  - the Neon write URL: the same, with `user=` = `nexus`.
 *
 * No I/O here so it is unit-testable without a file. The parsed values are stored encrypted.
 */
object CredentialFileParser {

    private val KEY = Regex("sk-[a-zA-Z0-9_-]+")
    private val USER = Regex("[?&]user=([^&#\\s]+)", RegexOption.IGNORE_CASE)

    fun parse(content: String): ImportedCredentials {
        var key: String? = null
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
            } else if (key == null) {
                // Scan only non-URL lines for the key, so a `sk-…` substring inside a URL
                // (e.g. a password) can never be mis-imported as the DeepSeek key.
                KEY.find(line)?.value?.let { if (it.length <= MAX_KEY_LENGTH) key = it }
            }
        }
        return ImportedCredentials(key, read, write)
    }

    private fun isUrl(line: String): Boolean =
        line.startsWith("jdbc:postgresql://") || line.startsWith("postgresql://")

    /** Upper bound for an accepted DeepSeek key token. */
    const val MAX_KEY_LENGTH = 256
}
