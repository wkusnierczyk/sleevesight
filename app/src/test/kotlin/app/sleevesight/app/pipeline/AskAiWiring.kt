package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.KeptCard
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock

/**
 * *Ask AI* wired as `AppGraph` wires it (#851, [AskAiWired]): the panels' searches and a genre's
 * definition ([searches]), the genres' notes ([notes]) with their definitions from [articles], and the
 * two triggers of *Ask AI automatically* ([panels], [definitions]) with their gates, on [cache], asking
 * [provider], in [scope]; the diagnostics' events kept in [events]; the plays of the track shown
 * followed by [plays], none by default. A second wiring on the same cache is the app after a
 * restart.
 */
@Suppress("LongParameterList") // What the app's wiring is given, each a test's to choose.
internal class AskAi(
    scope: CoroutineScope,
    val settings: MutableStateFlow<Settings?>,
    cache: PipelineCache,
    val provider: CountingProvider,
    articles: GenreArticles = GenreArticles { Lookup.NotFound },
    clock: Clock = Clock.System,
    plays: Plays? = null,
) {
    /** The diagnostics' events told, in order. */
    val events = mutableListOf<PipelineEvent>()

    val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                Normalizer(JvmAccentFolder),
                emptyList(),
                TrackResolver { _, _ -> Resolution.NONE },
                JvmHasher,
                AiAccess(provider, Secret("key")),
                cache,
            ),
        )

    val notes =
        AppGenreNotes(
            GenreDefinitions(articles, VOCABULARY),
            parts,
            settings,
            scope,
            clock = clock,
            events = { events += it },
            plays = plays?.current ?: MutableStateFlow(null),
        )

    // As `AppGraph` wires them.
    private val wired =
        AskAiWired(
            parts = parts,
            settings = settings,
            answers = AiSearchAnswers(VOCABULARY, JvmAccentFolder),
            scope = scope,
            settledNoDefinition = notes.lookups::settledNoDefinition,
            clock = clock,
            events = { events += it },
            plays = plays?.current ?: MutableStateFlow(null),
        )

    val searches: AppAiSearches = wired.searches
    val panels: AutoSearches<SearchKind> = wired.panels
    val definitions: AutoSearches<String> = wired.definitions

    companion object {
        val VOCABULARY: GenreVocabulary = GenreVocabulary.load(JvmResourceReader)

        /** Sundown, every source done with nothing: no date, genre or lyrics page. */
        fun empty(playing: app.sleevesight.core.model.NowPlaying = HomeFixtures.sundown): TrackState =
            HomeFixtures.track(
                playing,
                sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.None), SourceId.LASTFM to done()),
            )

        /** [playing] with punk in its table, from Last.fm, which Wikipedia may have no article for. */
        fun punk(playing: TrackState = HomeFixtures.track()): TrackState =
            playing.copy(
                genres =
                    TrackGenres(
                        listOf(
                            GenreRow(
                                GenreLevel.ARTIST,
                                "A",
                                listOf(
                                    TrackLabel(
                                        GenreLabel("punk", "Punk", "punk", null, GenreLevel.ARTIST, "A"),
                                        listOf(LabelCredit(LabelSource.LASTFM, null)),
                                    ),
                                ),
                            ),
                        ),
                    ),
            )

        /** [this] played as another track: another title. */
        fun TrackState.another(): TrackState =
            copy(normalized = normalized.copy(title = normalized.title + " (another)"))
    }
}

/** A cache whose reads wait for [open] while [holding]: a slow lookup, during which the gates change. */
internal class SlowCache(
    private val cache: PipelineCache,
) : PipelineCache by cache {
    var holding = false
    val open = CompletableDeferred<Unit>()

    override suspend fun keptCard(key: CardKey): KeptCard? {
        if (holding) open.await()
        return cache.keptCard(key)
    }
}
