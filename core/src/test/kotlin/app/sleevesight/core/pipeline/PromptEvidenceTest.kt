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

    // MusicBrainz's first-release line (`MusicBrainzEvidence`), on a release of [type].
    private fun firstRelease(
        album: String,
        type: String = "album",
        title: String = "The Song",
        by: String = "The Band",
    ) = line(
        SourceId.MUSICBRAINZ,
        "\u201C$title\u201D by $by was first released in 1974, on the $type \u201C$album\u201D.",
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

    // #424: the player's release line, restated by MusicBrainz's first-release line.

    @Test
    fun `the player's release line is left out when the song first came out on that album`() {
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
    fun `a release the song did not first come out on keeps its line, as a compilation's`() {
        assertEquals(listOf("MB1", "P1"), sentOn("The Very Best Of Kiss", firstRelease("Ace Frehley")))
        assertEquals(listOf("W1", "P1"), sentOn("CV Home", line(SourceId.WIKIPEDIA, "It is a provincial anthem.")))
    }

    @Test
    fun `the names are compared folded, and less their closing bracketed parts`() {
        assertEquals(listOf("MB1"), sentOn("YOU ARE NOT MY MOTHER", firstRelease("You Are Not My Mother")))
        assertEquals(listOf("MB1"), sentOn("Fisherman's Blues", firstRelease("Fisherman\u2019s Blues")))
        assertEquals(listOf("MB1"), sentOn("Rubber Soul (Remastered)", firstRelease("Rubber Soul")))
        assertEquals(listOf("MB1"), sentOn("Sundown (Remastered) [Deluxe]", firstRelease("Sundown")))
        assertEquals(listOf("MB1"), sentOn("DAMN.", firstRelease("DAMN.")))
        assertEquals(listOf("MB1"), sentOn("(Untitled)", firstRelease("(Untitled)")))
    }

    @Test
    fun `the player's closing parts are stripped balanced, nested, after any space, while a name is left`() {
        assertEquals("Sundown", PromptEvidence.edition("Sundown (Remastered (2024))"))
        assertEquals("Sundown", PromptEvidence.edition("Sundown (Remastered)\u00A0[Deluxe]\u00A0"))
        assertEquals("(Untitled)", PromptEvidence.edition("(Untitled) (Deluxe)"))
        assertEquals("()", PromptEvidence.edition("()"))
        assertEquals("Sundown )", PromptEvidence.edition("Sundown )"))
        assertEquals(listOf("MB1"), sentOn("Sundown (Remastered (2024))", firstRelease("Sundown")))
        assertEquals(listOf("MB1"), sentOn("(Untitled) (Deluxe)", firstRelease("(Untitled)")))
        assertEquals(listOf("MB1"), sentOn("()", firstRelease("()")))
    }

    @Test
    fun `MusicBrainz's name is taken as it is, so a first release on another edition keeps the line`() {
        assertEquals(listOf("MB1", "P1"), sentOn("Sundown", firstRelease("Sundown (Live)")))
        assertEquals(listOf("MB1", "P1"), sentOn("Sundown", firstRelease("Sundown (Deluxe Edition)")))
    }

    @Test
    fun `a name is the whole album's name, not part of a longer one at either end`() {
        assertEquals(listOf("MB1", "P1"), sentOn("Greatest Hits", firstRelease("Greatest Hits II")))
        assertEquals(listOf("MB1", "P1"), sentOn("Greatest Hits", firstRelease("The Greatest Hits")))
        assertEquals(listOf("MB1", "P1"), sentOn("Sun", firstRelease("Sundown")))
        assertEquals(listOf("MB1", "P1"), sentOn("Bang (Soundtrack) Vol. 2", firstRelease("Bang")))
    }

    @Test
    fun `a short name counts only as the whole album's name`() {
        // The Beatles' "1": MusicBrainz's album “1” names it; "No. 1" in another line does not.
        assertEquals(listOf("MB1"), sentOn("1", firstRelease("1")))
        assertEquals(listOf("MB1", "P1"), sentOn("1", line(SourceId.MUSICBRAINZ, "It reached No. 1.")))
        assertEquals(listOf("MB1", "P1"), sentOn("x", firstRelease("x", title = "x", type = "single")))
    }

    @Test
    fun `the track's title and the artist credit are not the release, on a title track or a self-titled album`() {
        // A title track first released as a single: the album the player reports is another release.
        val single = firstRelease("Sundown", type = "single", title = "Sundown", by = "Gordon Lightfoot")
        val written = line(SourceId.MUSICBRAINZ, "\u201CSundown\u201D was written by Gordon Lightfoot.")
        assertEquals(listOf("MB1", "MB2", "P1"), sentOn("Sundown", single, written))
        // A self-titled album the song did not first come out on.
        assertEquals(
            listOf("MB1", "P1"),
            sentOn("Ace Frehley", firstRelease("Kiss Alive", by = "Ace Frehley")),
        )
    }

    @Test
    fun `a single, an EP or a player's single of the album's name keeps the line`() {
        assertEquals(listOf("MB1", "P1"), sentOn("Sundown", firstRelease("Sundown", type = "single")))
        assertEquals(listOf("MB1", "P1"), sentOn("Sundown", firstRelease("Sundown", type = "ep")))
        assertEquals(listOf("MB1", "P1"), sentOn("Sundown - Single", firstRelease("Sundown")))
    }

    @Test
    fun `the template's words count only in MusicBrainz's line about the song`() {
        val template = "\u201CThe Song\u201D by The Band was first released in 1974, on the album \u201CSundown\u201D."

        assertEquals(listOf("W1", "P1"), sentOn("Sundown", line(SourceId.WIKIPEDIA, template)))
        assertEquals(listOf("G1", "P1"), sentOn("Sundown", line(SourceId.WEB, template, Scope.WEB)))
        assertEquals(
            listOf("MB1", "P1"),
            sentOn("Sundown", line(SourceId.MUSICBRAINZ, template, Scope.ORIGINAL_SONG)),
        )
    }

    @Test
    fun `only MusicBrainz's first-release line names the release, not another source or line`() {
        val wikipedia = line(SourceId.WIKIPEDIA, "It is from his 1974 album Sundown.")
        val artist = line(SourceId.WIKIPEDIA, "The band's best-known album is Sundown.", Scope.ARTIST)
        val original =
            line(SourceId.MUSICBRAINZ, "The original is on the album \u201CSundown\u201D.", Scope.ORIGINAL_SONG)
        val web = line(SourceId.WEB, "Sundown is a 1974 album.", Scope.WEB)

        assertEquals(listOf("W1", "P1"), sentOn("Sundown", wikipedia))
        assertEquals(listOf("MB1", "W1", "P1"), sentOn("Sundown", song(1), artist))
        assertEquals(listOf("MB1", "P1"), sentOn("Sundown", original))
        assertEquals(listOf("G1", "P1"), sentOn("Sundown", web))
    }

    @Test
    fun `with no release reported, or a blank one, nothing is left out for it`() {
        val evidence = set(firstRelease("Sundown"), player("Sundown"))

        assertEquals(listOf("MB1", "P1"), sent(evidence, album = null))
        assertEquals(listOf("MB1", "P1"), sent(evidence, album = " "))
    }
}
