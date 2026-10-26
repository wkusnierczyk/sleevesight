package app.sleevesight.app

import androidx.test.core.app.ApplicationProvider
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.ArtistFact
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.store.ArtistResolution
import app.sleevesight.store.CacheStats
import app.sleevesight.store.SynthesisKey
import app.sleevesight.store.TableStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * *Clear history* as the app wires it (#547): the settings screen's `historyRetention.clear`
 * deletes everything kept about the tracks played, each store emptied through that one path, and
 * keeps the settings; the caches work again after it.
 */
@RunWith(RobolectricTestRunner::class)
class ClearHistoryWiringTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val graph get() = app.graph

    private val key =
        SynthesisKey(CardKind.INSIGHT, "a|x", "fp", 1, "style", ProviderId.ANTHROPIC, "model", false, null)
    private val part = ArtistPart("test.lastfm", 1, 1, setOf(SourceId.LASTFM))
    private val fact = ArtistFact(SourceId.WIKIPEDIA, "The band formed in 1960.")

    /** The first run acknowledged and the settings read, with [Settings.historyLifetime] at a week. */
    private fun ready() {
        FirstRunAcknowledgement(app).acknowledge()
        runBlocking { graph.settings.update { it.copy(historyLifetime = 7.days) } }
        idleUntil("the settings read") { graph.latestSettings.value?.historyLifetime == 7.days }
    }

    /** Something kept in each store *Clear history* empties. */
    private fun keepSomethingEverywhere() {
        val now = Clock.System.now()
        val sundown = NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", player = SPOTIFY)
        graph.history.started(sundown, now, 30.minutes) { false }
        val found = SourceResult(Outcome.None, emptyList(), 100.milliseconds)
        graph.caches.retrieval.put(SourceId.MUSICBRAINZ, "a|x", 1, found)
        graph.caches.artists.put("a", ArtistResolution.None)
        graph.caches.artistResults.put(part, "a", "v")
        graph.caches.synthesis.put(key, "text")
        runBlocking { graph.sessionFacts.cite("a|x", setOf(fact)) }
        graph.diagnostics.log.record(PipelineEvent.Superseded("Sundown"))
    }

    private fun clear(): Boolean {
        val cleared = graph.historyRetention.clear()
        idleUntil("the clear done") { cleared.isCompleted }
        return runBlocking { cleared.await() }
    }

    @Test
    fun clearHistoryEmptiesEveryStoreAndKeepsTheSettings() {
        ready()
        keepSomethingEverywhere()
        val kept = graph.caches.stats()
        val entries = listOf(kept.retrieval, kept.artists, kept.artistResults, kept.synthesis).map { it.entries }
        assertEquals(listOf(1L, 1L, 1L, 1L), entries)

        assertTrue(clear())

        assertEquals(emptyList<Any>(), graph.history.latestSession())
        val empty = TableStats(0, 0)
        assertEquals(CacheStats(empty, empty, empty, empty), graph.caches.stats())
        assertEquals(emptySet<ArtistFact>(), runBlocking { graph.sessionFacts.citedByOthers("b|y") })
        assertEquals(emptyList<Any>(), graph.diagnostics.log.entries.value)
        assertEquals(7.days, graph.settings.now().historyLifetime)
    }

    @Test
    fun theCachesKeepWorkingAfterTheClear() {
        ready()
        keepSomethingEverywhere()
        assertTrue(clear())

        assertNull(graph.caches.synthesis.get(key))
        graph.caches.synthesis.put(key, "again")
        val again = graph.caches.synthesis.get(key)
        assertEquals("again", again?.text)
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
    }
}
