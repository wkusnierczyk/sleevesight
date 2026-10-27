package app.sleevesight.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every link in the app is made in `Accents.kt` (#442), by `LinkedText` or `LinkLabel`, so none
 * takes another look or loses its line: the app's sources make no link, link style or underline
 * anywhere else, the citations' chips and the sentences they flag aside. A line struck through (as a
 * hidden source's filter chip's name was, #623, before *Sources*' table, #662) is neither, and is
 * let through.
 */
class LinkSourcesTest {
    @Test
    fun linksAreMadeOnlyInAccents() {
        val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.toList()
        assertTrue("sources found", sources.any { it.name == "Accents.kt" })
        val elsewhere = sources.filter { it.name != "Accents.kt" }.flatMap { violations(it.name, it.readText()) }
        assertEquals(emptyList<String>(), elsewhere)
    }

    @Test
    fun theCitationsExceptionsAreStillThere() {
        // An exception the citations no longer need would let a link in under its wording.
        val citations =
            File("src/main/kotlin")
                .walk()
                .single { it.name == "Citations.kt" }
                .readLines()
                .map(String::trim)
        assertEquals(emptyList<String>(), CITATIONS.filter { it !in citations })
    }

    @Test
    fun eachWayOfMakingALinkIsFound() {
        val made =
            listOf(
                "Text(buildAnnotatedString { withLink(LinkAnnotation.Url(url)) { append(host) } })",
                "pushLink(LinkAnnotation.Clickable(\"tag\") { open() })",
                "addLink(LinkAnnotation.Url(url), 0, 4)",
                "Text(AnnotatedString.fromHtml(html))",
                "ClickableText(text) { offset -> open(offset) }",
                "pushUrlAnnotation(UrlAnnotation(url))",
                "TextLinkStyles(SpanStyle(color = Color.Blue))",
                "SpanStyle(textDecoration = TextDecoration.Underline)",
                // An alias hides the name where it is used, but not where it is imported.
                "import androidx.compose.ui.text.LinkAnnotation as LA\nval link = LA.Url(url)",
                "import androidx.compose.ui.text.style.TextDecoration.Companion.Underline\n" +
                    "val style = SpanStyle(textDecoration = Underline)",
                "import androidx.compose.ui.text.withLink as linked",
                "import androidx.compose.ui.text.TextLinkStyles as Styles",
                "import androidx.compose.ui.text.fromHtml",
                "import androidx.compose.foundation.text.ClickableText as Clickable",
                // Struck through and underlined at once is still underlined.
                "TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))",
                "val style = SpanStyle(textDecoration = TextDecoration.LineThrough + TextDecoration.Underline)",
                "import androidx.compose.ui.text.style.TextDecoration as Decoration",
            )
        for (source in made) assertTrue(source, violations("Example.kt", source).isNotEmpty())
        // The citations' own exceptions do not reach another file; but for the plain import of
        // `TextDecoration`, which any file may have for a strike-through, its uses still checked.
        for (line in CITATIONS - DECORATION_IMPORT) assertTrue(line, violations("Example.kt", line).isNotEmpty())
        // Nor does one reach a citations line written another way.
        assertTrue(violations("Citations.kt", "withLink(LinkAnnotation.Url(url)) { append(label) }").isNotEmpty())
    }

    @Test
    fun linksMadeHereAreNotFound() {
        val fine =
            listOf(
                "LinkedText(listOf(TextPart.Words(\"See \"), TextPart.Link(name, url)), style)",
                "LinkLabel(TextPart.Link(shownAddress(url), url), onClickLabel = open, style = style)",
                "Text(\"Underlined in the error colour\")",
                // A struck-through line is not a link's look.
                "import androidx.compose.ui.text.style.TextDecoration",
                "textDecoration = if (shown) null else TextDecoration.LineThrough,",
            )
        for (source in fine) assertEquals(source, emptyList<String>(), violations("Example.kt", source))
        for (line in CITATIONS) assertEquals(line, emptyList<String>(), violations("Citations.kt", line))
    }

    private companion object {
        /** A name or a call that makes a link, a link's style or an underline. */
        val MADE =
            listOf(
                """\bLinkAnnotation\b""",
                """\b(withLink|pushLink|addLink|fromHtml|pushUrlAnnotation|addUrlAnnotation)\s*\(""",
                """\bClickableText\b""",
                """\bUrlAnnotation\b""",
                """\bTextLinkStyles\b""",
                """\bTextDecoration\b""",
                """\bUnderline\b""",
            ).joinToString("|").toRegex()

        /** An import of what makes one, under its own name or another. */
        val IMPORTED =
            Regex(
                """^\s*import\s+androidx\.compose\.(ui\.text\.(LinkAnnotation|withLink|TextLinkStyles|fromHtml|""" +
                    """UrlAnnotation|style\.TextDecoration)|foundation\.text\.ClickableText)\b""",
            )

        /**
         * The citations' lines that make their chips, links of their own kind styled as chips, and
         * underline the sentences they flag: these lines as they are, and no other.
         */
        val CITATIONS =
            listOf(
                "import androidx.compose.ui.text.LinkAnnotation",
                "import androidx.compose.ui.text.TextLinkStyles",
                "import androidx.compose.ui.text.style.TextDecoration",
                "import androidx.compose.ui.text.withLink",
                "val flagged = SpanStyle(textDecoration = TextDecoration.Underline, " +
                    "color = MaterialTheme.colorScheme.error)",
                "val link = LinkAnnotation.Clickable(CITE_PREFIX + id, TextLinkStyles(styles.line)) { onCite(id) }",
                "withLink(link) { append(label) }",
            )

        /** A strike-through, and the plain import of what makes it, which make no link (#623). */
        val STRUCK = Regex("""\bTextDecoration\.LineThrough\b""")
        const val DECORATION_IMPORT = "import androidx.compose.ui.text.style.TextDecoration"

        /** The lines of [source], a file named [name], that make a link, each as "name:line: text". */
        fun violations(
            name: String,
            source: String,
        ): List<String> =
            source.lines().withIndex().mapNotNull { (i, line) ->
                val checked = if (line.trim() == DECORATION_IMPORT) "" else line.replace(STRUCK, "")
                val made = MADE.containsMatchIn(checked) || IMPORTED.containsMatchIn(checked)
                val excepted = name == "Citations.kt" && line.trim() in CITATIONS
                "$name:${i + 1}: ${line.trim()}".takeIf { made && !excepted }
            }
    }
}
