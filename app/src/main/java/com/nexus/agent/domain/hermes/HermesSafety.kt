package com.nexus.agent.domain.hermes

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Client-side safety rules for the Hermes connector. The *server* is a separate execution
 * environment (it runs its own tools on its own host), so these rules govern what the Android
 * client will accept and send — they are not a substitute for a read-only server profile, which
 * must be configured on the Hermes host (see docs/hermes-agent-deployment.md).
 */
object HermesSafety {

    /**
     * Validates and normalizes a user-supplied Hermes API server endpoint into a canonical
     * origin string (`scheme://host[:port]`). Rules:
     *  - a missing scheme is assumed to be `https://`;
     *  - TLS (`https`) is required for any non-loopback host, so the bearer key never travels
     *    in cleartext off-device;
     *  - plain `http` is permitted only for loopback (127.0.0.1 / localhost / ::1) local dev;
     *  - any path/query/fragment is dropped (the endpoint is host-only; route paths are added
     *    by the client).
     */
    fun normalizeEndpoint(raw: String?): Result<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return failure(ERR_EMPTY)
        if (trimmed.length > 2048) return failure(ERR_TOO_LONG)

        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return failure(ERR_MALFORMED)

        val host = url.host
        if (host.isNullOrEmpty()) return failure(ERR_MALFORMED)

        val scheme = url.scheme
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (scheme != "https" && !(scheme == "http" && isLoopback)) {
            return failure(ERR_HTTPS_REQUIRED)
        }

        return Result.success(origin(url, scheme, host))
    }

    /**
     * Reduces a user-typed endpoint to `scheme://host[:port]` for storage, stripping any userinfo
     * (`user:pass@`), path, query, or fragment. Does NOT enforce TLS (that is [normalizeEndpoint]'s
     * job at request time) — it only guarantees no credentials are persisted in plaintext prefs.
     */
    fun sanitizeForStorage(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        // Fail closed: never persist unparseable input (which may still contain userinfo) as-is.
        val url = withScheme.toHttpUrlOrNull() ?: return ""
        val host = url.host ?: return ""
        return origin(url, url.scheme, host)
    }

    private fun origin(url: HttpUrl, scheme: String, host: String): String =
        buildString {
            append(scheme).append("://").append(host)
            val defaultPort = HttpUrl.defaultPort(scheme)
            if (url.port != defaultPort) append(":").append(url.port)
        }

    /** A bearer key must be non-blank; the server itself rejects an empty key. */
    fun validateApiKey(raw: String?): Result<String> {
        val key = raw?.trim().orEmpty()
        if (key.isEmpty()) return failure(ERR_NO_KEY)
        return Result.success(key)
    }

    private fun failure(code: String): Result<String> =
        Result.failure(IllegalStateException(code))

    const val ERR_EMPTY = "hermes_endpoint_empty"
    const val ERR_TOO_LONG = "hermes_endpoint_too_long"
    const val ERR_MALFORMED = "hermes_endpoint_malformed"
    const val ERR_HTTPS_REQUIRED = "hermes_endpoint_https_required"
    const val ERR_NO_KEY = "hermes_no_key"
}
