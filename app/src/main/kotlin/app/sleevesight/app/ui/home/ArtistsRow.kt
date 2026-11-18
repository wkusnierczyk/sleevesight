package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.NameMatcher

// Every artist on the track (#1168): on a track crediting several, with no composer told apart, the
// *Artists* row by the first, "{first}, others" where that fits, and in its panel or on its page a
// section for each of the others under *Also on this track*.

// The Artist row with no split: by the primary artist; *Artists* when the track credits others too
// (#1168), each of them in [ItemRow.others], in the credit's order.
internal fun artistRow(normalized: NormalizedTrack): ItemRow? {
    val first = normalized.primary.takeIf { it.isNotBlank() } ?: return null
    val others = alsoOnTrack(normalized)
    return if (others.isEmpty()) {
        ItemRow(InsightItem.ARTIST, first)
    } else {
        ItemRow(
            InsightItem.ARTIST,
            first,
            kind = R.string.home_item_artists,
            action = R.string.home_item_show_artists,
            others = others,
        )
    }
}

/**
 * The artists [normalized] credits besides its primary artist (#1168), in the credit's order, featured
 * ones the same (the normalizer reads a title's "feat." into the artists): each once, by name as the
 * resolution compares names, none blank. Empty for a track crediting one.
 */
internal fun alsoOnTrack(normalized: NormalizedTrack): List<String> {
    val seen = mutableSetOf(ARTIST_NAMES.key(normalized.primary))
    return normalized.artists.map(String::trim).filter { it.isNotEmpty() && seen.add(ARTIST_NAMES.key(it)) }
}

/**
 * What [row] reads beside its button, and as its panel's subtitle and its page's name: its name; for
 * an *Artists* row ([ItemRow.others], #1168) "{first}, others", never a list of names cut short.
 */
@Composable
internal fun rowName(row: ItemRow): String =
    if (row.others.isEmpty()) row.name else stringResource(R.string.home_item_name_others, row.name)

/**
 * [content] with the name [row] shows beside its button, its line giving [reserved] to what comes
 * before the name: the row's name; for an *Artists* row ([ItemRow.others], #1168) "{first}, others"
 * ([rowName]) where that fits on the name's one line, else the first artist alone (clipped as any
 * name), never a list of names cut short.
 */
@Composable
internal fun FittedName(
    row: ItemRow,
    reserved: Dp,
    content: @Composable (String) -> Unit,
) {
    if (row.others.isEmpty()) {
        content(row.name)
        return
    }
    val full = rowName(row)
    val style = MaterialTheme.typography.bodyLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = measurer.measure(full, style, maxLines = 1, softWrap = false).size.width
        val room = with(density) { (maxWidth - reserved).roundToPx() }
        content(if (wide <= room) full else row.name)
    }
}

// The further artists on a track crediting several, with no split (#1168): *Also on this track*, then
// a section for each of [names], in the credit's order: the name as a heading, then the artist's
// insight ([InsightItem.named]) as kept, else "Tap Write insight…" and *Write insight*, a paid call on
// its tap alone, in every mode: never written by itself, nor on the panel's opening. Written once per
// artist, it shows on any track crediting them.
@Composable
internal fun AlsoOnTrack(
    names: List<String>,
    context: ItemContext,
) {
    Column(Modifier.testTag(ALSO_ON_TRACK_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PeopleHeading(stringResource(R.string.home_item_also_on_track))
        names.forEach { name ->
            key(name) {
                Column(Modifier.testTag(alsoOnTrackTag(name)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        name,
                        Modifier.padding(top = 4.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ItemContent(InsightItem.named(name), context, tapped = false, byItself = false)
                }
            }
        }
    }
}

/** The test tag of *Also on this track* in the *Artists* panel (#1168), and of each artist's section. */
const val ALSO_ON_TRACK_TAG: String = "also-on-track"

fun alsoOnTrackTag(name: String): String = "also-on-track:$name"

// Names compared as the resolution compares them.
private val ARTIST_NAMES = NameMatcher(JvmAccentFolder)
