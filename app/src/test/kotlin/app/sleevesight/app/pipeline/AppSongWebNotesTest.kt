package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.ai.Usage
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
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.SentenceStatus
import app.sleevesight.core.synthesis.WebPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // A service that takes tools: searches once when [searching], then replies [text], stopping as [stop].
    private class Tooled(
        val searching: Boolean,
        val stop: StopReason = StopReason.END,
        var text: String = "It came out in 1974 [MB1].",
    ) : AiProvider {
        override val id: ProviderId = ProviderId.ANTHROPIC
        override val usesTools: Boolean = true
        var requests = 0

        override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> =
            flow {
                requests++
                val toSearch = request.tools.isNotEmpty() && request.toolsAllowed && request.exchanges.isEmpty()
                if (searching && toSearch) {
                    val call = ToolCall("c1", WebPrompt.TOOL_NAME, """{"query":"Sundown"}""")
                    emit(CompletionEvent.ToolUse(ToolTurn(listOf(call), "[]")))
                    emit(CompletionEvent.Done(USAGE, StopReason.TOOL_USE))
                } else {
                    emit(CompletionEvent.Delta(text))
                    emit(CompletionEvent.Done(USAGE, stop))
                }
            }
    }

    private object FailingSearches : WebSearcher {
        override suspend fun search(
            query: String,
            subject: List<String>?,
        ): WebSearchOutcome = WebSearchOutcome.Failed
    }

    private fun parts(
        provider: AiProvider,
        web: WebSearcher? = null,
    ) = MutableStateFlow<PipelineParts?>(
        PipelineParts(
            normalizer = Normalizer(JvmAccentFolder),
            adapters = emptyList(),
            resolver = TrackResolver { _, _ -> Resolution.NONE },
            hasher = JvmHasher,
            ai = AiAccess(provider, Secret("key")),
            cache = cache,
            webSearch = web,
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
    fun `the page the song's search found is a line beyond the track's own, for Key insights to take`() =
        runTest {
            val provider = CountingProvider().apply { reply = { "A slow groove [S1]." } }
            val notes = notes(parts(provider))
            val first = track()
            assertEquals(ItemShown.Offered, notes.settled(first))
            // Gathered, before its search: nothing the track has not already.
            assertEquals(emptyList<String>(), notes.foundLines(first).first()?.map { it.text })

            notes.write(first, InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(listOf(WEB_LINE.text), notes.foundLines(first).first()?.map { it.text })
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

    private suspend fun AppItemNotes.shownCard(): ItemShown.Shown {
        val shown = item(track(), InsightItem.SONG).first { it is ItemShown.Shown } as ItemShown.Shown
        assertTrue(shown.state.toString(), shown.state is InsightState.Written)
        return shown
    }

    @Test
    fun `a reply cut off with a search due is shown with its note, and not written again`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.UP_TO)
            val provider = Tooled(searching = false, stop = StopReason.MAX_TOKENS)
            val notes = notes(parts(provider, FailingSearches))
            notes.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertFalse((notes.shownCard().state as InsightState.Written).complete)
            assertTrue(songWeb.kept.isEmpty())
            notes.write(track(), InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `once, a failed search leaves the kept card shown with the failure beside it, and Off the card alone`() =
        runTest {
            songWeb.found = null
            val provider = CountingProvider()
            val notes = plainThenOnce(provider)
            notes.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, songWeb.searches)
            assertEquals(1, provider.requests)
            assertEquals(AiSearches.WEB_SEARCH_FAILED, notes.shownCard().rewriteFailed)
            assertTrue(songWeb.kept.isEmpty())

            // No repeat by itself on this play.
            notes.write(track(), InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            assertEquals(1, songWeb.searches)

            // Searched no more: the card alone, no failure, no call.
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            advanceUntilIdle()
            assertNull(notes.shownCard().rewriteFailed)
            assertEquals(1, provider.requests)

            // Back on, and *Retry*: searched again, and written from what it found.
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.ONCE)
            advanceUntilIdle()
            songWeb.found = listOf(WEB_LINE)
            provider.reply = { "A slow groove [S1]." }
            notes.write(track(), InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            assertEquals(2, songWeb.searches)
            assertEquals(2, provider.requests)
            assertNull(notes.shownCard().rewriteFailed)
        }

    @Test
    fun `up to 3 times, a failed write leaves the kept card as it was, with the failure beside it`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            val provider = Tooled(searching = true, text = "${GroundingCheck.ONLY_RELEASE_SENTENCE} It came [MB1].")
            val notes = notes(parts(provider, FailingSearches))
            notes.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            val card = (notes.shownCard().state as InsightState.Written).grounded.text

            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.UP_TO)
            advanceUntilIdle()
            provider.text = "A different reply [MB1]."
            notes.write(track(), InsightItem.SONG)
            advanceUntilIdle()
            val shown = notes.shownCard()
            assertEquals(AiSearches.WEB_SEARCH_FAILED, shown.rewriteFailed)
            assertEquals(card, (shown.state as InsightState.Written).grounded.text)
            assertTrue(songWeb.kept.isEmpty())
        }

    @Test
    fun `a card held back at a restart shows once the search is turned off, with no call`() =
        runTest {
            val provider = CountingProvider()
            val made = parts(provider)
            plainThenOnce(provider)
            val restarted = notes(made)
            assertEquals(ItemShown.Offered, restarted.settled(track()))
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            advanceUntilIdle()
            assertNull(restarted.shownCard().rewriteFailed)
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
        val USAGE = Usage(1, 1)

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
