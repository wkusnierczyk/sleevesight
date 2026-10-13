package io.github.wkusnierczyk.sleevesight.core.evidence

/**
 * SHA-256, for the evidence fingerprint. Behind an interface because the JVM's
 * `java.security.MessageDigest` is JVM-only (D2); the JVM implementation is `JvmHasher`. The
 * algorithm is fixed, not a parameter, so a fingerprint is the same on every platform.
 */
fun interface Hasher {
    /** The 32-byte SHA-256 digest of [bytes]. */
    fun sha256(bytes: ByteArray): ByteArray
}
