package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.withAlbum
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.synthesis.GroundedText
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

/**
 * The card paid for shown in place of a run's own insight (#1131, `withCard`): the ids of the lines its
 * run took beyond the track's own go with its evidence, not the run's now, so *Sources* marks the
 * album's among them as the album's after a rebuild that took none, and after one that numbers them
 * otherwise.
 */
class CardItemIdsTest {
    private val at = Instant.parse("2026-11-10T12:00:00Z")
    private val playing =
        NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", album = "Sundown", player = "com.spotify.music")
    private val release = draft(SourceId.PLAYER, "This recording is on the release \"Sundown\".", Scope.RELEASE)
    private val albumLine = draft(SourceId.WIKIPEDIA, "Sundown is his tenth album.", Scope.RELEASE)
    private val songPage = draft(SourceId.WIKIPEDIA, "Sundown is a song.", Scope.SONG)
    private val album = EvidenceSet.of(listOf(albumLine), JvmHasher)

    private fun draft(
        source: SourceId,
        text: String,
        scope: Scope,
    ) = EvidenceDraft(source, scope, text, null, at)

    private fun card(items: List<EvidenceDraft>) =
        Card(
            InsightState.Written(GroundedText("It is a song.", emptyList(), allowModel = false), cached = false),
            EvidenceSet.of(listOf(release) + items, JvmHasher),
            items,
        )

    private fun track(
        evidence: EvidenceSet,
        itemIds: Set<String>,
    ) = TrackState(
        playing,
        Normalizer(JvmAccentFolder).normalize(playing),
        emptyMap(),
        evidence = evidence,
        itemIds = itemIds,
    )

    @Test
    fun aRebuildThatTookNoneShowsTheCardsLinesTheAlbumsAsTheAlbums() {
        val paid = card(listOf(albumLine, songPage))
        // The rebuild's run, of the player's release alone, took nothing.
        val shown = track(EvidenceSet.of(listOf(release), JvmHasher), emptySet()).withCard(paid)

        assertEquals(setOf("W1", "W2"), shown.itemIds)
        val sources = withAlbum(shown.evidence, album, taken = shown.itemIds)
        assertEquals(setOf("W1"), sources.album)
    }

    @Test
    fun aRunThatNumbersTheLinesOtherwiseShowsTheCardsNumbering() {
        val paid = card(listOf(albumLine))
        // Back online's run took the song's page first: the album's line is W2 in its evidence.
        val now = EvidenceSet.of(listOf(release, songPage, albumLine), JvmHasher)
        val shown = track(now, setOf("W1", "W2")).withCard(paid)

        assertEquals(setOf("W1"), shown.itemIds)
        assertEquals(setOf("W1"), withAlbum(shown.evidence, album, taken = shown.itemIds).album)
    }
}
