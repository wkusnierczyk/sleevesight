package sleevesight.checks

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckRegexBracesTest {
    private fun bare(pattern: String) = CheckRegexBraces.bareBraces(pattern)

    private fun problems(vararg files: Pair<String, String>) = CheckRegexBraces.problemsIn(files.toList())

    @Test
    fun `a brace not escaped outside a class is found`() {
        assertEquals(listOf(4), bare("""a"\s}"""))
        assertEquals(listOf(1), bare("""a{b"""))
        assertEquals(listOf(0, 3), bare("""{a"}"""))
    }

    @Test
    fun `quantifiers, escapes and classes are accepted`() {
        listOf(
            """\d{2}""",
            """X{0,3}""",
            """a{2,}""",
            """\{\s*"x"\s*\}""",
            """\p{L}+\P{Lu}""",
            """\x{1F600}""",
            """\N{LATIN SMALL LETTER A}""",
            """[{}]""",
            """[^\]{}]""",
            """[a[{]]""",
            """\Q{}\E""",
            """\\\{""",
        ).forEach { assertEquals(emptyList(), bare(it), it) }
    }

    @Test
    fun `a brace after an escaped backslash is not escaped`() {
        assertEquals(listOf(2), bare("""\\}"""))
    }

    @Test
    fun `a brace that is not a whole quantifier is found`() {
        assertEquals(listOf(1, 4), bare("""a{,3}"""))
        assertEquals(listOf(1, 3), bare("""a{x}"""))
    }

    @Test
    fun `regexes are read where they are written`() {
        val source =
            """
            private const val NOT_ESCAPED = ${"\"\"\""}(?<!\\)(?:\\\\)*${"\"\"\""}
            private const val INNER = "[A-Z]" + "{1,2}"
            val a = Regex(${"\"\"\""}${'$'}NOT_ESCAPED"\s*}${"\"\"\""})
            val b = Regex("a" + INNER + "\\}", RegexOption.IGNORE_CASE)
            val c = "x{".toRegex()
            val d = Pattern.compile("y}")
            val e = listOf("\\[(?:Music)]", "z{" + "w").map { Regex(it) }
            val f = Regex(Regex.escape(key) + "\\d{2}")
            val g = Regex("{literal}", RegexOption.LITERAL)
            val h = listOf("{not a regex}").map { it.length }
            // Regex("in a comment}")
            val i = "Regex(\"in a string}\")"
            """.trimIndent()
        assertEquals(
            listOf(
                3 to """(?<!\\)(?:\\\\)*"\s*}""",
                4 to """a[A-Z]{1,2}\}""",
                5 to "x{",
                6 to "y}",
                7 to """\[(?:Music)]""",
                7 to "z{w",
                8 to """\d{2}""",
            ),
            CheckRegexBraces.regexesInFile(source),
        )
    }

    @Test
    fun `a constant from another file is resolved`() {
        val found =
            problems(
                "A.kt" to "internal const val CLOSE = \"}\"",
                "B.kt" to "val b = Regex(\"a\$CLOSE\")",
            )
        assertEquals(listOf("B.kt:1: a}"), found)
    }

    @Test
    fun `a raw string's closing quotes keep the ones before them`() {
        val source = "val s = Regex(" + "\"\"\"" + "\\{\\s*" + "\"\"\"\"" + ")"
        assertEquals(listOf(1 to """\{\s*""""), CheckRegexBraces.regexesInFile(source))
    }

    @Test
    fun `a brace in a string inside a template is not the template's end`() {
        val q = "\"\"\""
        // `Regex("""${Regex.escape("{")}""")`: the `{` in the template's string opens nothing, so the
        // regex after it is still read.
        val opening = "val a = Regex($q\${Regex.escape(\"{\")}$q)\nval b = Regex(\"later}\")"
        assertEquals(listOf("A.kt:2: later}"), problems("A.kt" to opening))
        // With `"}"`, the template does not end early and leave `")}` as the regex.
        val closing = "val a = Regex($q\${Regex.escape(\"}\")}$q)"
        assertEquals(emptyList(), problems("A.kt" to closing))
        // A character and a comment in a template, too.
        val other = "val a = Regex(\"\${listOf('{', /* } */ 'x').size}a\")\nval b = Regex(\"c}\")"
        assertEquals(listOf("A.kt:2: c}"), problems("A.kt" to other))
    }

    @Test
    fun `a literal toRegex is not read`() {
        val source =
            """
            val a = "}".toRegex(RegexOption.LITERAL)
            val b = "}".toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.LITERAL))
            val c = "{".toRegex(RegexOption.IGNORE_CASE)
            val d = "{".toRegex(setOf(RegexOption.IGNORE_CASE))
            """.trimIndent()
        assertEquals(listOf(3 to "{", 4 to "{"), CheckRegexBraces.regexesInFile(source))
    }
}
