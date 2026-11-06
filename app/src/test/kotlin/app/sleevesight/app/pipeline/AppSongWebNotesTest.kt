package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SongWebLookup
import app.sleevesight.core.sources.SongWebQuery
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.SentenceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * The song's insight in the app for a song the sources only list (#958): a web search made only by a
 * write, through its gates, counted as paid; a second visit finds it all with no search or call.
 */
class AppSongWebNotesTest {
    private val cache = CardCache()
    private val songWeb = Lines()
    private val settings =
        MutableStateFlow<Settings?>(
            Settings(
                aiProvider = ProviderId.ANTHROPIC,
                enabledSources = Settings().enabledSources + SourceSwitch.WEB,
                webEngines = setOf(WebEngine.EXA),
                aiWebSearch = AiWebSearch.ONCE,
            ),
        )
    private var open = true
    private var paid = QuestionCost()

    private class Lines(
        var found: List<EvidenceDraft>? = listOf(WEB_LINE),
    ) : SongWebLookup {
        val kept = mutableMapOf<String, List<EvidenceDraft>>()
        var searches = 0

        override suspend fun kept(song: SongWebQuery): List<EvidenceDraft>? = kept[song.track.lookupKey]

        override suspend fun search(
            song: SongWebQuery,
            sent: () -> Unit,
            keeping: (List<EvidenceDraft>) -> Unit,
            mayPay: () -> Boolean,
        ): List<EvidenceDraft>? =
            kept[song.track.lookupKey] ?: run {
                if (!mayPay()) return null
                searches++
                sent()
                found?.also {
                    keeping(it)
                    kept[song.track.lookupKey] = it
                }
            }

        override suspend fun keep(
            song: SongWebQuery,
            lines: List<EvidenceDraft>,
        ) {
            kept[song.track.lookupKey] = lines
        }

        override suspend fun pageLines(song: SongWebQuery): (WebPage, Instant) -> EvidenceDraft? = { _, _ -> null }

        override suspend fun renew(song: SongWebQuery) = Unit
    }

