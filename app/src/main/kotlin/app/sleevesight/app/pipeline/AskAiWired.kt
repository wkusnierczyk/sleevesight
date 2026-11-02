package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.emptyPanels
import app.sleevesight.app.ui.home.searchFacts
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/**
 * *Ask AI* wired together (#773, #786, #851), as the app runs it and its tests check it: the
 * searches ([searches]: Home's panels' and a genre's definition), made with the live pipeline's
 * [parts] under the [settings] as last read, in [scope], marked with [epoch], their answers checked by
 * [answers], the models named by [modelName], their questions and kept answers told to [events], and
 * whether an answer is kept from before worked out from the [plays]; and the two triggers of *Ask AI
 * automatically*, each the other's gate just before a call and where a search goes back when it
 * closed: [panels], each panel with nothing from its sources ([emptyPanels]), by its subject (for
 * *Dates* with the album, `AiSearches.subject`); and [definitions], each genre of the track's table
 * whose Wikipedia lookup ended with none on this play ([settledNoDefinition]), asked by its label as
 * the chip shows it.
 */
@Suppress("LongParameterList") // What the searches are made with, and where their answers and events go.
class AskAiWired(
    parts: StateFlow<PipelineParts?>,
    settings: StateFlow<Settings?>,
    answers: AiSearchAnswers,
    scope: CoroutineScope,
    settledNoDefinition: (TrackState, String) -> Boolean,
    epoch: ClearEpoch = ClearEpoch(),
    modelName: (String) -> String = { it },
    clock: Clock = Clock.System,
    events: (PipelineEvent) -> Unit = {},
    plays: StateFlow<Play?>,
) {
    /** *Ask AI automatically* for Home's panels. */
    lateinit var panels: AutoSearches<SearchKind>
        private set

    /** *Ask AI automatically* for a genre's definition (#786). */
    lateinit var definitions: AutoSearches<String>
        private set

    /** The searches: Home's panels' and a genre's definition. */
    val searches: AppAiSearches =
        AppAiSearches(
            parts = parts,
            settings = settings,
            answers = answers,
            scope = scope,
            epoch = epoch,
            modelName = modelName,
            clock = clock,
            // An automatic search's gates, checked again just before its call: the panels' own, or a
            // genre's definition's, which also asks that the genre still has none, settled on this play.
            automaticGate = { track, kind, genre ->
                if (kind == SearchKind.DEFINITION) {
                    definitions.open() && genre != null && settledNoDefinition(track, genre)
                } else {
                    panels.open()
                }
            },
            // Where one whose gates closed goes back, to start again once they open.
            deferred = { track, kind, genre ->
                if (kind == SearchKind.DEFINITION) {
                    genre?.let { definitions.release(track, it) }
                } else {
                    panels.release(track, kind)
                }
            },
            events = events,
            plays = plays,
        )

    init {
        val albums = NameMatcher(JvmAccentFolder)
        panels =
            AutoSearches(
                ::emptyPanels,
                { track, kind ->
                    Triple(track.normalized, track.nowPlaying.duration, AiSearches.subject(track, kind, albums))
                },
            ) { track, kind -> searches.ask(track, kind, searchFacts(track, track.nowPlaying), automatic = true) }
        definitions =
            AutoSearches(
                { track, _ -> genresWithNoDefinition(track, settledNoDefinition) },
            ) { track, genre ->
                val label =
                    track.genres.all
                        .firstOrNull { it.genre.identity == genre }
                        ?.genre
                        ?.label ?: genre
                searches.ask(track, SearchKind.DEFINITION, SearchFacts.ofGenre(label), automatic = true, genre = genre)
            }
    }
}
