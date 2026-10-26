package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.app.ui.rememberOpenUrl
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine

// *Sources* (#444; once *Where to look*) and *Genres* (D12, #84; once *Genre labels*).

/**
 * A row of *Sources*: a source's switch ([SourceSwitch]), or a web search engine's ([WebEngine]),
 * each a service of the first level since #444 (a later engine is a row of its own); with its
 * [name], what it is ([summary]), and its site, opened by *Open website ↗* ([url]; none for the AI
 * service's knowledge, which has none of its own).
 */
private sealed class SourceRow(
    @StringRes val name: Int,
    @StringRes val summary: Int,
    val url: String?,
) {
    class Source(
        val switch: SourceSwitch,
        name: Int,
        summary: Int,
        url: String?,
    ) : SourceRow(name, summary, url)

    class Engine(
        val engine: WebEngine,
        name: Int,
        summary: Int,
        url: String,
    ) : SourceRow(name, summary, url)
}

/**
 * The rows of *Sources*, in the order shown: the sources with an adapter (Discogs comes in Phase 3),
 * then the web search engines, then the AI service's own knowledge.
 */
private val SOURCE_ROWS: List<SourceRow> =
    listOf(
        SourceRow.Source(
            SourceSwitch.MUSICBRAINZ,
            R.string.settings_source_musicbrainz,
            R.string.settings_source_musicbrainz_summary,
            "https://musicbrainz.org/",
        ),
        SourceRow.Source(
            SourceSwitch.WIKIPEDIA,
            R.string.settings_source_wikipedia,
            R.string.settings_source_wikipedia_summary,
            "https://www.wikipedia.org/",
        ),
        SourceRow.Source(
            SourceSwitch.LASTFM,
            R.string.settings_source_lastfm,
            R.string.settings_source_lastfm_summary,
            "https://www.last.fm/",
        ),
        SourceRow.Engine(
            WebEngine.EXA,
            R.string.settings_engine_exa,
            R.string.settings_engine_exa_summary,
            "https://exa.ai/",
        ),
        SourceRow.Source(
            SourceSwitch.MODEL,
            R.string.settings_source_model,
            R.string.settings_source_model_summary,
            null,
        ),
    )

/**
 * *Sources*: a switch per source, each with what it is, its site, and, for one that needs a key
 * it lacks, where to save it ([keyNote]): the keys themselves are under *Secrets*. A web search
 * engine's row is on while web search is on with that engine ([Settings.isEnabled] needs both):
 * switching it on turns both on, and off turns the engine off.
 */
@Composable
internal fun SourcesSection(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    update: Update,
) {
    for (row in SOURCE_ROWS) {
        val key =
            when (row) {
                is SourceRow.Source -> sourceKey(row.switch)
                is SourceRow.Engine -> engineKey(row.engine)
            }
        val checked =
            when (row) {
                is SourceRow.Source -> row.switch in settings.enabledSources
                is SourceRow.Engine -> SourceSwitch.WEB in settings.enabledSources && row.engine in settings.webEngines
            }
        val name = stringResource(row.name)
        SwitchRow(
            title = name,
            summary = lines(stringResource(row.summary), key?.let { keyNote(it, secrets) }),
            checked = checked,
            below = row.url?.let { url -> { WebsiteLink(name, url) } },
        ) { on -> update { switched(it, row, on) } }
    }
}

/** [settings] with [row] switched [on]. */
private fun switched(
    settings: Settings,
    row: SourceRow,
    on: Boolean,
): Settings =
    when (row) {
        is SourceRow.Source -> {
            val sources = if (on) settings.enabledSources + row.switch else settings.enabledSources - row.switch
            settings.copy(enabledSources = sources)
        }

        is SourceRow.Engine -> {
            if (on) {
                settings.copy(
                    enabledSources = settings.enabledSources + SourceSwitch.WEB,
                    webEngines = settings.webEngines + row.engine,
                )
            } else {
                settings.copy(webEngines = settings.webEngines - row.engine)
            }
        }
    }

