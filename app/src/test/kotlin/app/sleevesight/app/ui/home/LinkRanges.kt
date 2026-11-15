package app.sleevesight.app.ui.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.LinkAnnotation

/**
 * The links in the text tagged [tag], in order: the words each covers, as shown (what a screen reader
 * names the link), and the address it opens. A source credit links the source's name alone, never
 * "From" (#1285).
 */
internal fun SemanticsNodeInteractionsProvider.linkedRanges(tag: String): List<Pair<String, String>> {
    val text =
        onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
    return text.getLinkAnnotations(0, text.length).map {
        text.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url
    }
}
