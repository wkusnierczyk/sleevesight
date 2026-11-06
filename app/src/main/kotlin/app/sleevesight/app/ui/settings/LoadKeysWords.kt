package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.keys.KeyWords

// *Load keys*' ① (#590, #765): the five words, and the time they have left.

/**
 * ① *Encryption words* (#765): what the words are for, and to give them to the page; then the
 * [words], centred in the space left as one group, each on a line of its own, large, its first
 * three letters bold, which are enough to type it, the rest in the quiet colour; then the time they
 * have [left], in milliseconds, counting down as m:ss ([countdown]), that new ones are drawn when it
 * is up, and that the phone forgets them when the screen is left. *New words* calls [onNewWords],
 * *Next* [onNext], and *Cancel* [onCancel].
 */
@Composable
internal fun ColumnScope.WordsStep(
    words: List<String>,
    left: Long,
    onNewWords: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    val style = MaterialTheme.typography.bodyMedium
    FilledMiddle(
        least = null,
        above = {
            Text(stringResource(R.string.load_keys_words_secret), style = style)
            Text(stringResource(R.string.load_keys_words_give), style = style)
        },
        middle = {
            Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val quiet = MaterialTheme.colorScheme.onSurfaceVariant
                for (word in words) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(word.take(KeyWords.PREFIX)) }
                            withStyle(SpanStyle(color = quiet)) { append(word.drop(KeyWords.PREFIX)) }
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.testTag(LOAD_KEYS_WORD_TAG),
                    )
                }
            }
        },
        below = {
            Text(timeLeft(left), style = style)
            Text(stringResource(R.string.load_keys_words_next), style = style)
        },
    )
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        TextButton(onClick = onNewWords) { Text(stringResource(R.string.load_keys_new_words)) }
        TextButton(onClick = onNext) { Text(stringResource(R.string.load_keys_next)) }
    }
}

/**
 * The words' time [left], in milliseconds, in the sentence that says it, bold, in figures of one
 * width, so it does not shift as it counts down; and, on a line of its own, that the phone forgets
 * the words when the screen is left.
 */
@Composable
private fun timeLeft(left: Long): AnnotatedString {
    val text = stringResource(R.string.load_keys_words_within, TIME_MARK)
    val at = text.indexOf(TIME_MARK)
    return buildAnnotatedString {
        append(text, 0, at)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")) { append(countdown(left)) }
        append(text, at + TIME_MARK.length, text.length)
        append("\n")
        append(stringResource(R.string.load_keys_words_forget))
    }
}

/** [left] milliseconds as m:ss, rounded up, so it shows 0:00 only once the time is up: "4:52". */
internal fun countdown(left: Long): String {
    val seconds = (left.coerceAtLeast(0) + MS_PER_SECOND - 1) / MS_PER_SECOND
    return "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

/** Where [timeLeft] puts the time: a character no translation has. */
private const val TIME_MARK = "￼"

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60
