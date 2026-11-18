package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings

// An item's insight that no source has anything for (#1308): one line naming the sources asked, as the
// *Sources* chips name them, and a quiet line for each it would ask that was not asked, switched off or
// without its key, with a link to where it is set.

/** Why a source an item would ask was not asked (#1308), in the words of its line. */
internal enum class NotAskedWhy(
    val words: Int,
) {
    /** Switched off under *Settings → Sources*. */
    DISABLED(R.string.home_item_not_asked_disabled),

    /** Its key not saved, or not readable, under *Settings → Secrets*. */
    NO_KEY(R.string.home_item_not_asked_no_key),

    /** *AI web search* set to *Off* under *Settings → Insights*. */
    OFF(R.string.home_item_not_asked_off),
}

/**
 * A source an item would ask that was not asked (#1308): [name], as *Settings* names it, [why], and
 * [where] it is set, with [link] the words of the link there.
 */
internal data class NotAsked(
    val name: Int,
    val why: NotAskedWhy,
    val where: SettingsTarget,
    val link: Int,
)

/**
 * The sources an item's insight asked ([asked], in the *Sources* chips' order; [SourceId.WEB] for a web
 * search), and those it would ask that were not asked ([notAsked]).
 */
internal data class SourcesAsked(
    val asked: List<SourceId>,
    val notAsked: List<NotAsked>,
)

/**
 * The sources [item]'s insight, its [evidence]'s, asks under [settings] (#1308), with [keys] the keys'
 * states and [needsKey] the keyed sources switched on that are not looked up for want of a readable key
 * (`PipelineView.needsKey`):
 * - MusicBrainz and Wikipedia (Wikidata with it), each by its switch;
 * - Last.fm, by its switch and its key;
 * - for an album looked up by its name ([ItemEvidence.byName]), neither MusicBrainz nor Last.fm, which
 *   are not asked about an album with no release group;
 * - a web search: the song's and the album's by *Exa web search* and its key; the artist's (and
 *   any other artist's) by those and *AI web search*, which searches for the artist only when it is
 *   not *Off*, and not when the AI service wrote its insight without searching
 *   ([ItemEvidence.webSkipped]: none was asked, and nothing is switched off).
 */
internal fun sourcesAsked(
    item: InsightItem,
    evidence: ItemEvidence,
    settings: Settings,
    keys: Map<SecretId, SecretState>,
    needsKey: Set<SourceId>,
): SourcesAsked {
    // Looked up by its name: MusicBrainz and Last.fm not asked about the album.
    val byName = item == InsightItem.ALBUM && evidence.byName
    val switched = if (byName) listOf(SourceId.WIKIPEDIA) else listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA)
    // Each source the item asks, and why it was not asked, null when it was.
    val why =
        switched.map { it to disabled(it).takeUnless { _ -> settings.isEnabled(it) } } +
            listOfNotNull(lastFm(settings, keys, needsKey).takeUnless { byName }) +
            listOfNotNull(webSearch(item, evidence, settings, keys, needsKey)?.let { SourceId.WEB to it.notAsked })
    return SourcesAsked(why.filter { it.second == null }.map { it.first }, why.mapNotNull { it.second })
}

// Last.fm, and why it was not asked, null when it was.
private fun lastFm(
    settings: Settings,
    keys: Map<SecretId, SecretState>,
    needsKey: Set<SourceId>,
): Pair<SourceId, NotAsked?> = SourceId.LASTFM to keyed(SourceId.LASTFM, SecretId.LASTFM, settings, keys, needsKey)

// Whether [item]'s web search was asked, as [sourcesAsked] says ([WebSearch.notAsked] null); null when
// none was, nor was it held back by a setting: the artist's, written with no search made.
private fun webSearch(
    item: InsightItem,
    evidence: ItemEvidence,
    settings: Settings,
    keys: Map<SecretId, SecretState>,
    needsKey: Set<SourceId>,
): WebSearch? {
    val exa = keyed(SourceId.WEB, SecretId.EXA, settings, keys, needsKey)
    return when {
        exa != null -> WebSearch(exa)
        item.aboutArtist && settings.aiWebSearchUsed == AiWebSearch.OFF ->
            WebSearch(
                NotAsked(
                    R.string.settings_ai_web_search,
                    NotAskedWhy.OFF,
                    SettingsTarget.aiWebSearch,
                    R.string.home_settings_insights_link,
                ),
            )
        item.aboutArtist && evidence.webSkipped -> null
        else -> WebSearch(null)
    }
}

