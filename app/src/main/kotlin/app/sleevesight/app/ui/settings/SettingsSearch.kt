package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// The settings' search (#649): the box under the title, what it finds, and how the rows it finds
// show.

/** A setting as the search knows it: its [title], as its row shows it, and every text it is found by. */
internal class IndexedRow(
    val title: String,
    val texts: List<String>,
)

/** A section as the search knows it: its own [texts] (its name and descriptions), and its [rows]. */
internal class IndexedSection(
    val section: SettingsSection,
    val texts: List<String>,
    val rows: List<IndexedRow>,
)

/** What a search found in a section: the whole of it ([rows] null), or only the rows of those titles. */
internal class SectionMatch(
    val rows: Set<String>?,
)

/**
 * The sections of [index] that [query] finds, in the order shown, each with what of it shows: all
 * of it when its name or a description has [query] in it, else the rows that have it in their
 * title, description, value or choices. Case-insensitive, and a typed `'` finds a `’` ([folded]);
 * empty for a blank [query].
 */
internal fun matches(
    index: List<IndexedSection>,
    query: String,
): Map<SettingsSection, SectionMatch> {
    val needle = folded(query.trim())
    if (needle.isEmpty()) return emptyMap()
    val found = LinkedHashMap<SettingsSection, SectionMatch>()
    for (indexed in index) {
        if (indexed.texts.any { folded(it).contains(needle, ignoreCase = true) }) {
            found[indexed.section] = SectionMatch(null)
            continue
        }
        val rows =
            indexed.rows
                .filter { row -> row.texts.any { folded(it).contains(needle, ignoreCase = true) } }
                .map { it.title }
                .toSet()
        if (rows.isNotEmpty()) found[indexed.section] = SectionMatch(rows)
    }
    return found
}

/**
 * A search under way, as the rows of one section see it ([LocalRowSearch]): [query], and the rows
 * that show, by title ([rows]; null for all of them). The rows' names mark where [query] is in them,
 * on [mark].
 */
internal class RowSearch(
    val query: String,
    private val rows: Set<String>?,
    private val mark: Color,
) {
    /** Whether only some rows show: what is not a row of its own (a hint, a sub-heading) does not. */
    val filtering: Boolean get() = rows != null

    /** Whether the row titled [title] shows. */
    fun shows(title: String): Boolean = rows == null || title in rows

    /** [text] with [query] marked wherever it is in it. */
    fun marked(text: String): AnnotatedString = marked(text, query, SpanStyle(background = mark))
}

/** The search under way, for the rows of the section drawn; null while not searching. */
internal val LocalRowSearch = staticCompositionLocalOf<RowSearch?> { null }

/** [text], a name, as a row or a section shows it: with the search's query marked in it, if any. */
@Composable
internal fun searchMarked(text: String): AnnotatedString = LocalRowSearch.current?.marked(text) ?: AnnotatedString(text)

/** Whether the row titled [title] shows: always, unless a search shows only other rows. */
@Composable
internal fun shownInSearch(title: String): Boolean = LocalRowSearch.current?.shows(title) ?: true

/** [text] with every place [query] is in it, whatever the case or the apostrophe ([folded]), set in [style]. */
internal fun marked(
    text: String,
    query: String,
    style: SpanStyle,
): AnnotatedString {
    val needle = folded(query.trim())
    if (needle.isEmpty()) return AnnotatedString(text)
    // Folding keeps every character's place, so the places found in it are the text's own.
    val haystack = folded(text)
    return buildAnnotatedString {
        append(text)
        var at = haystack.indexOf(needle, ignoreCase = true)
        while (at >= 0) {
            addStyle(style, at, at + needle.length)
            at = haystack.indexOf(needle, at + needle.length, ignoreCase = true)
        }
    }
}

/**
 * [text] with the typographic apostrophes and single quotes (`’`, `‘`) as the keyboard's `'`, so
 * that "can't" finds *Can’t see your player?* (#758). One character for one: every place stays put.
 */
internal fun folded(text: String): String = text.replace('’', '\'').replace('‘', '\'')

/** The colour the search marks what it found with. */
@Composable
internal fun searchMarkColour(): Color = MaterialTheme.colorScheme.tertiaryContainer

/**
 * The search box (#649), under the settings' title, outside the list: a search symbol, the hint
 * *Search settings*, and, once there is text, a button that clears it. A screen reader reads the
 * field by its hint, *Search settings*, while it is empty, and by what was typed after.
 */
@Composable
internal fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hint = stringResource(R.string.settings_search_hint)
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        // Named by its placeholder, the hint: a content description would replace what was typed
        // in what a screen reader reads.
        modifier = modifier.fillMaxWidth().testTag(SEARCH_FIELD_TAG),
        placeholder = { Text(hint) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon =
            if (query.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = { onQuery("") }, modifier = Modifier.testTag(SEARCH_CLEAR_TAG)) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.settings_search_clear),
                        )
                    }
                }
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/**
 * What a search for [query] found, said to a screen reader once the typing has paused
 * ([ANNOUNCE_AFTER]): how many sections match ([count]), or that none does. Drawn as a point, not
 * seen, above the list so it is on screen however far the list is scrolled; the screen shows what
 * it found.
 */
@Composable
internal fun SearchAnnouncement(
    query: String,
    count: Int,
) {
    val message =
        if (count == 0) {
            stringResource(R.string.settings_search_none, query.trim())
        } else {
            pluralStringResource(R.plurals.settings_search_matches, count, count)
        }
    // Drawn from the search's first keystroke, empty, so the screen reader knows the live region
    // before its first change; said only once the typing pauses, whether or not the count changed,
    // and only when what it says has changed.
    var said by remember { mutableStateOf("") }
    LaunchedEffect(query, message) {
        delay(ANNOUNCE_AFTER)
        said = message
    }
    Box(
        Modifier
            .size(1.dp)
            .testTag(SEARCH_ANNOUNCEMENT_TAG)
            .clearAndSetSemantics {
                contentDescription = said
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/** Nothing found for [query]: *No settings match "…"*. */
@Composable
internal fun NothingFound(query: String) {
    Text(
        stringResource(R.string.settings_search_none, query.trim()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp).testTag(SEARCH_NONE_TAG),
    )
}

/** How long the typing pauses before the screen reader hears what the search found. */
private val ANNOUNCE_AFTER = 700.milliseconds

/** The test tags of the search box, its clear button, what it says it found, and *No settings match*. */
internal const val SEARCH_FIELD_TAG = "settings_search"
internal const val SEARCH_CLEAR_TAG = "settings_search_clear"
internal const val SEARCH_ANNOUNCEMENT_TAG = "settings_search_announcement"
internal const val SEARCH_NONE_TAG = "settings_search_none"
