package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which of MusicBrainz's results are about the track alone (#420), and which are provisional (#724). */
class MusicBrainzSourceTest {
    private val track = Normalizer(JvmAccentFolder).normalize("Sundown", "Gordon Lightfoot")

    private suspend fun lookup(resolution: Resolution) =
        MusicBrainzSource().lookup(track, CompletableDeferred(resolution))

    @Test
    fun `no recording and no artist is about the track alone`() =
        runTest {
            assertTrue(lookup(Resolution.NONE).trackOnly)
        }

    @Test
    fun `a result naming the artists, or rejecting a recording by another, is about the artists`() =
        runTest {
            val resolved = lookup(Resolution(artists = listOf(ResolvedArtist("Gordon Lightfoot", "mbid-gordon"))))
            assertEquals(listOf(ResolvedArtist("Gordon Lightfoot", "mbid-gordon")), resolved.labelArtists)
            assertFalse(resolved.trackOnly)
            assertFalse(lookup(Resolution(artists = listOf(ResolvedArtist("Gordon Lightfoot", null)))).trackOnly)
            val namesakes = Outcome.Rejected("several MusicBrainz artists named Reem have a matching recording")
            assertFalse(lookup(Resolution(outcome = namesakes)).trackOnly)
        }

    @Test
    fun `a resolution whose lookup of the original's release failed gives a provisional result`() =
        runTest {
            val resolved = Resolution(outcome = Outcome.SongArticle, recordingMbid = "mb-sundown", cover = true)
            assertTrue(lookup(resolved.copy(provisional = true)).provisional)
            assertFalse(lookup(resolved).provisional)
        }
}
