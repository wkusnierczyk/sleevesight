package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.StatedGenre
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.WikidataLabels
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * A run's labels from Wikidata (#1098): the song's and the artist's genres from Wikipedia's result,
 * the album's asked for after the evidence, each at its level, behind *Wikidata genres* and *Show
 * genres*.
 */
class TrackLabelsWikidataTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val playing = nowPlaying("I Can't Go for That (No Can Do)", ARTIST, "Private Eyes")
    private val normalized = Normalizer(JvmAccentFolder).normalize(playing)

    // The album's item, by its release group, stating pop rock.
    private class Wikidata : WikidataLookup {
        var asked = 0

        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            Lookup.Found(ids.associateWith { WdEntity(it, "Private Eyes", null, mapOf(Wd.GENRE to listOf("Q-pr"))) })

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
            Lookup.Found(
                mapOf(
                    "Q-pr" to "pop rock",
                ),
            )

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = error("not asked")

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> {
            asked++
            return Lookup.Found(if (value == GROUP) listOf("Q3") else emptyList())
        }
    }

    private val wikidata = Wikidata()

    private fun labels(settings: Settings = Settings()) =
        TrackLabels(
            musicBrainz = null,
            lastFm = null,
            settings = settings,
            key = "key",
            sink =
                object : RunSink {
                    override fun emit(event: PipelineEvent) = Unit

                    override fun update(change: (TrackState) -> TrackState) = Unit
                },
            fold = { it.lowercase() },
            wikidata = WikidataLabels(vocabulary, wikidata),
        )

    // Wikipedia's result stating blue-eyed soul for the song and soul for the artist; MusicBrainz's
    // naming the album's release group, with the recording's Pop tag.
    private val state =
        TrackState(
            playing,
            normalized,
            mapOf(
                SourceId.WIKIPEDIA to
                    SourceState.Done(
                        SourceResult(
                            Outcome.SongArticle,
                            emptyList(),
                            Duration.ZERO,
                            statedGenres =
                                listOf(
                                    StatedGenre("blue-eyed soul", GenreLevel.TRACK, null, SONG),
                                    StatedGenre("soul music", GenreLevel.ARTIST, ARTIST, ITEM),
                                    StatedGenre("pop music", GenreLevel.TRACK, null, SONG),
                                ),
                        ),
                        cached = false,
                    ),
                SourceId.MUSICBRAINZ to
                    SourceState.Done(
                        SourceResult(
                            Outcome.SongArticle,
                            emptyList(),
                            Duration.ZERO,
                            releaseDates = listOf(ReleaseDate("Private Eyes", "1981-09-01", GROUP, "Album")),
                        ),
                        cached = false,
                    ),
            ),
        )

    private fun TrackGenres.row(level: GenreLevel) = rows.single { it.level == level }.labels.map { it.genre.label }

    @Test
    fun `the song's and the artist's genres are labels at their levels, credited to Wikidata`() {
        val genres = labels().of(state)
        assertEquals(listOf("Blue-Eyed Soul", "Pop"), genres.row(GenreLevel.TRACK))
        assertEquals(listOf("Soul"), genres.row(GenreLevel.ARTIST))
        val track = genres.rows.first { it.level == GenreLevel.TRACK }
        assertTrue(
            track.labels.all { label ->
                label.credits.single().let {
                    it.source == LabelSource.WIKIDATA &&
                        it.url == SONG
                }
            },
        )
    }

    @Test
    fun `the album's genres are asked for after the evidence, by its release group`() =
        runTest {
            val run = labels()
            val later = run.later(state)
            assertEquals(listOf(LabelSource.WIKIDATA), later.map { it.source })
            run.addLater(later)
            assertEquals(listOf("Pop Rock"), run.of(state).row(GenreLevel.ALBUM))
        }

    @Test
    fun `with Wikidata genres off, or Show genres off, Wikidata gives no labels and none are asked for`() {
        for (settings in listOf(Settings(labelSources = setOf(LabelSource.LASTFM)), Settings(showGenres = false))) {
            val run = labels(settings)
            assertTrue(run.of(state).rows.isEmpty(), "$settings")
            assertTrue(run.later(state).isEmpty(), "$settings")
        }
        assertEquals(0, wikidata.asked)
    }

    @Test
    fun `with the Wikipedia switch off under Sources, Wikidata gives no labels`() {
        val off = Settings(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.WIKIPEDIA)
        assertTrue(labels(off).of(state).rows.isEmpty())
    }

    private companion object {
        const val ARTIST = "Daryl Hall & John Oates"
        const val GROUP = "rg-private-eyes"
        const val SONG = "https://www.wikidata.org/wiki/Q1"
        const val ITEM = "https://www.wikidata.org/wiki/Q2"
    }
}
