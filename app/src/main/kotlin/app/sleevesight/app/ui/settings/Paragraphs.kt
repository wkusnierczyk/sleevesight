package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [text] in body small, a paragraph for each part a blank line starts (#758), [PARAGRAPH_GAP]
 * apart, in [color]: one text when it has no blank line.
 */
@Composable
internal fun Paragraphs(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val paragraphs = paragraphsOf(text)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PARAGRAPH_GAP)) {
        for (paragraph in paragraphs) Text(paragraph, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** [text]'s paragraphs, each trimmed, none empty. */
internal fun paragraphsOf(text: String): List<String> =
    text.split(PARAGRAPH_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

/** What separates two paragraphs in a text: a blank line. */
private val PARAGRAPH_BREAK = Regex("""\n\s*\n""")

/** The space between two paragraphs ([Paragraphs]). */
internal val PARAGRAPH_GAP: Dp = 12.dp