/** *Open website ↗* for the service [name], opening [url] in the browser. */
@Composable
internal fun WebsiteLink(
    name: String,
    url: String,
) {
    ExternalLink(
        text = stringResource(R.string.settings_open_website),
        description = stringResource(R.string.settings_open_website_of, name),
        onClick = rememberOpenUrl(url),
    )
}

/** [first], and [second] on a line of its own when there is one. */
internal fun lines(
    first: String,
    second: String?,
): String = if (second == null) first else "$first\n$second"

/**
 * What a row that needs the key [id] says of it, where the key is not saved and readable: that it
 * is needed, in *Secrets*, or that the one saved can no longer be read. Nothing before the keys'
 * states are read, which would show it, for a moment, beside a saved key.
 */
@Composable
internal fun keyNote(
    id: SecretId,
    secrets: Map<SecretId, SecretState>,
): String? =
    when (secrets[id]) {
        null, SecretState.SAVED -> null
        SecretState.ABSENT -> stringResource(R.string.settings_needs_key, stringResource(keyRecipient(id)))
        SecretState.UNREADABLE -> {
            stringResource(R.string.settings_key_unreadable_note, stringResource(keyRecipient(id)))
        }
    }

/**
 * *Genres* (D12, #84, #444): *Show genres*, off for none at all, not asked for from any source
 * ([Settings.givesLabels]), nor shown; then which sources' tags give genre labels, each with where
 * its tags come from and *Open website ↗* (#545). A source gives labels only while *Show genres*
 * is on and the source is on under *Sources*, and Last.fm only with its key saved and readable, so
 * until then its switch shows off and cannot be moved, and says why: a key saved but no longer
 * readable ([SecretState.UNREADABLE]) is told apart from no key.
 */
@Composable
internal fun GenresSection(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    update: Update,
) {
    SwitchRow(
        title = stringResource(R.string.settings_show_genres),
        summary = stringResource(R.string.settings_show_genres_summary),
        checked = settings.showGenres,
    ) { on -> update { it.copy(showGenres = on) } }
    for (source in LabelSource.entries) {
        val why =
            when {
                !settings.showGenres -> stringResource(R.string.settings_labels_genres_off)
                !settings.isEnabled(source.source) -> stringResource(R.string.settings_labels_source_off)
                source == LabelSource.LASTFM -> keyNote(SecretId.LASTFM, secrets)
                else -> null
            }
        // Last.fm's switch is offered only with its key saved, so not before the keys' states are read.
        val offered = why == null && (source != LabelSource.LASTFM || secrets[SecretId.LASTFM] == SecretState.SAVED)
        val name = stringResource(labelSourceName(source))
        SwitchRow(
            title = name,
            summary = lines(stringResource(labelSourceSummary(source)), why),
            checked = offered && source in settings.labelSources,
            indent = true,
            below = { WebsiteLink(name, labelSourcePage(source)) },
            onChange =
                { on: Boolean ->
                    update { it.copy(labelSources = if (on) it.labelSources + source else it.labelSources - source) }
                }.takeIf { offered },
        )
    }
}

@StringRes
private fun labelSourceName(source: LabelSource): Int =
    when (source) {
        LabelSource.LASTFM -> R.string.settings_source_lastfm
        LabelSource.MUSICBRAINZ -> R.string.settings_source_musicbrainz
    }

@StringRes
private fun labelSourceSummary(source: LabelSource): Int =
    when (source) {
        LabelSource.LASTFM -> R.string.settings_labels_lastfm_summary
        LabelSource.MUSICBRAINZ -> R.string.settings_labels_musicbrainz_summary
    }

/**
 * The page a source's *Open website ↗* opens: MusicBrainz's on its tags, their votes and its genre
 * list; for Last.fm, which has a page per tag but none that explains its tags (checked 2026-10-25),
 * its home page, as its row under *Sources* has (#545).
 */
private fun labelSourcePage(source: LabelSource): String =
    when (source) {
        LabelSource.LASTFM -> "https://www.last.fm/"
        LabelSource.MUSICBRAINZ -> "https://musicbrainz.org/doc/Folksonomy_Tagging"
    }
