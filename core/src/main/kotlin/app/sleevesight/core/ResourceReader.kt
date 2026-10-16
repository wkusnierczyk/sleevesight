package app.sleevesight.core

/**
 * Reads a data file bundled with `:core`, such as the genre vocabulary. Behind an interface
 * because reading a classpath resource is JVM-only (D2); the JVM implementation is
 * `JvmResourceReader`. A Multiplatform target would bundle the same files its own way.
 */
fun interface ResourceReader {
    /** The UTF-8 text of the resource at [path], relative to the resource root, or null when there is none. */
    fun text(path: String): String?
}
