package io.github.wkusnierczyk.sleevesight.core.platform

import io.github.wkusnierczyk.sleevesight.core.normalizer.AccentFolder
import java.text.Normalizer

/** [AccentFolder] on the JVM and Android, through `java.text.Normalizer`. */
object JvmAccentFolder : AccentFolder {
    override fun decompose(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
}
