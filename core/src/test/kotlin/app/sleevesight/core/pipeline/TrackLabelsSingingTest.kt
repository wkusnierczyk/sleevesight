package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.WeakEvidence
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * A run's genres weigh Instrumental against what MusicBrainz's result says of the lyrics (#996):
 * its lyrics links, its works' languages, its vocal credits, its instrumental mark.
 */
class TrackLabelsSingingTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val playing = nowPlaying("Nasnusa", "Sababa 5")
    private val normalized = Normalizer(JvmAccentFolder).normalize(playing)

    // The artist's MusicBrainz tags, as a *Retry* keeps them from the run before: Instrumental and Funk.
    private val labels =
        TrackLabels(
            musicBrainz = null,
            lastFm = null,
            settings = Settings(),
            key = "key",
            sink =
                object : RunSink {
                    override fun emit(event: PipelineEvent) = Unit

                    override fun update(change: (TrackState) -> TrackState) = Unit
                },
            fold = { it },
            kept =
                TrackLabels.LaterKept(
                    found =
                        mapOf(
                            LabelSource.MUSICBRAINZ to
                                vocabulary.labelsFor(
                                    listOf("instrumental", "funk"),
                                    level = GenreLevel.ARTIST,
                                    artist = "Sababa 5",
                                ),
                        ),
                    states = mapOf(LabelSource.MUSICBRAINZ to LaterLabels.DONE),
                    answered = setOf(LabelSource.MUSICBRAINZ),
                ),
            asking = emptySet(),
        )

    // The genres with MusicBrainz's result saying [lyrics] of the song.
    private fun genresWith(lyrics: WorkLyrics?) =
        labels.of(
            TrackState(
                playing,
                normalized,
                mapOf(
                    SourceId.MUSICBRAINZ to
                        SourceState.Done(
                            SourceResult(Outcome.SongArticle, emptyList(), Duration.ZERO, lyrics = lyrics),
                            cached = false,
                        ),
                ),
            ),
        )

    private fun weak(lyrics: WorkLyrics?) = genresWith(lyrics).weakEvidence("instrumental")

    @Test
    fun `lyrics links drop Instrumental, a language or a vocal credit leaves it weak, and the instrumental mark not`() {
        val linked = genresWith(WorkLyrics(links = listOf("https://genius.com/Sababa-5-nasnusa-lyrics")))
        assertEquals(listOf("funk"), linked.all.map { it.genre.identity })
        assertEquals(WeakEvidence.Language("jpn", "Japanese"), weak(WorkLyrics(languages = listOf("jpn"))))
        assertEquals(
            WeakEvidence.Vocalist("Yurika Hanashima"),
            weak(WorkLyrics(vocalists = listOf("Yurika Hanashima"))),
        )
        // Only `zxx`, or the performance marked instrumental: as tagged, links or not.
        assertNull(weak(WorkLyrics(languages = listOf("zxx"))))
        val marked =
            genresWith(
                WorkLyrics(links = listOf("https://genius.com/x-lyrics"), instrumental = true, vocalists = listOf("Y")),
            )
        assertTrue(marked.all.any { it.genre.identity == "instrumental" })
        assertFalse(marked.chips().single { it.label.genre.identity == "instrumental" }.weak)
        // `und` says nothing of words; with no answer, nothing is known.
        assertNull(weak(WorkLyrics(languages = listOf("und"))))
        assertNull(weak(null))
    }
}
