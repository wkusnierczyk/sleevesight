package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/**
 * A track's roles as the run knows them (#1180, `TrackState.roles`): the resolution's, else MusicBrainz's
 * result kept from an earlier play (a replay makes no resolution), else the title's composer lead.
 */
class TrackRolesTest {
    private val playing =
        NowPlaying(
            title = "Turandot: \"Nessun dorma!\"",
            artists = "Giacomo Puccini, Luciano Pavarotti, Zubin Mehta, Los Angeles Philharmonic",
            album = null,
            duration = null,
            player = "com.spotify.music",
        )
    private val normalized = Normalizer(JvmAccentFolder).normalize(playing)
    private val told =
        ArtistRoles(listOf("Giacomo Puccini"), listOf("Luciano Pavarotti", "Zubin Mehta", "Los Angeles Philharmonic"))

    private fun kept(roles: ArtistRoles?) =
        mapOf(
            SourceId.MUSICBRAINZ to
                SourceState.Done(
                    SourceResult(Outcome.SongArticle, emptyList(), Duration.ZERO, roles = roles),
                    cached = true,
                ),
        )

    @Test
    fun `the resolution's roles, else those kept with MusicBrainz's result, else the title's`() {
        val resolved = TrackState(playing, normalized, emptyMap(), resolution = Resolution(roles = told))
        assertEquals(told, resolved.roles)
        assertEquals("Luciano Pavarotti", resolved.roles.lead(normalized))
        // A replay served from the cache: no resolution, the roles kept with MusicBrainz's result.
        val replay = TrackState(playing, normalized, kept(told))
        assertEquals(told, replay.roles)
        // Nothing tells: no composer, the first credited artist leads, as before.
        val untold = TrackState(playing, normalized, kept(null))
        assertEquals(ArtistRoles(emptyList(), normalized.artists), untold.roles)
        assertEquals("Giacomo Puccini", untold.roles.lead(normalized))
    }
}
