package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.synthesis.ItemPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The album's insight on a classical track (#1130): its title is shared by other performers'
 * recordings of the same works, so the album is asked about with the track's performers, and its
 * prompt gives them, with the year, to check a line against. An album whose track names no composer
 * is asked about and prompted as before.
 */
class ClassicalAlbumInsightTest {
    private val provider = FakeProvider { FakeProvider.done("It is a 2024 album [MB1].") }
    private val albums = Albums()
    private val items =
        ItemInsights(
            AiAccess(provider, Secret("k")),
            MemoryCache(),
            JvmHasher,
            NameMatcher(JvmAccentFolder),
            albums = albums,
        )
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    // The album's one line, whatever is asked; each query kept.
    private class Albums : AlbumLookup {
        val asked = mutableListOf<AlbumQuery>()
        val renewed = mutableListOf<AlbumQuery>()

        override suspend fun lookup(query: AlbumQuery): AlbumFound {
            asked += query
            return AlbumFound(listOf(line(SourceId.MUSICBRAINZ, "“${query.title}” is an album.", Scope.RELEASE)))
        }

        override suspend fun renewWeb(query: AlbumQuery) {
            renewed += query
        }
    }

    private fun track(
        title: String,
        artists: String,
        album: String,
        year: Int? = null,
    ): TrackState {
        val playing =
            NowPlaying(
                title = title,
                artists = artists,
                album = album,
                player = "p",
                duration = 200.seconds,
                year = year,
            )
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources = emptyMap(),
            resolution = null,
            evidence = EvidenceSet.of(playerLines(playing, RETRIEVED), JvmHasher),
        )
    }

    // The album's insight on [track] written; its prompt.
    private suspend fun albumPrompt(track: TrackState): String {
        val evidence = checkNotNull(items.evidence(InsightItem.ALBUM, track, settings))
        val key = checkNotNull(items.key(InsightItem.ALBUM, track, evidence, settings))
        items.write(key, InsightItem.ALBUM, track, evidence) { _, _ -> }
        return provider.requests.last().prompt
    }

    @Test
    fun `a classical album is asked about with its performers, and its prompt checks lines against them`() =
        runTest {
            val concerto =
                track(
                    "Mozart: Piano Concerto No. 20 in D Minor, K.466: II. Romance",
                    "Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields",
                    "Mozart: Piano Concertos Nos. 20 & 23",
                    year = 2024,
                )
            val performers = listOf("Khatia Buniatishvili", "Academy of St. Martin in the Fields")
            val prompt = albumPrompt(concerto)
            assertEquals(performers, albums.asked.last().performers)
            assertEquals(
                CardEntity.Album("Mozart: Piano Concertos Nos. 20 & 23", "Wolfgang Amadeus Mozart", null, performers),
                items.entity(InsightItem.ALBUM, concerto),
            )
            assertTrue("Performers: Khatia Buniatishvili, Academy of St. Martin in the Fields\n" in prompt)
            assertTrue("Released: 2024\n" in prompt)
            assertTrue(ItemPrompt.ALBUM_RECORDING_RULE in prompt)
            // Its web lines are kept again under the same performers they were searched with.
            assertEquals(listOf(performers), albums.renewed.map { it.performers })
        }

    @Test
    fun `an album whose track names no composer is asked about and prompted as before`() =
        runTest {
            val sundown = track("Carefree Highway", "Gordon Lightfoot", "Sundown", year = 1974)
            val prompt = albumPrompt(sundown)
            val asked = albums.asked.last()
            assertEquals(
                Triple("Sundown", "Gordon Lightfoot", emptyList<String>()),
                Triple(asked.title, asked.artist, asked.performers),
            )
            assertFalse("Performers:" in prompt)
            assertFalse("Released:" in prompt)
            assertFalse(ItemPrompt.ALBUM_RECORDING_RULE in prompt)
        }
}
