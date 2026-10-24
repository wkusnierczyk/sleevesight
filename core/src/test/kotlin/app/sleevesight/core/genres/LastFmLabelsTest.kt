package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.testing.MemoryArtistResults
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Last.fm's track and album labels (#439): the levels, their pages, and what is kept. */
class LastFmLabelsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val asked = mutableListOf<String>()
    private var album: Lookup<List<String>> = Lookup.Found(listOf("folk rock", "1974", "folk"))

    private val tags =
        object : ReleaseTags {
            override suspend fun trackTags(
                artist: String,
                title: String,
            ): Lookup<List<String>> = Lookup.Found(listOf("soft rock", "seen live")).also { asked += "track $title" }

            override suspend fun albumTags(
                artist: String,
                album: String,
            ): Lookup<List<String>> = this@LastFmLabelsTest.album.also { asked += "album $album" }

            override fun trackPage(
                artist: String,
                title: String,
            ) = "track page"

            override fun albumPage(
                artist: String,
                album: String,
            ) = "album page"
        }

    @Test
    fun `the track's and the album's tags through the vocabulary, each linking its page`() =
        runTest {
            val labels = LastFmLabels(tags, vocabulary)
            val track = labels.trackLabels("Gordon Lightfoot", "Sundown")
            assertEquals(listOf("Soft Rock"), track.map { it.label })
            assertTrue(track.all { it.level == GenreLevel.TRACK && it.url == "track page" && it.artist == null })
            val album = labels.albumLabels("Gordon Lightfoot", "Sundown")
            assertEquals(listOf("Folk Rock", "Folk"), album.map { it.label })
            assertTrue(album.all { it.level == GenreLevel.ALBUM && it.url == "album page" })
        }

    @Test
    fun `an album's tags are kept, in the artist cache when there is one, a failure is not`() =
        runTest {
            album = Lookup.Failed("Offline")
            val kept = MemoryArtistResults()
            val labels = LastFmLabels(tags, vocabulary, artists = kept)
            assertEquals(emptyList(), labels.albumLabels("Gordon Lightfoot", "Sundown"))
            album = Lookup.Found(listOf("folk"))
            labels.albumLabels("Gordon Lightfoot", "Sundown")
            labels.albumLabels("Gordon Lightfoot", "Sundown")
            LastFmLabels(tags, vocabulary, artists = kept).albumLabels("Gordon Lightfoot", "Sundown")
            assertEquals(listOf("album Sundown", "album Sundown"), asked)
            assertEquals(
                setOf(LastFmLabels.albumKey("Gordon Lightfoot", "Sundown")),
                kept.keys(LastFmLabels.ALBUM_PART),
            )

            // The track's are asked each time.
            labels.trackLabels("Gordon Lightfoot", "Sundown")
            labels.trackLabels("Gordon Lightfoot", "Sundown")
            assertEquals(2, asked.count { it.startsWith("track") })
        }
}
