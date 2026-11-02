package com.nexus.agent.data.remote

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Prevents transparent replay of Neon memory writes or transcript mirroring.
 *
 * A timeout after a successful database COMMIT is an UNKNOWN result, not proof
 * that it is safe to automatically retry. Redirects must not replay credentialed
 * POST requests or forward their bearer tokens to another endpoint.
 *
 * This is transport-level containment, NOT server-side idempotency. A durable
 * idempotency key is still required before autonomous retry of COMMIT tools.
 */
internal object NeonHttpPolicy {
    fun harden(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}
