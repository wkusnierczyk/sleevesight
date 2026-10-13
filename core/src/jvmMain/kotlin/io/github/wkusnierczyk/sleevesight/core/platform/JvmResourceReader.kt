package io.github.wkusnierczyk.sleevesight.core.platform

import io.github.wkusnierczyk.sleevesight.core.ResourceReader

/** [ResourceReader] on the JVM and Android, through the class loader that loaded `:core`. */
object JvmResourceReader : ResourceReader {
    override fun text(path: String): String? =
        JvmResourceReader::class.java.classLoader
            .getResourceAsStream(path)
            ?.use { it.readBytes().decodeToString() }
}
