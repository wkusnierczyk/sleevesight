package app.sleevesight.core.platform

import app.sleevesight.core.normalizer.AccentFolder
import java.text.Normalizer

/** [AccentFolder] on the JVM and Android, through `java.text.Normalizer`. */
object JvmAccentFolder : AccentFolder {
    override fun decompose(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
}