    private fun parts(provider: CountingProvider) =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
                songWeb = songWeb,
            ),
        )

    private fun TestScope.notes(parts: MutableStateFlow<PipelineParts?>) =
        AppItemNotes(
            parts,
            settings,
            this,
            NameMatcher(JvmAccentFolder),
            ClearEpoch(),
            paid = { paid += it },
            gate = { open },
        )

    // Sundown with a MusicBrainz line about the song, a listing, and a Wikipedia line about the artist.
    private fun track(): TrackState {
        val drafts =
            listOf(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "Sundown was first released in 1974."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, ARTIST_TEXT, scope = Scope.ARTIST),
            )
        val sources = drafts.groupBy { it.source }.mapValues { (_, lines) -> HomeFixtures.done(evidence = lines) }
        return HomeFixtures.track(HomeFixtures.sundown, sources = sources, evidence = EvidenceSet.of(drafts, JvmHasher))
    }

    private suspend fun AppItemNotes.settled(track: TrackState): ItemShown =
        item(track, InsightItem.SONG).first {
            it is ItemShown.Offered || (it is ItemShown.Shown && it.state !is InsightState.Writing)
        }

    @Test
    fun `once, the song's write searches, Sources lists the page, and a second visit pays for nothing`() =
        runTest {
            val provider = CountingProvider().apply { reply = { "A slow groove [S1]." } }
            val parts = parts(provider)
            val notes = notes(parts)
            val first = track()
            // Shown, unfolded, its key looked up: no search.
            assertEquals(ItemShown.Offered, notes.settled(first))
            assertEquals(0, songWeb.searches)

            notes.write(first, InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, songWeb.searches)
            assertEquals(QuestionCost(searches = 1, calls = 1), paid)
            assertTrue(WEB_LINE.text in provider.prompts.single())
            assertTrue(ARTIST_TEXT !in provider.prompts.single())
            assertTrue((notes.settled(first) as ItemShown.Shown).state is InsightState.Written)
            assertTrue(
                notes
                    .songEvidence(first)
                    .first()
                    ?.lines
                    .orEmpty()
                    .any { it.text == WEB_LINE.text },
            )

            // A second visit, a new notes as after a restart: the insight found, no search or call.
            val again = notes(parts)
            assertTrue((again.settled(first) as ItemShown.Shown).state is InsightState.Written)
            again.write(first, InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, songWeb.searches)
            assertEquals(1, provider.requests)
            assertEquals(QuestionCost(searches = 1, calls = 1), paid)
        }

    @Test
    fun `a failed search shows the write failed, makes no call, keeps nothing, and is made again on a tap`() =
        runTest {
            songWeb.found = null
            val provider = CountingProvider().apply { reply = { "A slow groove [S1]." } }
            val notes = notes(parts(provider))
            val first = track()
            notes.write(first, InsightItem.SONG)
            advanceUntilIdle()
            val failed = notes.item(first, InsightItem.SONG).first { it is ItemShown.Shown } as ItemShown.Shown
            assertEquals(InsightState.Failed(AiSearches.WEB_SEARCH_FAILED), failed.state)
            assertEquals(0, provider.requests)
            assertEquals(QuestionCost(searches = 1), paid)
            assertTrue(songWeb.kept.isEmpty())

            songWeb.found = listOf(WEB_LINE)
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            assertEquals(2, songWeb.searches)
            assertTrue((notes.settled(first) as ItemShown.Shown).state is InsightState.Written)
        }

    @Test
    fun `off, no search, the plain sentence, the artist's line cited, not dimmed, a single call`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            val provider =
                CountingProvider().apply {
                    reply = { "${GroundingCheck.ONLY_RELEASE_SENTENCE} Gordon Lightfoot's sound is folk [W1]." }
                }
            val notes = notes(parts(provider))
            val first = track()
            notes.write(first, InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(0, songWeb.searches)
            assertEquals(QuestionCost(calls = 1), paid)
            val shown = notes.settled(first) as ItemShown.Shown
            val written = shown.state as InsightState.Written
            assertEquals(
                listOf(SentenceStatus.HEDGE, SentenceStatus.OK),
                written.grounded.paragraphs
                    .flatten()
                    .map { it.status },
            )
            assertTrue(ARTIST_TEXT in provider.prompts.single())
        }

    // The plain-sentence card, written with *AI web search* off; then *Once*.
    private suspend fun TestScope.plainThenOnce(provider: CountingProvider): AppItemNotes {
        settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
        provider.reply = { "${GroundingCheck.ONLY_RELEASE_SENTENCE} It came out in 1974 [MB1]." }
        val notes = notes(parts(provider))
        notes.write(track(), InsightItem.SONG)
        advanceUntilIdle()
        assertEquals(1, provider.requests)
        assertTrue((notes.settled(track()) as ItemShown.Shown).state is InsightState.Written)
        settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.ONCE)
        advanceUntilIdle()
        return notes
    }

    @Test
    fun `a plain card from before the search can be made is offered, and written once with it`() =
        runTest {
            val provider = CountingProvider()
            val notes = plainThenOnce(provider)
            assertEquals(ItemShown.Offered, notes.settled(track()))
            assertEquals(0, songWeb.searches)

            provider.reply = { "A slow groove [S1]." }
            notes.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, songWeb.searches)
            assertEquals(2, provider.requests)
            assertTrue(WEB_LINE.text in provider.prompts.last())

            // No repeated write, here or after a restart.
            notes.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            val again = notes(parts(provider))
            assertTrue((again.settled(track()) as ItemShown.Shown).state is InsightState.Written)
            again.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, songWeb.searches)
            assertEquals(2, provider.requests)
        }

    @Test
    fun `a plain card whose search then finds nothing stands, with no second call`() =
        runTest {
            songWeb.found = emptyList()
            val provider = CountingProvider()
            val notes = plainThenOnce(provider)
            notes.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, songWeb.searches)
            assertEquals(1, provider.requests)
            assertTrue((notes.settled(track()) as ItemShown.Shown).state is InsightState.Written)
        }

    @Test
    fun `a plain card written with a search already kept is shown as it is`() =
        runTest {
            songWeb.found = emptyList()
            val provider =
                CountingProvider().apply {
                    reply =
                        { "${GroundingCheck.ONLY_RELEASE_SENTENCE} It came out in 1974 [MB1]." }
                }
            val notes = notes(parts(provider))
            notes.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, songWeb.searches)
            assertEquals(1, provider.requests)
            val fresh = notes(parts(provider))
            assertTrue((fresh.settled(track()) as ItemShown.Shown).state is InsightState.Written)
            fresh.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, songWeb.searches)
            assertEquals(1, provider.requests)
        }

    @Test
    fun `a closed gate before the search makes neither search nor call, and it is offered again`() =
        runTest {
            val provider = CountingProvider()
            val notes = notes(parts(provider))
            val first = track()
            open = false
            notes.write(first, InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(0, songWeb.searches)
            assertEquals(0, provider.requests)
            assertEquals(ItemShown.Offered, notes.settled(first))
        }

    private companion object {
        const val ARTIST_TEXT = "Gordon Lightfoot was a Canadian folk singer."

        val WEB_LINE =
            EvidenceDraft(
                SourceId.WEB,
                Scope.WEB,
                "Sundown rides a slow, dark groove, cut in Toronto.",
                "https://blog.example/sundown",
                Instant.parse("2026-11-06T00:00:00Z"),
            )
    }
}
