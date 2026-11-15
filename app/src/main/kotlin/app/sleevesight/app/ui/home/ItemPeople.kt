package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.pipeline.ArtistSplit
import app.sleevesight.core.pipeline.AuthorsName
import app.sleevesight.core.pipeline.InsightItem

// The other people of a track whose roles tell the composer apart from the performers (#1199): in
// the artist's panel, the other performers; in the Authors panel, who wrote the music and who wrote
// the words, each a chip opening that artist's own insight in place, written on the tap; and under
// the title, the composer's line of its own.

/**
 * What [item]'s panel (or page) shows above its insight, by [split] (#1199): in the Authors panel,
 * *Music*, the composer's insight being about the music; nothing otherwise.
 */
@Composable
internal fun PeopleBefore(
    item: InsightItem,
    split: ArtistSplit?,
) {
    if (item == InsightItem.COMPOSER && split?.name == AuthorsName.AUTHORS) {
        PeopleHeading(stringResource(R.string.home_item_music))
    }
}

/**
 * What [item]'s panel (or page) shows under its insight, by [split] (#1199): in the artist's panel,
 * *Also performing on this recording* and a chip for each other performer; in the Authors panel, a
 * chip for each other composer, then *Words* and a chip for each who wrote the words. A chip opens
 * that artist's insight ([InsightItem.named]) in place under the chips, one at a time, in a panel of
 * its own with *Close*; the tap is its consent, so it is written at once, in every mode (#948). The
 * one open is kept while [context]'s track plays, and the focus goes to its *Close* when it opens and
 * back to its chip when it closes.
 */
@Composable
internal fun PeopleAfter(
    item: InsightItem,
    split: ArtistSplit?,
    context: ItemContext,
) {
    if (split == null) return
    val groups =
        when (item) {
            InsightItem.ARTIST ->
                listOf(PeopleGroup(stringResource(R.string.home_item_also_performing), split.others))
            InsightItem.COMPOSER ->
                listOf(
                    PeopleGroup(null, split.composers.drop(1)),
                    PeopleGroup(stringResource(R.string.home_item_words), split.words),
                )
            else -> emptyList()
        }.filter { it.names.isNotEmpty() }
    if (groups.isEmpty()) return
    // A new track starts with none open.
    val playing = context.track?.nowPlaying?.let { "${it.title}\u0000${it.artists}\u0000${it.album}" }
    var open by rememberSaveable(playing, item.name) { mutableStateOf<String?>(null) }
    val handoff = rememberFocusHandoff()
    val chips = remember { mutableMapOf<String, FocusRequester>() }
    val chipOf = { name: String -> chips.getOrPut(name) { FocusRequester() } }
    // Each artist's *Close* its own: a panel switched for another's is not the one the focus goes to.
    val closes = remember { mutableMapOf<String, FocusRequester>() }
    val closeOf = { name: String -> closes.getOrPut(name) { FocusRequester() } }
    val ai = context.settings.writeInsights
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { group ->
            group.heading?.let { PeopleHeading(it) }
            PeopleChips(group.names, open, ai, { Modifier.handoffTarget(handoff, chipOf(it)) }) { name ->
                open = if (open == name) null else name
                if (open != null) handoff.moveTo(closeOf(name)) else handoff.moveTo(chipOf(name))
            }
        }
        open?.let { name ->
            key(name) {
                NamedArtist(name, context, Modifier.handoffTarget(handoff, closeOf(name))) {
                    open = null
                    handoff.moveTo(chipOf(name))
                }
            }
        }
    }
}

// A group of chips: its [heading], if any, and the artists' [names].
private class PeopleGroup(
    val heading: String?,
    val names: List<String>,
)

// A heading over a group of chips, or over the composer's insight in the Authors panel.
@Composable
private fun PeopleHeading(text: String) {
    Text(
        text,
        Modifier.padding(top = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// A chip for each of [names], drawn as the rows' buttons are ([PanelChip]), with the **AI** mark
// ([ai]); as wide as its name, the mark included, and no wider than the panel, wrapping inside;
// "Academy of St. Martin in the Fields, uses AI. Collapsed. Button. Double-tap to show this artist's
// insight"; while its insight is open ([open]), expanded, "Double-tap to hide this artist's insight".
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleChips(
    names: List<String>,
    open: String?,
    ai: Boolean,
    focus: (String) -> Modifier,
    onTap: (String) -> Unit,
) {
    val show = stringResource(R.string.home_item_show_named)
    val hide = stringResource(R.string.home_item_hide_named)
    BoxWithConstraints {
        val most = maxWidth
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { name ->
                val width = min(chipColumnWidth(listOf(name), mark = ai), most)
                PanelChip(
                    name,
                    itemChipTag(name),
                    modifier = focus(name),
                    action = if (open == name) hide else show,
                    collapsed = open != name,
                    description = if (ai) stringResource(R.string.home_uses_ai, name) else name,
                    width = width,
                    ai = ai,
                    expanded = open == name,
                    markAtEnd = false,
                ) { onTap(name) }
            }
        }
    }
}

// [name]'s insight, open in place under the chips: a panel of its own, titled with the name, its
// *Close* under [closeModifier] calling [onClose]; written at once, the chip's tap its consent.
@Composable
private fun NamedArtist(
    name: String,
    context: ItemContext,
    closeModifier: Modifier,
    onClose: () -> Unit,
) {
    val item = InsightItem.named(name)
    // Taken once, not asked again after a rotation or a restore with the chip open.
    var due by rememberSaveable(name) { mutableStateOf(true) }
    val opening = if (due) OpeningWrite(due = true) { due = false } else OpeningWrite.NONE
    Panel(AnnotatedString(name), itemPanelTag(item), onClose = onClose, closeModifier = closeModifier) {
        ItemContent(item, context, tapped = true, opening = opening)
    }
}

/** The test tag of the chip of the artist [name] in an item's panel (#1199). */
fun itemChipTag(name: String): String = "item-chip:$name"

// The composer, or every author, on a line of their own under the performers (#1199), after a
// quieter label as the Composer row names them: "Composer: Wolfgang Amadeus Mozart".
@Composable
internal fun AuthorsLine(split: ArtistSplit) {
    val label =
        stringResource(
            when (split.name) {
                AuthorsName.COMPOSER -> R.string.home_key_composer
                AuthorsName.AUTHOR -> R.string.home_key_author
                AuthorsName.AUTHORS -> R.string.home_key_authors
            },
        )
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line =
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) { append(label) }
            append(" ")
            append(split.authors.joinToString(", "))
        }
    Text(line, Modifier.testTag(KEY_AUTHORS_TAG), style = MaterialTheme.typography.bodyLarge)
}

/** The test tag of the composer's line under the performers (#1199). */
const val KEY_AUTHORS_TAG: String = "key-authors"
