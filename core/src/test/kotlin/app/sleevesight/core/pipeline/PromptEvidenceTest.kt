package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PromptEvidenceTest {
    private val formed = line(SourceId.WIKIPEDIA, "The band formed in 1960.", Scope.ARTIST)
    private val label = line(SourceId.WIKIDATA, "The band's label is Parlophone.", Scope.ARTIST)
    private val release = line(SourceId.PLAYER, "On the release.", Scope.RELEASE)

    private fun song(n: Int) = line(SourceId.MUSICBRAINZ, "Song fact $n.")

    private fun set(vararg drafts: EvidenceDraft) = EvidenceSet.of(drafts.toList(), JvmHasher)

    private fun sent(
        evidence: EvidenceSet,
        cited: Set<ArtistFact> = emptySet(),
        album: String? = "The Album",
    ) = PromptEvidence.of(evidence, album, cited, JvmHasher).ids

    // The player's release line as the pipeline words it (`TrackRun.playerLines`).
    private fun player(album: String) =
        line(SourceId.PLAYER, "This recording is on the release \"$album\".", Scope.RELEASE)

    // MusicBrainz's first-release line (`MusicBrainzEvidence`).
    private fun firstRelease(album: String) =
        line(
            SourceId.MUSICBRAINZ,
            "\u201CThe Song\u201D by The Band was first released in 1974, on the album \u201C$album\u201D.",
        )

    // The ids sent for a track on [album] with its release line and [others].
    private fun sentOn(
        album: String,
        vararg others: EvidenceDraft,
    ) = sent(set(*others, player(album)), album = album)

    @Test
    fun `enough lines about the track leave every artist line out, whatever the session cited`() {
        val own = (1..PromptEvidence.ENOUGH_OF_ITS_OWN).map(::song)
        val evidence = set(*own.toTypedArray(), formed, label, release)

        assertEquals(listOf("MB1", "MB2", "MB3", "MB4", "P1"), sent(evidence))
    }

    @Test
    fun `the player's release is not a line about the track`() {
        val own = (1 until PromptEvidence.ENOUGH_OF_ITS_OWN).map(::song)
        val evidence = set(*own.toTypedArray(), formed, release)

        assertEquals(evidence.ids, sent(evidence))
    }

    @Test
    fun `fewer lines leave out only the artist facts other insights of the session cited`() {
        val evidence = set(song(1), formed, label)

        assertEquals(listOf("MB1", "W1", "WD1"), sent(evidence))
        assertEquals(listOf("MB1", "WD1"), sent(evidence, setOf(ArtistFact(SourceId.WIKIPEDIA, formed.text))))
    }

    @Test
    fun `a fact fetched again with other typography is still left out, one with other words is not`() {
        // With the artist cache off (#420), the next track's artist lines are fetched afresh.
        val cited = setOf(ArtistFact.of(set(formed).lines.single()))
        val retyped = line(SourceId.WIKIPEDIA, "The  band formed in 1960", Scope.ARTIST)
        val curly = line(SourceId.WIKIDATA, "The band\u2019s label is Parlophone.", Scope.ARTIST)
        val labelCited = setOf(ArtistFact.of(set(label).lines.single()))
        val edited = line(SourceId.WIKIPEDIA, "The band formed in 1961.", Scope.ARTIST)

        assertEquals(listOf("MB1"), sent(set(song(1), retyped), cited))
        assertEquals(listOf("MB1"), sent(set(song(1), curly), labelCited))
        assertEquals(listOf("MB1", "W1"), sent(set(song(1), edited), cited))
    }

    @Test
    fun `when leaving lines out would leave nothing to narrate, the whole evidence is sent`() {
        val evidence = set(formed, release)

        val all = PromptEvidence.of(evidence, "The Album", setOf(ArtistFact.of(evidence.lines.first())), JvmHasher)

        assertSame(evidence, all)
    }

    // #424: the player's release line, restated by a line about the song.

    @Test
    fun `the player's release line is left out when a line about the song names the release`() {
        assertEquals(listOf("MB1"), sentOn("Sundown", firstRelease("Sundown")))
    }

    @Test
    fun `the release line is left out whatever else is, and with enough lines of the track's own`() {
        val own = (2..PromptEvidence.ENOUGH_OF_ITS_OWN).map(::song)
        val evidence = set(firstRelease("Sundown"), *own.toTypedArray(), formed, player("Sundown"))

        assertEquals(listOf("MB1", "MB2", "MB3", "MB4"), sent(evidence, album = "Sundown"))
        assertEquals(listOf("MB1", "W1"), sentOn("Sundown", firstRelease("Sundown"), formed))
    }

    @Test
    fun `a release no line about the song names keeps its line, as on a compilation`() {
        assertEquals(listOf("MB1", "P1"), sentOn("The Very Best Of Kiss", firstRelease("Ace Frehley")))
        assertEquals(listOf("W1", "P1"), sentOn("CV Home", line(SourceId.WIKIPEDIA, "It is a provincial anthem.")))
    }

    @Test
    fun `the release is matched folded, and less a closing bracketed part`() {
        assertEquals(listOf("MB1"), sentOn("YOU ARE NOT MY MOTHER", firstRelease("You Are Not My Mother")))
        assertEquals(listOf("MB1"), sentOn("Fisherman's Blues", firstRelease("Fisherman\u2019s Blues")))
        assertEquals(listOf("MB1"), sentOn("Rubber Soul (Remastered)", firstRelease("Rubber Soul")))
        assertEquals(
            listOf("MB1"),
            sentOn("Fisherman's Blues (Collector's Edition, 22 tracks)", firstRelease("Fisherman\u2019s Blues")),
        )
        assertEquals(listOf("MB1"), sentOn("DAMN.", firstRelease("DAMN.")))
    }

    @Test
    fun `a release is named only as a whole, not inside a longer name or with more after the bracket`() {
        assertEquals(listOf("MB1", "P1"), sentOn("Sun", firstRelease("Sundown")))
        assertEquals(listOf("MB1", "P1"), sentOn("Sundown", firstRelease("Sundowner")))
        assertEquals(listOf("MB1", "P1"), sentOn("Bang (Soundtrack) Vol. 2", firstRelease("Bang")))
    }

    @Test
    fun `only a line about the song names the release, not one about the artist, an original or the web`() {
        val artist = line(SourceId.WIKIPEDIA, "The band's best-known album is Sundown.", Scope.ARTIST)
        val original = line(SourceId.MUSICBRAINZ, "The original is on the album Sundown.", Scope.ORIGINAL_SONG)
        val web = line(SourceId.WEB, "Sundown is a 1974 album.", Scope.WEB)

        assertEquals(listOf("MB1", "W1", "P1"), sentOn("Sundown", song(1), artist))
        assertEquals(listOf("MB1", "P1"), sentOn("Sundown", original))
        assertEquals(listOf("G1", "P1"), sentOn("Sundown", web))
    }

    @Test
    fun `with no release reported, or a blank one, nothing is left out for it`() {
        val evidence = set(firstRelease("Sundown"), player("Sundown"))

        assertEquals(listOf("MB1", "P1"), sent(evidence, album = null))
        assertEquals(listOf("MB1", "P1"), sent(evidence, album = " "))
        assertEquals(listOf("MB1", "P1"), sent(evidence, album = "()"))
    }
}