// An item's web search: asked, or [notAsked] and why.
private class WebSearch(
    val notAsked: NotAsked?,
)

// [source], switched off under *Sources*.
private fun disabled(source: SourceId): NotAsked =
    NotAsked(settingsName(source), NotAskedWhy.DISABLED, SettingsTarget.sources, R.string.home_settings_sources_link)

// Why [source], which needs its [key], is not asked under [settings]; null when it is. With its key not
// saved (the keys' gate keeps it off then), or saved but not readable ([needsKey]), for want of the key,
// at its row under *Secrets*; else switched off.
private fun keyed(
    source: SourceId,
    key: SecretId,
    settings: Settings,
    keys: Map<SecretId, SecretState>,
    needsKey: Set<SourceId>,
): NotAsked? =
    when {
        source in needsKey || (!settings.isEnabled(source) && keys[key] == SecretState.ABSENT) ->
            NotAsked(
                settingsName(source),
                NotAskedWhy.NO_KEY,
                SettingsTarget.key(key),
                R.string.home_settings_secrets_link,
            )
        !settings.isEnabled(source) -> disabled(source)
        else -> null
    }

// [source] as its *Sources* row names it, but Wikipedia as its chip does, Wikidata included.
private fun settingsName(source: SourceId): Int =
    when (source) {
        SourceId.MUSICBRAINZ -> R.string.settings_source_musicbrainz
        SourceId.WIKIPEDIA -> R.string.home_source_chip_wikipedia
        SourceId.LASTFM -> R.string.settings_source_lastfm
        else -> R.string.settings_engine_exa
    }

/**
 * What [item]'s insight, with no source having anything for it, says (#1308): the sources [asked] named
 * together, then a line for each [SourcesAsked.notAsked], its link opening the settings there
 * ([openSettingsAt]). With none asked, the line that the sources give nothing ([noEvidence]).
 */
@Composable
internal fun NoSourceHasIt(
    item: InsightItem,
    asked: SourcesAsked,
    context: ItemContext,
) {
    if (asked.asked.isEmpty()) {
        ItemQuiet(stringResource(noEvidence(item, context.track)))
    } else {
        val web = stringResource(R.string.home_item_asked_web)
        val names = namedInOrder(asked.asked.map { if (it == SourceId.WEB) web else chipName(it) })
        ItemQuiet(stringResource(askedLine(item, context), names), Modifier.testTag(ITEM_ASKED_TAG))
    }
    val style = MaterialTheme.typography.bodyMedium
    for (not in asked.notAsked) {
        val link = TextPart.Action(stringResource(not.link), not.where.linkTag) { context.openSettingsAt(not.where) }
        LinkedText(
            linkedParts(
                stringResource(R.string.home_item_not_asked),
                TextPart.Words(stringResource(not.name)),
                TextPart.Words(stringResource(not.why.words)),
                link,
            ),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ITEM_NOT_ASKED_TAG),
        )
    }
}

// The line naming the sources [item] asked, as its row on [context]'s track names it: the music's on a
// classical track, as [noEvidence] has it.
private fun askedLine(
    item: InsightItem,
    context: ItemContext,
): Int =
    when (noEvidence(item, context.track)) {
        R.string.home_item_no_evidence_song -> R.string.home_item_asked_song
        R.string.home_item_no_evidence_song_classical -> R.string.home_item_asked_song_classical
        R.string.home_item_no_evidence_composer -> R.string.home_item_asked_composer
        R.string.home_item_no_evidence_artist -> R.string.home_item_asked_artist
        R.string.home_item_no_evidence_album -> R.string.home_item_asked_album
        else -> R.string.home_item_asked_named
    }

/** The test tag of the line naming the sources an item asked (#1308). */
const val ITEM_ASKED_TAG: String = "item_asked"

/** The test tag of each line naming a source an item did not ask (#1308). */
const val ITEM_NOT_ASKED_TAG: String = "item_not_asked"
