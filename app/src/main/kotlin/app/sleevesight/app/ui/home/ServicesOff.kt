package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.settings.labelSourceName
import app.sleevesight.app.ui.settings.providerName
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch

// What an item on Home cannot be shown without (#766): the services that are off, named as Settings
// names them, and a link to the section of Settings where they are switched on, on two lines
// ("MusicBrainz is disabled." / "See Settings → Sources."), as *Dates* and *Lyrics* say it (#724, #728).

/**
 * One part of what is off for an item (#766): its [lines], each a sentence, and the section of
 * Settings where it is switched on ([target]), which the line after them links to ([OffLines]). A
 * service that lacks its key links to where it is switched on (*Sources*, *AI services*), not to
 * *Secrets*: that section links its key.
 */
internal class OffPart(
    val lines: List<String>,
    val target: SettingsTarget,
)

/**
 * [parts] on Home (#766): each part's lines, then "See Settings → Section." with the section a link
 * that opens Settings there ([openSettingsAt]), in [style] and [color].
 */
@Composable
internal fun OffLines(
    parts: List<OffPart>,
    openSettingsAt: (SettingsTarget) -> Unit,
    style: TextStyle,
    color: Color = Color.Unspecified,
) {
    for (part in parts) {
        for (line in part.lines) LinkedText(listOf(TextPart.Words(line)), style = style, color = color)
        val target = part.target
        val link = TextPart.Action(stringResource(linkText(target)), target.linkTag) { openSettingsAt(target) }
        LinkedText(linkedParts(stringResource(R.string.home_off_see), link), style = style, color = color)
    }
}

/**
 * An item's lines of what is off ([part], #766), under *Key insights*: in [ItemQuiet]'s style, the
 * link to [openSettingsAt].
 */
@Composable
internal fun ItemOff(
    part: OffPart?,
    openSettingsAt: (SettingsTarget) -> Unit,
) {
    part ?: return
    OffLines(
        listOf(part),
        openSettingsAt,
        MaterialTheme.typography.bodyMedium,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// The words of the link to [target]: "Settings → Sources", and so on.
@StringRes
private fun linkText(target: SettingsTarget): Int =
    when (target) {
        SettingsTarget.genres -> R.string.home_settings_genres_link
        SettingsTarget.aiServices -> R.string.home_settings_ai_services_link
        SettingsTarget.insights -> R.string.home_settings_insights_link
        else -> R.string.home_settings_sources_link
    }

/**
 * The genre sources that give no genres under [settings] (#766), by why: switched off under
 * *Sources* ([disabled]; Wikidata's with *Wikipedia*'s switch), lacking their key ([keyless], among
 * [LocalKeyless]: Last.fm), or their genre switch off under *Genres* ([unswitched]). Each in the
 * order Settings shows them (MusicBrainz, Last.fm, Wikipedia's Wikidata). Empty with *Show genres*
 * off, which [GenresContent] is not shown for.
 */
internal data class GenresOff(
    val disabled: List<LabelSource> = emptyList(),
    val keyless: List<LabelSource> = emptyList(),
    val unswitched: List<LabelSource> = emptyList(),
) {
    val isEmpty: Boolean get() = disabled.isEmpty() && keyless.isEmpty() && unswitched.isEmpty()

    companion object {
        /** The order the genre sources are named in, as Settings lists them. */
        private val ORDER = listOf(LabelSource.MUSICBRAINZ, LabelSource.LASTFM, LabelSource.WIKIDATA)

        fun of(
            settings: Settings,
            keyless: Set<SourceId>,
        ): GenresOff {
            if (!settings.showGenres) return GenresOff()
            val off = ORDER.filterNot { gives(settings, keyless, it) }
            return GenresOff(
                disabled = off.filter { it.source !in keyless && !settings.isEnabled(it.source) },
                keyless = off.filter { it.source in keyless },
                unswitched = off.filter { it.source !in keyless && settings.isEnabled(it.source) },
            )
        }
    }
}

/**
 * Whether [source] gives genres under [settings] (#766): [Settings.givesLabels], and its key there
 * ([keyless]: Last.fm, on with no key it can read, is not asked).
 */
internal fun gives(
    settings: Settings,
    keyless: Set<SourceId>,
    source: LabelSource,
): Boolean = settings.givesLabels(source) && source.source !in keyless

/** [off]'s parts (#766): what is off under *Sources*, then under *Genres*. */
@Composable
internal fun genresOffParts(off: GenresOff): List<OffPart> =
    buildList {
        val sources =
            buildList {
                if (off.disabled.isNotEmpty()) add(disabledLine(off.disabled.map { switchedName(it) }))
                off.keyless.forEach { add(stringResource(R.string.home_off_key, sourceName(it.source))) }
            }
        if (sources.isNotEmpty()) add(OffPart(sources, SettingsTarget.sources))
        if (off.unswitched.isNotEmpty()) {
            val names = off.unswitched.map { listOf(stringResource(labelSourceName(it))) }
            add(OffPart(listOf(disabledLine(names)), SettingsTarget.genres))
        }
    }

// The names of [source] switched off under *Sources*: Wikidata's genres go with the switch Settings
// names "Wikipedia and Wikidata", said as two in a list ("MusicBrainz, Last.fm, Wikipedia and Wikidata").
@Composable
private fun switchedName(source: LabelSource): List<String> =
    if (source == LabelSource.WIKIDATA) {
        listOf(chipName(SourceId.WIKIPEDIA), sourceName(SourceId.WIKIDATA))
    } else {
        listOf(sourceName(source.source))
    }

// "X is disabled." for one service, "X, Y and Z are disabled." for several, [names] each a source's.
@Composable
private fun disabledLine(names: List<List<String>>): String {
    val all = names.flatten()
    return stringResource(if (all.size == 1) R.string.home_off_one else R.string.home_off_many, namedInOrder(all))
}

/**
 * Why no insight can be written (#766), with what is off: *Show insights* ([insightsOn] false,
 * *Insights*), else no AI service ready ([aiReady] false, *AI services*): none switched on, or the
 * one switched on ([provider]) lacking its key. Null when neither.
 */
@Composable
internal fun insightOffPart(
    insightsOn: Boolean,
    aiReady: Boolean,
    provider: ProviderId?,
): OffPart? =
    when {
        !insightsOn -> OffPart(listOf(stringResource(R.string.home_insight_off)), SettingsTarget.insights)
        aiReady -> null
        provider == null -> OffPart(listOf(stringResource(R.string.home_off_no_ai)), SettingsTarget.aiServices)
        else ->
            OffPart(
                listOf(stringResource(R.string.home_off_key, stringResource(providerName(provider)))),
                SettingsTarget.aiServices,
            )
    }

/**
 * The sources that cannot be asked for want of their key (#766): those on that the pipeline has no
 * key for ([needsKey], `PipelineView.needsKey`), and those whose key is not saved ([missing]), which
 * the keys' gate keeps switched off (`KeyGate`), as Settings shows them: "Needs your Last.fm key.".
 */
internal fun keylessSources(
    needsKey: Set<SourceId>,
    missing: Set<SecretId>,
): Set<SourceId> =
    needsKey +
        SourceId.entries.filter { id ->
            SourceSwitch.of(id)?.let(SecretId::forSource)?.let { it in missing } == true
        }

/**
 * The sources Home's items name as lacking their key (#766, [keylessSources]), provided by Home and
 * its pages; none where not provided.
 */
internal val LocalKeyless = compositionLocalOf { emptySet<SourceId>() }
