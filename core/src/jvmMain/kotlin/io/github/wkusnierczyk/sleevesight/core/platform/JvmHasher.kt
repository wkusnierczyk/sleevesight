package io.github.wkusnierczyk.sleevesight.core.platform

import io.github.wkusnierczyk.sleevesight.core.evidence.Hasher
import java.security.MessageDigest

/** [Hasher] on the JVM and Android, through `java.security.MessageDigest`. */
object JvmHasher : Hasher {
    override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
