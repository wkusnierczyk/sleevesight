package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.WebSearchOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a genre's panel shows above its table, in the app (#523): a genre's definition found once for
 * every track, behind Wikipedia's switch; *Why is this …?* written on a tap, one call, and kept with
 * the track: shown again with no call when the panel is opened again, another genre opened in
 * between, or the track plays again later; forgotten on *Clear history*.
 */
class AppGenreNotesTest {
    private val provider = CountingProvider().apply { reply = { "Its loud guitars are rock [MB1]." } }

    // A clock the tests move.
    private val clock = MovedClock()

    // A cache that says when each card was kept: the time given, or its own reading of [clock].
    private val cache = CardCache(clock = clock)
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))

    private val rock = "https://en.wikipedia.org/wiki/Rock_music"
    private val articles =
        object : GenreArticles {
            val asked = mutableListOf<String>()

            // What each genre's lookup answers (found, by default), and, while it is not complete, a
            // lookup waits for [gate]: lookups under way at once.
            val answers = mutableMapOf<String, MutableList<Lookup<GenreDefinition>>>()
            var gate: CompletableDeferred<Unit>? = null

            override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> {
                asked += musicBrainzGenre
                gate?.await()
                return answers[musicBrainzGenre]?.removeFirstOrNull()
                    ?: Lookup.Found(GenreDefinition("Rock music is a genre of popular music.", "Rock music", rock))
            }
        }
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val definitions = GenreDefinitions(articles, vocabulary)

    // The MusicBrainz genres Wikipedia is asked about for [genres], by their identities.
    private fun ids(vararg genres: String) = genres.map { checkNotNull(vocabulary.musicBrainzGenre(it)) }

    private fun DefinitionState?.found(): GenreDefinition? = (this as? DefinitionState.Found)?.definition

    // A lookup's end: found, not found or failed; not while looking, nor with lookups off.
    private fun DefinitionState?.answered(): Boolean = this != null && this != DefinitionState.Looking

    private fun label(
        tag: String,
        source: LabelSource = LabelSource.LASTFM,
    ) = TrackLabel(
        GenreLabel(tag, "rock", tag.replaceFirstChar(Char::uppercase), tag, null, GenreLevel.ARTIST, "A"),
        listOf(LabelCredit(source, null)),
    )

    private fun track(playing: TrackState = HomeFixtures.track()): TrackState {
        val row = GenreRow(GenreLevel.ARTIST, "A", listOf(label("rock"), label("punk")))
        return playing.copy(genres = TrackGenres(listOf(row)))
    }

    private fun TestScope.notes(epoch: ClearEpoch = ClearEpoch()) =
        AppGenreNotes(definitions, parts, settings, this, epoch, clock = clock)

    @Test
    fun `a genre's definition is found once for every track that has it`() =
        runTest {
            val notes = notes()
            // The panels of three tracks with rock, one after the other.
            repeat(3) {
                assertEquals(
                    rock,
                    notes
                        .definition("rock")
                        .first { it.answered() }
                        .found()
                        ?.url,
                )
            }
            assertEquals(1, articles.asked.size)
        }

    @Test
    fun `a genre with no article is not found, so its panel can say so`() =
        runTest {
            // *British invasion* is not one of MusicBrainz's genres (#663).
            assertEquals(DefinitionState.NotFound, notes().definition("britishinvasion").first { it.answered() })
            assertEquals(0, articles.asked.size)
        }

    @Test
    fun `with Wikipedia off, no definition is looked up`() =
        runTest {
            val now = checkNotNull(settings.value)
            settings.value = now.copy(enabledSources = now.enabledSources - SourceSwitch.WIKIPEDIA)
            assertNull(notes().definition("rock").first())
            assertEquals(0, articles.asked.size)
        }

    @Test
    fun `a definition is looked up once Wikipedia comes on, and gone once it goes off`() =
        runTest {
            val on = checkNotNull(settings.value)
            settings.value = null
            val shown = mutableListOf<DefinitionState?>()
            val watching = launch { notes().definition("rock").collect { shown += it } }
            advanceUntilIdle()
            // The settings' first read.
            settings.value = on
            advanceUntilIdle()
            settings.value = on.copy(enabledSources = on.enabledSources - SourceSwitch.WIKIPEDIA)
            advanceUntilIdle()
            watching.cancel()
            // Looking while it is looked up (#786), then found.
            val states = shown.filterIndexed { i, state -> i == 0 || shown[i - 1] != state }
            assertEquals(listOf(null, DefinitionState.Looking), states.take(2))
            assertEquals(rock, states[2].found()?.url)
            assertEquals(listOf(null), states.drop(3))
        }

    @Test
    fun `a reason is written on a tap, once, and shows again on the next opening and after another genre`() =
        runTest {
            val notes = notes()
            val track = track()
            assertEquals(GenreReason.Offered, notes.reason(track, "rock").first())
            notes.explain(track, "rock")
            // A second tap while it is written asks nothing more.
            notes.explain(track, "rock")
            advanceUntilIdle()
            val written = notes.reason(track, "rock").first() as GenreReason.Shown
            assertTrue(written.state is InsightState.Written)
            assertEquals(1, provider.requests)
            // Another genre in between: offered, not written.
            assertEquals(GenreReason.Offered, notes.reason(track, "punk").first())
            // The panel opened again on the first: as written, no call.
            assertEquals(written, notes.reason(track, "rock").first())
            notes.explain(track, "rock")
            advanceUntilIdle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `a reason written before shows again when the track plays again later, with no call`() =
        runTest {
            val first = notes()
            first.explain(track(), "rock")
            advanceUntilIdle()
            // A process later: nothing in memory, the cache keeps it.
            val later = notes()
            val reason = later.reason(track(), "rock")
            reason.first()
            advanceUntilIdle()
            val shown = reason.first() as GenreReason.Shown
            assertTrue((shown.state as InsightState.Written).cached)
            assertEquals(1, provider.requests)
        }

    @Test
    fun `a reason from the evidence alone shows again with no call after AI web search changes (#801)`() =
        runTest {
            val searched = mutableListOf<String>()
            parts.value =
                PipelineParts(
                    normalizer = Normalizer(JvmAccentFolder),
                    adapters = emptyList(),
                    resolver = TrackResolver { _, _ -> Resolution.NONE },
                    hasher = JvmHasher,
                    ai = AiAccess(provider, Secret("key")),
                    cache = cache,
                    webSearch = { query ->
                        searched += query
                        WebSearchOutcome.Failed
                    },
                )
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            val notes = notes()
            notes.explain(track(), "rock")
            advanceUntilIdle()
            assertTrue((notes.reason(track(), "rock").first() as GenreReason.Shown).state is InsightState.Written)
            assertEquals(1, provider.requests)
            // *Once* since: the reason's key has its web part, and the one from the evidence alone is
            // found under the key without it, shown, and a tap asks nothing.
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.ONCE)
            val reason = notes.reason(track(), "rock")
            reason.first()
            advanceUntilIdle()
            assertTrue((reason.first() as GenreReason.Shown).state is InsightState.Written)
            notes.explain(track(), "rock")
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(searched.isEmpty())
        }

    @Test
    fun `a reason shown is offered again once it is older than Keep history for`() =
        runTest {
            settings.value = settings.value?.copy(historyLifetime = 7.days)
            val notes = notes()
            notes.explain(track(), "rock")
            advanceUntilIdle()
            assertTrue(notes.reason(track(), "rock").first() is GenreReason.Shown)
            clock.at += 7.days
            assertEquals(GenreReason.Offered, notes.reason(track(), "rock").first())
            // A tap writes it again: the store serves no copy as old (this fake cache cannot tell).
            cache.clearCards()
            notes.explain(track(), "rock")
            advanceUntilIdle()
            assertEquals(2, provider.requests)
        }

    @Test
    fun `a reason written is held from the time the cache keeps it with`() =
        runTest {
            settings.value = settings.value?.copy(historyLifetime = 7.days)
            // The clock moves on each time it is read while the reason is written.
            clock.tick = 1.milliseconds
            val notes = notes()
            notes.explain(track(), "rock")
            advanceUntilIdle()
            clock.tick = Duration.ZERO
            val kept = cache.keptTimes.single()
            clock.at = kept + 7.days - 1.milliseconds
            assertTrue(notes.reason(track(), "rock").first() is GenreReason.Shown)
            // Past *Keep history for* by the cache's time, past it on the screen too.
            clock.at = kept + 7.days
            assertEquals(GenreReason.Offered, notes.reason(track(), "rock").first())
        }

    @Test
    fun `a shorter Keep history for drops a reason shown at once`() =
        runTest {
            val notes = notes()
            notes.explain(track(), "rock")
            advanceUntilIdle()
            clock.at += 3.days
            val shown = mutableListOf<GenreReason>()
            val watching = launch { notes.reason(track(), "rock").collect { shown += it } }
            advanceUntilIdle()
            settings.value = settings.value?.copy(historyLifetime = 1.days)
            advanceUntilIdle()
            watching.cancel()
            assertTrue(shown.first() is GenreReason.Shown)
            assertEquals(GenreReason.Offered, shown.last())
        }

    @Test
    fun `no reason is offered with Show insights off, or without an AI service`() =
        runTest {
            val notes = notes()
            settings.value = settings.value?.copy(writeInsights = false)
            assertEquals(GenreReason.None, notes.reason(track(), "rock").first())
            settings.value = settings.value?.copy(writeInsights = true)
            parts.value = null
            assertEquals(GenreReason.None, notes.reason(track(), "rock").first())
            notes.explain(track(), "rock")
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `Clear history forgets the reasons shown`() =
        runTest {
            val epoch = ClearEpoch()
            val notes = notes(epoch)
            notes.explain(track(), "rock")
            advanceUntilIdle()
            cache.clearCards()
            epoch.advance()
            notes.forget()
            assertEquals(GenreReason.Offered, notes.reason(track(), "rock").first())
        }

    // A track whose table has [genres]: the artist's, then, with [album], the album's too.
    private fun playing(
        vararg genres: String,
        album: List<String> = emptyList(),
    ): TrackState {
        val rows =
            listOfNotNull(
                GenreRow(GenreLevel.ARTIST, "A", genres.map { label(it) }),
                album.takeIf { it.isNotEmpty() }?.let { tags ->
                    GenreRow(GenreLevel.ALBUM, null, tags.map { label(it) })
                },
            )
        return HomeFixtures.track().copy(genres = TrackGenres(rows))
    }

    @Test
    fun `every genre of the table is looked up ahead, at once, as the genres land, and not again (#786)`() =
        runTest {
            val notes = notes()
            articles.gate = CompletableDeferred()
            notes.lookups.fetchAhead(playing("rock"))
            runCurrent()
            assertEquals(ids("rock"), articles.asked)
            assertEquals(DefinitionState.Looking, notes.lookups.lookedUp.value["rock"])
            // The track gains genres, its album's too: each new one asked, while the first still runs.
            notes.lookups.fetchAhead(playing("rock", "punk", album = listOf("jazz")))
            runCurrent()
            assertEquals(ids("rock", "punk", "jazz"), articles.asked)
            assertTrue(
                notes.lookups.lookedUp.value.values
                    .all { it == DefinitionState.Looking },
            )
            checkNotNull(articles.gate).complete(Unit)
            advanceUntilIdle()
            assertTrue(
                notes.lookups.lookedUp.value.values
                    .all { it is DefinitionState.Found },
            )
            // Found: not asked again, by the same track, another, or a panel.
            notes.lookups.fetchAhead(playing("rock", "punk", album = listOf("jazz")))
            notes.lookups.fetchAhead(
                playing("rock").let { it.copy(normalized = it.normalized.copy(title = "another")) },
            )
            notes.definition("punk").first { it.answered() }
            advanceUntilIdle()
            assertEquals(3, articles.asked.size)
        }

    @Test
    fun `a genre being looked up is not asked twice (#786)`() =
        runTest {
            val notes = notes()
            articles.gate = CompletableDeferred()
            notes.lookups.fetchAhead(playing("rock"))
            val watching = launch { notes.definition("rock").collect {} }
            runCurrent()
            notes.retryDefinition("rock")
            runCurrent()
            assertEquals(ids("rock"), articles.asked)
            watching.cancel()
            checkNotNull(articles.gate).complete(Unit)
        }

    @Test
    fun `Retry asks one failed genre again, which looks again (#786)`() =
        runTest {
            val notes = notes()
            articles.answers[ids("rock").single()] = mutableListOf(Lookup.Failed("offline"))
            notes.lookups.fetchAhead(playing("rock", "punk"))
            advanceUntilIdle()
            assertEquals(DefinitionState.Failed, notes.lookups.lookedUp.value["rock"])
            assertTrue(notes.lookups.lookedUp.value["punk"] is DefinitionState.Found)
            // The same play asks a failed genre no more by itself.
            notes.lookups.fetchAhead(playing("rock", "punk"))
            advanceUntilIdle()
            assertEquals(2, articles.asked.size)
            articles.gate = CompletableDeferred()
            notes.retryDefinition("rock")
            runCurrent()
            assertEquals(DefinitionState.Looking, notes.lookups.lookedUp.value["rock"])
            checkNotNull(articles.gate).complete(Unit)
            advanceUntilIdle()
            assertEquals(ids("rock", "punk", "rock"), articles.asked)
            assertEquals(
                rock,
                notes.lookups.lookedUp.value["rock"]
                    .found()
                    ?.url,
            )
            // A genre found is not asked again on Retry.
            notes.retryDefinition("rock")
            advanceUntilIdle()
            assertEquals(3, articles.asked.size)
        }

    @Test
    fun `nothing is looked up ahead with Show genres or Wikipedia off (#786)`() =
        runTest {
            val on = checkNotNull(settings.value)
            val notes = notes()
            settings.value = on.copy(showGenres = false)
            notes.lookups.fetchAhead(playing("rock"))
            settings.value = on.copy(enabledSources = on.enabledSources - SourceSwitch.WIKIPEDIA)
            notes.lookups.fetchAhead(playing("rock"))
            advanceUntilIdle()
            assertEquals(emptyList<String>(), articles.asked)
            assertNull(notes.definition("rock").first())
            // Once on, the track's genres are asked.
            settings.value = on
            notes.lookups.fetchAhead(playing("rock"))
            advanceUntilIdle()
            assertEquals(ids("rock"), articles.asked)
        }

    @Test
    fun `the fetch ahead follows the tracks and the settings (#786)`() =
        runTest {
            val on = checkNotNull(settings.value)
            settings.value = on.copy(showGenres = false)
            val notes = notes()
            val tracks = MutableStateFlow<TrackState?>(playing("rock"))
            val following = launch { notes.lookups.fetchAhead(tracks) }
            advanceUntilIdle()
            assertEquals(emptyList<String>(), articles.asked)
            settings.value = on
            advanceUntilIdle()
            tracks.value = playing("rock", "punk")
            advanceUntilIdle()
            following.cancel()
            assertEquals(ids("rock", "punk"), articles.asked)
        }

    @Test
    fun `Clear history forgets the definitions, and a lookup under way shows nothing (#786)`() =
        runTest {
            val epoch = ClearEpoch()
            val notes = notes(epoch)
            notes.lookups.fetchAhead(playing("rock"))
            advanceUntilIdle()
            articles.gate = CompletableDeferred()
            notes.lookups.fetchAhead(playing("punk"))
            runCurrent()
            epoch.advance()
            notes.forget()
            checkNotNull(articles.gate).complete(Unit)
            advanceUntilIdle()
            assertEquals(emptyMap<String, DefinitionState>(), notes.lookups.lookedUp.value)
        }

    @Test
    fun `the genres searched by themselves are those settled as having no definition on this play (#786)`() =
        runTest {
            val notes = expiring(1.days)
            val track = playing("rock", "punk", album = listOf("jazz"))
            articles.answers[ids("rock").single()] = mutableListOf(Lookup.NotFound, Lookup.NotFound)
            articles.answers[ids("jazz").single()] = mutableListOf(Lookup.NotFound)
            notes.lookups.fetchAhead(track)
            advanceUntilIdle()
            val settled = notes.lookups::settledNoDefinition
            assertEquals(setOf("rock", "jazz"), genresWithNoDefinition(track, settled))
            // Another play: none settled until its check answers, while the shown state stays.
            clock.at += 1.days
            articles.gate = CompletableDeferred()
            val next = track.next(1)
            notes.lookups.fetchAhead(next)
            runCurrent()
            assertEquals(DefinitionState.NotFound, notes.lookups.lookedUp.value["rock"])
            assertEquals(emptySet<String>(), genresWithNoDefinition(next, settled))
            assertEquals(emptySet<String>(), genresWithNoDefinition(track, settled))
            checkNotNull(articles.gate).complete(Unit)
            advanceUntilIdle()
            // Rock still has none; jazz is found now.
            assertEquals(setOf("rock"), genresWithNoDefinition(next, settled))
            // Off with *Show genres* or Wikipedia off.
            val now = checkNotNull(settings.value)
            settings.value = now.copy(showGenres = false)
            assertEquals(emptySet<String>(), genresWithNoDefinition(next, settled))
            settings.value = now.copy(enabledSources = now.enabledSources - SourceSwitch.WIKIPEDIA)
            assertEquals(emptySet<String>(), genresWithNoDefinition(next, settled))
        }

    @Test
    fun `a Clear history between the choice of the genres and their lookups ends the batch (#786)`() =
        runTest {
            val epoch = ClearEpoch()
            var clearing = true
            lateinit var lookups: AppGenreDefinitions
            lookups =
                AppGenreDefinitions(definitions, settings, this, epoch) {
                    if (clearing) {
                        runBlocking { epoch.advance() }
                        lookups.forget()
                    }
                }
            lookups.fetchAhead(playing("rock", "punk"))
            advanceUntilIdle()
            // Nothing asked, so nothing kept; nothing shown.
            assertEquals(emptyList<String>(), articles.asked)
            assertEquals(emptyMap<String, DefinitionState>(), lookups.lookedUp.value)
            // The track's next fetch ahead asks, once.
            clearing = false
            lookups.fetchAhead(playing("rock", "punk"))
            advanceUntilIdle()
            assertEquals(ids("rock", "punk"), articles.asked)
        }

    @Test
    fun `a Clear history while a batch's lookups run lets none of them show (#786)`() =
        runTest {
            val epoch = ClearEpoch()
            val notes = notes(epoch)
            articles.gate = CompletableDeferred()
            notes.lookups.fetchAhead(playing("rock", "punk"))
            runCurrent()
            epoch.advance()
            notes.forget()
            checkNotNull(articles.gate).complete(Unit)
            advanceUntilIdle()
            assertEquals(emptyMap<String, DefinitionState>(), notes.lookups.lookedUp.value)
        }

    // Notes whose definitions are kept in memory for [lifetime] by [clock], as the store keeps them for
    // Wikipedia's lifetime.
    private fun TestScope.expiring(lifetime: Duration) =
        AppGenreNotes(GenreDefinitions(articles, vocabulary, clock, lifetime), parts, settings, this, clock = clock)

    // [track] played again, as a new play: another identity.
    private fun TrackState.next(n: Int) = copy(normalized = normalized.copy(title = "play $n"))

    @Test
    fun `each play checks a definition again, gone past its lifetime, with no looking meanwhile (#786)`() =
        runTest {
            val notes = expiring(1.days)
            val shown = mutableListOf<DefinitionState?>()
            val watching = launch { notes.definition("rock").collect { shown += it } }
            notes.lookups.fetchAhead(playing("rock"))
            advanceUntilIdle()
            assertEquals(
                rock,
                notes.lookups.lookedUp.value["rock"]
                    .found()
                    ?.url,
            )
            // Wikipedia's article goes; within the lifetime, the next play is served what is kept.
            articles.answers[ids("rock").single()] = mutableListOf(Lookup.NotFound)
            shown.clear()
            notes.lookups.fetchAhead(playing("rock").next(1))
            advanceUntilIdle()
            assertEquals(1, articles.asked.size)
            assertEquals(
                rock,
                notes.lookups.lookedUp.value["rock"]
                    .found()
                    ?.url,
            )
            assertTrue("no looking while it is checked: $shown", shown.none { it == DefinitionState.Looking })
            // Past the lifetime, the next play asks Wikipedia, and the definition is gone.
            clock.at += 1.days
            notes.lookups.fetchAhead(playing("rock").next(2))
            advanceUntilIdle()
            assertEquals(2, articles.asked.size)
            assertEquals(DefinitionState.NotFound, notes.lookups.lookedUp.value["rock"])
            assertTrue("no looking while it is checked: $shown", shown.none { it == DefinitionState.Looking })
            // Asked once a play: the same play again asks nothing.
            notes.lookups.fetchAhead(playing("rock").next(2))
            advanceUntilIdle()
            assertEquals(2, articles.asked.size)
            watching.cancel()
        }

    @Test
    fun `a genre with no definition is asked again past Wikipedia's lifetime, and then found (#786)`() =
        runTest {
            val notes = expiring(1.days)
            articles.answers[ids("rock").single()] = mutableListOf(Lookup.NotFound)
            notes.lookups.fetchAhead(playing("rock"))
            advanceUntilIdle()
            assertEquals(DefinitionState.NotFound, notes.lookups.lookedUp.value["rock"])
            notes.lookups.fetchAhead(playing("rock").next(1))
            advanceUntilIdle()
            assertEquals(DefinitionState.NotFound, notes.lookups.lookedUp.value["rock"])
            assertEquals(1, articles.asked.size)
            clock.at += 1.days
            notes.lookups.fetchAhead(playing("rock").next(2))
            advanceUntilIdle()
            assertEquals(2, articles.asked.size)
            assertEquals(
                rock,
                notes.lookups.lookedUp.value["rock"]
                    .found()
                    ?.url,
            )
        }

    @Test
    fun `a check that fails leaves not found shown, but does not settle on this play that there is none (#786)`() =
        runTest {
            val notes = expiring(1.days)
            articles.answers[ids("rock").single()] = mutableListOf(Lookup.NotFound, Lookup.Failed("offline"))
            val track = playing("rock")
            notes.lookups.fetchAhead(track)
            advanceUntilIdle()
            assertTrue(notes.lookups.settledNoDefinition(track, "rock"))
            // Past Wikipedia's lifetime, the next play's check fails: still shown as not found, but
            // Wikipedia was not reached on this play, so nothing is searched by itself.
            clock.at += 1.days
            val next = track.next(1)
            notes.lookups.fetchAhead(next)
            advanceUntilIdle()
            assertEquals(2, articles.asked.size)
            assertEquals(DefinitionState.NotFound, notes.lookups.lookedUp.value["rock"])
            assertFalse(notes.lookups.settledNoDefinition(next, "rock"))
            assertEquals(emptySet<String>(), genresWithNoDefinition(next, notes.lookups::settledNoDefinition))
        }

    @Test
    fun `a check that fails leaves the definition shown (#786)`() =
        runTest {
            val notes = expiring(1.days)
            notes.lookups.fetchAhead(playing("rock"))
            advanceUntilIdle()
            clock.at += 1.days
            articles.answers[ids("rock").single()] = mutableListOf(Lookup.Failed("offline"))
            notes.lookups.fetchAhead(playing("rock").next(1))
            advanceUntilIdle()
            assertEquals(2, articles.asked.size)
            assertEquals(
                rock,
                notes.lookups.lookedUp.value["rock"]
                    .found()
                    ?.url,
            )
        }
}
