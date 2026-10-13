package sleevesight.checks

/**
 * The rules behind [CheckPlatformSources], over source text, so they can be tested without Gradle.
 *
 * Before matching, comments, string and character literals are blanked (newlines kept, so line
 * numbers hold), backticks are dropped, and whitespace around dots is removed, so
 * `` core.`platform` . JvmHasher `` reads as `core.platform.JvmHasher`.
 *
 * Limits: this is a text check, not a Kotlin parse. It does not see code inside string templates
 * (`"${java.net.URL(u)}"`), names reached through a type alias declared elsewhere, or reflection
 * by name; and nested block comments end at their first `*` `/`. Detekt's `ForbiddenImport` and
 * the module graph still cover the portable code; the Multiplatform switch is the complete check.
 */
object PlatformSourceRules {
    private const val ROOT = """io\.github\.wkusnierczyk\.sleevesight"""

    /** A reference to a `platform` package of this project, anywhere in a qualified name. */
    private val PLATFORM_REFERENCE = Regex("""(?<![\w.])$ROOT\.(?:\w+\.)*platform\b""")

    /** A package inside a `platform` package. */
    private val PLATFORM_PACKAGE = Regex("""^$ROOT\.(?:\w+\.)*platform(?:\.\w+)*$""")

    private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)

    private val SPACED_DOT = Regex("""\s*\.\s*""")

    /** Problems in a portable file (`src/main`): any reference to a `platform` package. */
    fun portableProblems(
        path: String,
        source: String,
    ): List<String> =
        lines(source).mapIndexedNotNull { index, line ->
            if (PLATFORM_REFERENCE.containsMatchIn(line)) "$path:${index + 1}: portable code refers to a platform package" else null
        }

    /**
     * Problems in a platform file (`src/jvmMain`): a package outside a `platform` package (portable
     * code in the same package could then call it unseen), or a qualified name starting with one of
     * [forbiddenPrefixes], such as `java.net.`, imported or written out in full.
     */
    fun platformProblems(
        path: String,
        source: String,
        forbiddenPrefixes: List<String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        val code = lines(source)
        val pkg = PACKAGE.find(code.joinToString("\n"))?.groupValues?.get(1)
        if (pkg == null || !PLATFORM_PACKAGE.matches(pkg)) {
            problems += "$path: platform code must be in a `platform` package, not ${pkg ?: "the default package"}"
        }
        val patterns = forbiddenPrefixes.map { it to Regex("""(?<![\w.])""" + Regex.escape(it)) }
        code.forEachIndexed { index, line ->
            for ((prefix, pattern) in patterns) {
                if (pattern.containsMatchIn(line)) {
                    problems += "$path:${index + 1}: platform code uses $prefix; network access goes through :net"
                }
            }
        }
        return problems
    }

    /** [source] without comments and literals, backticks or spaces around dots, as lines. */
    internal fun lines(source: String): List<String> =
        blankCommentsAndLiterals(source)
            .replace("`", "")
            .lines()
            .map { it.replace(SPACED_DOT, ".") }

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun blankCommentsAndLiterals(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0

        fun blank(until: Int) {
            while (i < until && i < source.length) {
                out.append(if (source[i] == '\n') '\n' else ' ')
                i++
            }
        }
        while (i < source.length) {
            val rest = source.substring(i, minOf(i + 3, source.length))
            when {
                rest.startsWith("//") -> blank(source.indexOf('\n', i).let { if (it < 0) source.length else it })
                rest.startsWith("/*") -> blank(source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 })
                rest == "\"\"\"" -> blank(source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 })
                rest.startsWith("\"") || rest.startsWith("'") -> blank(endOfQuoted(source, i))
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    // The index after the closing quote of the literal starting at [start], skipping escapes.
    private fun endOfQuoted(
        source: String,
        start: Int,
    ): Int {
        val quote = source[start]
        var j = start + 1
        while (j < source.length && source[j] != quote && source[j] != '\n') {
            j += if (source[j] == '\\') 2 else 1
        }
        return minOf(j + 1, source.length)
    }
}
