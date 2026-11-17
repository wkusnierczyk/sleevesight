package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.pipeline.FoundAs
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Composer and Artist panels' people on a track looked up in another spelling (#1293): split by
 * MusicBrainz's roles, as the rows split them, though the sources were asked in another spelling.
 */
class SpelledPeopleTest {
    private val playing =
        NowPlaying(
            title = "Nessun doma - Turandot",
            artists = "Giacomo Puccini, Luciano Pavarotti",
            player = Settings.SPOTIFY,
        )
    private val played = normalizer.normalize(playing)
    private val roles = ArtistRoles(listOf("Giacomo Puccini"), listOf("Luciano Pavarotti"), classical = true)
    private val spelled =
        TrackState(
            playing,
            normalizer.normalize(playing.copy(title = "Nessun dorma")),
            sources = emptyMap(),
            resolution = Resolution(Outcome.SongArticle, recordingMbid = "rec", roles = roles),
            foundAs = FoundAs("Nessun dorma", playing.artists, played, "Claude Haiku 5.5"),
        )

    @Test
    fun theComposerPageOfASpelledTrackKeepsMusicBrainzsRoles() {
        val split = pageSplit(spelled, InsightItem.COMPOSER)
        assertEquals(listOf("Giacomo Puccini"), split?.composers)
        assertEquals("Luciano Pavarotti", split?.lead)
        assertEquals(split, pageSplit(spelled, InsightItem.ARTIST))
        assertNull(pageSplit(spelled, InsightItem.SONG))
    }
}
