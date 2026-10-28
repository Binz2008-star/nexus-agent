package com.nexus.agent.security

/** Base class for all NEXUS crypto failures. Callers catch this, never raw JCE internals. */
open class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The ciphertext is malformed or uses an unsupported version. */
class InvalidCiphertextException(message: String) : CryptoException(message)

/** The ciphertext failed GCM authentication (tampered, truncated, or wrong key). */
class TamperDetectedException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

/** The keystore key is missing, unavailable, or has been invalidated. */
class KeyUnavailableException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
