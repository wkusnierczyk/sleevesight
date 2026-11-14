package app.sleevesight.core.genres

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A track's genre Wikidata states (#1098) is a stated fact, not a count of votes: never weak for few
 * votes, and a weak genre's line never names Last.fm when Last.fm's tags do not give it.
 */
class WikidataWeakEvidenceTest {
    private fun label(
        tag: String,
        vararg sources: LabelSource,
        votes: Int? = null,
    ) = TrackLabel(
        GenreLabel(tag, tag.replaceFirstChar(Char::uppercase), tag, level = GenreLevel.TRACK, votes = votes),
        sources.map { LabelCredit(it, null) },
    )

    private fun track(vararg labels: TrackLabel) =
        TrackGenres(listOf(GenreRow(GenreLevel.TRACK, null, labels.toList())))

    @Test
    fun `a track's genre only Wikidata gives, which no other row has, is not weak`() {
        val genres = track(label("blueeyedsoul", LabelSource.WIKIDATA))
        assertFalse(genres.chips().single().weak)
        assertNull(genres.weakEvidence("blueeyedsoul"))
    }

    @Test
    fun `a genre Wikidata states beside a tag with few votes is not weak either`() {
        val genres =
            track(
                label("disco", LabelSource.MUSICBRAINZ, LabelSource.WIKIDATA, votes = 1),
                label("funk", LabelSource.LASTFM, LabelSource.WIKIDATA),
            )
        assertFalse(genres.chips().any { it.weak })
    }

    @Test
    fun `with Last fm absent, a weak genre's line names MusicBrainz, never Last fm`() {
        val genres =
            track(label("dance", LabelSource.MUSICBRAINZ, votes = 1), label("soul", LabelSource.WIKIDATA))
        assertEquals(
            WeakEvidence.FewVotes(musicBrainz = true, artists = 0, album = false),
            genres.weakEvidence("dance"),
        )
        assertNull(genres.weakEvidence("soul"))
    }
}
