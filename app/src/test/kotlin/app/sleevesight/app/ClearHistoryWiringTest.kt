package app.sleevesight.app

import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.ArtistFact
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbArtistSearch
import app.sleevesight.core.resolution.MbRecording
import app.sleevesight.core.resolution.MbRecordingDetail
import app.sleevesight.core.resolution.MbWork
import app.sleevesight.core.resolution.MusicBrainzLookup
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * *Clear history* as the app wires it (#547): the settings screen's `historyRetention.clear`
 * deletes everything kept about the tracks played, each store emptied through that one path,
 * MusicBrainz's answers in the app's wiring included, and keeps the settings, the players seen and
 * the API keys; the caches work again after it. The graph is made with stand-ins for the
 * Keystore, which Robolectric lacks, and for MusicBrainz, so nothing asks the network.
 */
@RunWith(RobolectricTestRunner::class)
class ClearHistoryWiringTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val graph get() = app.graph

    /** MusicBrainz as the stand-in answers it: nothing found, each search counted. */
    private class CountingMusicBrainz : MusicBrainzLookup {
        var searches = 0

        override suspend fun searchRecordings(
            title: String,
            artist: String,
        ): Lookup<List<MbRecording>> {
            searches++
            return Lookup.NotFound
        }

        override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> = Lookup.NotFound

        override suspend fun recordingReleases(mbid: String): Lookup<MbRecording> = Lookup.NotFound

        override suspend fun work(mbid: String): Lookup<MbWork> = Lookup.NotFound

        override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> = Lookup.NotFound
    }

    private val musicBrainz = CountingMusicBrainz()
    private val key =
        SynthesisKey(CardKind.INSIGHT, "a|x", "fp", 1, "style", ProviderId.ANTHROPIC, "model", false, null)
    private val part = ArtistPart("test.lastfm", 1, 1, setOf(SourceId.LASTFM))
    private val fact = ArtistFact(SourceId.WIKIPEDIA, "The band formed in 1960.")
    private val sundown = NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", player = SPOTIFY)

    @Before
    fun standIns() {
        app.makeGraph = { AppGraph(it, secretCipher = testCipher(), musicBrainzThrough = { musicBrainz }) }
    }

    /**
     * The first run acknowledged and the settings read, with *Keep history for* at a week and *Keep
     * what sources said about each artist* on, both stored before the acknowledgement: with the
     * switch read off, the graph clears what is kept about artists at start, which could race with
     * what a test keeps.
     */
    private fun ready() {
        runBlocking { graph.settings.update { it.copy(historyLifetime = 7.days, keepArtistResults = true) } }
        FirstRunAcknowledgement(app).acknowledge()
        idleUntil("the settings read") { graph.latestSettings.value?.historyLifetime == 7.days }
    }

    /** Asks the app's MusicBrainz, through its wiring's resolver, about [sundown]. */
    private fun resolve() =
        runBlocking { graph.wiring.resolver.resolve(Normalizer(JvmAccentFolder).normalize(sundown), null) }

    /** Something kept in each store *Clear history* empties. */
    private fun keepSomethingEverywhere() {
        graph.history.started(sundown, Clock.System.now(), 30.minutes) { false }
        val found = SourceResult(Outcome.None, emptyList(), 100.milliseconds)
        graph.caches.retrieval.put(SourceId.MUSICBRAINZ, "a|x", 1, found)
        graph.caches.artists.put("a", ArtistResolution.None)
        graph.caches.artistResults.put(part, "a", "v")
        graph.caches.synthesis.put(key, "text")
        runBlocking { graph.sessionFacts.cite("a|x", setOf(fact)) }
        graph.diagnostics.log.record(PipelineEvent.Superseded("Sundown"))
        resolve()
    }

    private fun clear(): Boolean {
        val cleared = graph.historyRetention.clear()
        idleUntil("the clear done") { cleared.isCompleted }
        return runBlocking { cleared.await() }
    }

    @Test
    fun clearHistoryEmptiesEveryStoreAndKeepsTheSettingsPlayersAndKeys() {
        ready()
        runBlocking {
            graph.settings.playerSeen(SEEN_PLAYER)
            graph.secrets.set(SecretId.ANTHROPIC, Secret("sk-ant-test"))
        }
        keepSomethingEverywhere()
        val kept = graph.caches.stats()
        val entries = listOf(kept.retrieval, kept.artists, kept.artistResults, kept.synthesis).map { it.entries }
        assertEquals(listOf(1L, 1L, 1L, 1L), entries)
        resolve()
        assertEquals("the second question answered from memory", 1, musicBrainz.searches)

        assertTrue(clear())

        assertEquals(emptyList<Any>(), graph.history.latestSession())
        val empty = TableStats(0, 0)
        assertEquals(CacheStats(empty, empty, empty, empty), graph.caches.stats())
        assertEquals(emptySet<ArtistFact>(), runBlocking { graph.sessionFacts.citedByOthers("b|y") })
        assertEquals(emptyList<Any>(), graph.diagnostics.log.entries.value)
        resolve()
        assertEquals("asked anew after the clear", 2, musicBrainz.searches)
        assertEquals(7.days, graph.settings.now().historyLifetime)
        assertEquals(false, graph.settings.now().followedPlayers[SEEN_PLAYER])
        val saved = runBlocking { graph.secrets.get(SecretId.ANTHROPIC) }
        assertEquals("sk-ant-test", saved?.reveal())
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
        const val SEEN_PLAYER = "com.example.player"
    }
}
