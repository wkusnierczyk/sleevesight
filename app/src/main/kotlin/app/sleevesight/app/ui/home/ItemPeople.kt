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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
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
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.ArtistSplit
import app.sleevesight.core.pipeline.AuthorsName
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.resolution.MusicBrainzEvidence

// The other people of a track whose roles tell the composer apart from the performers (#1199): in
// the artist's panel, the other performers; in the Authors panel, who wrote the music and who wrote
// the words, each a chip giving way to that artist's own insight in its place, written on the tap;
// and under the title, the composer's line of its own.

/**
 * What [item]'s panel (or page) shows above its insight, by [split] (#1199): in the Authors panel,
 * *Music by*, the composer's insight being about the music; nothing otherwise.
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
 * chip for each other composer, then *Words by* and a chip for each who wrote the words. A chip's tap
 * puts that artist's insight ([InsightItem.named]) in the chip's place (#1288), in a panel of its own
 * titled with the name, with *Close*, which puts the chip back; each opens and closes on its own,
 * several at once. The tap is its consent, so it is written at once, in every mode (#948). The ones
 * open are kept while [context]'s track plays, and the focus goes to a panel's *Close* when it opens
 * and back to its chip when it closes.
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
    var open by rememberSaveable(playing, item.name) { mutableStateOf(emptyList<String>()) }
    val handoff = rememberFocusHandoff()
    val chips = remember { mutableMapOf<String, FocusRequester>() }
    val chipOf = { name: String -> chips.getOrPut(name) { FocusRequester() } }
    // Each artist's *Close* its own, as several may be open.
    val closes = remember { mutableMapOf<String, FocusRequester>() }
    val closeOf = { name: String -> closes.getOrPut(name) { FocusRequester() } }
    val ai = context.settings.writeInsights
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { group ->
            group.heading?.let { PeopleHeading(it) }
            peopleRuns(group.names, open).forEach { run ->
                // One key over both kinds, so an open panel moves with its key when the runs before it
                // change, keeping its state (and its write not asked again); a run of chips, which keeps
                // none of its own, is laid out anew when its first chip opens.
                key(if (run.open) "panel:${run.names.single()}" else "chips:${run.names.first()}") {
                    if (run.open) {
                        val name = run.names.single()
                        NamedArtist(name, context, Modifier.handoffTarget(handoff, closeOf(name))) {
                            open = open - name
                            handoff.moveTo(chipOf(name))
                        }
                    } else {
                        PeopleChips(run.names, ai, { Modifier.handoffTarget(handoff, chipOf(it)) }) { name ->
                            open = open + name
                            handoff.moveTo(closeOf(name))
                        }
                    }
                }
            }
        }
    }
}

// A stretch of a group's chips, in their order: one artist whose insight is open in its chip's place,
// or the closed chips between two open ones.
private class PeopleRun(
    val names: List<String>,
    val open: Boolean,
)

// [names] in their order, cut into runs of closed chips around each of [open], which stands alone.
private fun peopleRuns(
    names: List<String>,
    open: List<String>,
): List<PeopleRun> {
    val runs = mutableListOf<PeopleRun>()
    var closed = mutableListOf<String>()
    names.forEach { name ->
        if (name in open) {
            if (closed.isNotEmpty()) runs += PeopleRun(closed, open = false)
            closed = mutableListOf()
            runs += PeopleRun(listOf(name), open = true)
        } else {
            closed += name
        }
    }
    if (closed.isNotEmpty()) runs += PeopleRun(closed, open = false)
    return runs
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
// insight". While its insight is open, the chip is not there: its panel is, in its place (#1288).
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleChips(
    names: List<String>,
    ai: Boolean,
    focus: (String) -> Modifier,
    onTap: (String) -> Unit,
) {
    val show = stringResource(R.string.home_item_show_named)
    BoxWithConstraints {
        val most = maxWidth
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { name ->
                val width = min(chipColumnWidth(listOf(name), mark = ai), most)
                key(name) {
                    PanelChip(
                        name,
                        itemChipTag(name),
                        modifier = focus(name),
                        action = show,
                        description = if (ai) stringResource(R.string.home_uses_ai, name) else name,
                        width = width,
                        ai = ai,
                        markAtEnd = false,
                    ) { onTap(name) }
                }
            }
        }
    }
}

// [name]'s insight, open in its chip's place: a panel of its own, titled with the name, its *Close*
// under [closeModifier] calling [onClose]; written at once, the chip's tap its consent.
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
// quieter label as the Composer row names them: "Composer: Wolfgang Amadeus Mozart". When it names
// anyone the track does not credit (#1298, [ArtistSplit.uncredited]), MusicBrainz's line on the work,
// [cite], as a citation chip at its end, as the insight's are: a tap opens *Sources* at it ([onCite]),
// and a screen reader hears it after the names, "(Source MB4)", the link offered as an action too.
@Composable
internal fun AuthorsLine(
    split: ArtistSplit,
    cite: String? = null,
    onCite: (String) -> Unit = {},
) {
    val label =
        stringResource(
            when (split.name) {
                AuthorsName.COMPOSER -> R.string.home_key_composer
                AuthorsName.AUTHOR -> R.string.home_key_author
                AuthorsName.AUTHORS -> R.string.home_key_authors
            },
        )
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val styles = citeStyles()
    val names = split.authors.joinToString(", ")
    val line =
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) { append(label) }
            append(" ")
            append(names)
            if (cite != null) {
                append(" ")
                appendCite(cite, cite, CiteKind.LINE, styles, onCite)
            }
        }
    val source = cite?.let { stringResource(R.string.home_cite, it) }
    Text(
        line,
        Modifier.testTag(KEY_AUTHORS_TAG).semantics {
            if (cite != null && source != null) {
                text = withoutIsolates(line)
                contentDescription = "$label $names ($source)"
                customActions =
                    listOf(
                        CustomAccessibilityAction(source) {
                            onCite(cite)
                            true
                        },
                    )
            }
        },
        style = citedTextStyle(MaterialTheme.typography.bodyLarge),
    )
}

/**
 * The line of [lines], those *Sources* lists, that [split]'s line under the title cites (#1298): none
 * while it names only authors the track credits; else MusicBrainz's credit line on the work
 * ([MusicBrainzEvidence.creditedNames]) naming one it does not, else its first credit line on the work;
 * none while *Sources* has no such line. Names are compared whole, as the line lists them ("A, B and C").
 */
internal fun authorsCite(
    split: ArtistSplit?,
    lines: EvidenceSet?,
): String? {
    val uncredited = split?.uncredited.orEmpty()
    if (uncredited.isEmpty()) return null
    val credits =
        lines
            ?.lines
            .orEmpty()
            .filter { it.source == SourceId.MUSICBRAINZ && it.url?.contains(WORK_PAGE) == true }
            .mapNotNull { line -> MusicBrainzEvidence.creditedNames(line.text)?.let { line.id to it.split(LISTED) } }
    val naming = credits.firstOrNull { (_, names) -> uncredited.any { it in names } }
    return (naming ?: credits.firstOrNull())?.first
}

// What separates the names a MusicBrainz line lists (`EvidenceText.list`: "A, B and C").
private val LISTED = Regex(", | and ")

// The path of a MusicBrainz work's page, which its lines on the work link.
private const val WORK_PAGE = "musicbrainz.org/work/"

/** The test tag of the composer's line under the performers (#1199). */
const val KEY_AUTHORS_TAG: String = "key-authors"
