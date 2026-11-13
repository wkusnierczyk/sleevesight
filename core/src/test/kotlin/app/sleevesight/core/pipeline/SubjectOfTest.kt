package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The names a track's cards are written about (#1036), which the support check counts as backed: a
 * genre's reason uses them too, the artists its title names among them (Claude's review, PR #1238).
 */
class SubjectOfTest {
    @Test
    fun `a track's subject has its title, album, artists and the artists its title names`() {
        val playing =
            NowPlaying(
                title = "Bach: Partita No. 2 in D Minor, BWV 1004: V. Chaconne (Arr. Busoni)",
                artists = "Johann Sebastian Bach, Ferruccio Busoni, Hélène Grimaud",
                album = "Bach Recital",
                player = "p",
            )
        val track = TrackState(playing, Normalizer(JvmAccentFolder).normalize(playing), emptyMap())
        val subject = subjectOf(track)
        for (name in listOf(playing.title, "Bach Recital") + track.normalized.titleArtists + track.normalized.artists) {
            assertTrue(name in subject, "$name in $subject")
        }
        assertTrue(track.normalized.titleArtists.isNotEmpty())
    }
}
