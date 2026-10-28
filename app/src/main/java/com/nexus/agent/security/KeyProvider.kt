package com.nexus.agent.security

import javax.crypto.SecretKey

/** Supplies the master encryption key. Implementations own key storage and lifecycle. */
interface KeyProvider {
    fun getOrCreateKey(): SecretKey
}
