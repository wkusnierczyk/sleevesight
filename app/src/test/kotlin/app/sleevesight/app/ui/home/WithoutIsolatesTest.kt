package app.sleevesight.app.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [withoutIsolates] (#1350): the chips' text without their isolates, each chip's link, outline
 * annotation and look still over exactly that chip's id between its thin spaces.
 */
class WithoutIsolatesTest {
    private val styles =
        CiteStyles(
            line = SpanStyle(background = Color.Blue),
            model = SpanStyle(background = Color.Green),
            unknown = SpanStyle(background = Color.Red),
            unsupported = SpanStyle(color = Color.Gray),
        )

    private val drawn =
        buildAnnotatedString {
            append("It topped the charts")
            appendCite("S1", "S1", CiteKind.LINE, styles, {}, unsupported = true)
            append(". It was a hit")
            appendCite("S2", "S2", CiteKind.LINE, styles, {})
            append(".")
        }

    private val copy = withoutIsolates(drawn)

    @Test
    fun theTextLosesOnlyItsIsolates() {
        assertFalse(copy.text, copy.text.any { it == '⁨' || it == '⁩' })
        assertEquals("It topped the charts S1 . It was a hit S2 .", copy.text)
    }

    @Test
    fun eachLinkCoversItsChip() {
        val links = copy.getLinkAnnotations(0, copy.length)
        assertEquals(listOf(" S1 ", " S2 "), links.map { copy.text.substring(it.start, it.end) })
        assertEquals(
            listOf(CITE_PREFIX + "S1", CITE_PREFIX + "S2"),
            links.map { (it.item as LinkAnnotation.Clickable).tag },
        )
    }

    @Test
    fun theOutlineCoversTheUnsupportedChip() {
        val outlined = copy.getStringAnnotations(OUTLINE_TAG, 0, copy.length).single()
        assertEquals("S1", outlined.item)
        assertEquals(" S1 ", copy.text.substring(outlined.start, outlined.end))
    }

    @Test
    fun eachChipsLookCoversItsChip() {
        val looks = copy.spanStyles.map { copy.text.substring(it.start, it.end) to it.item }
        assertEquals(listOf(" S1 " to styles.unsupported, " S2 " to styles.line), looks)
    }
}
