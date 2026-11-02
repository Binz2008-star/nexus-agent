package com.nexus.agent.data.remote

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Gmail send is a COMMIT action: a network replay can create duplicate mail after an
 * ambiguous timeout. Harden *every* supplied client, including an injected one.
 *
 * No transparent connection-failure retry or redirect of a credentialed POST.
 * A failed/unknown response must be reconciled with Gmail Sent before any new
 * manually approved send. This does not provide server-side idempotency.
 */
internal object GmailSendHttpPolicy {
    fun harden(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
}
