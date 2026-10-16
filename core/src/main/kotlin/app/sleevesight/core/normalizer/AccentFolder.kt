package app.sleevesight.core.normalizer

/**
 * Unicode compatibility decomposition (NFKD), which accent folding needs: `é` becomes `e`
 * followed by a combining accent, which [Normalizer] then drops. Behind an interface because
 * `java.text.Normalizer` is JVM-only (D2); the JVM implementation is `JvmAccentFolder`.
 */
fun interface AccentFolder {
    /** [text] in Unicode normalization form NFKD. */
    fun decompose(text: String): String
}
