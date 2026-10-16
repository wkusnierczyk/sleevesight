package app.sleevesight.core.platform

import app.sleevesight.core.evidence.Hasher
import java.security.MessageDigest

/** [Hasher] on the JVM and Android, through `java.security.MessageDigest`. */
object JvmHasher : Hasher {
    override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
