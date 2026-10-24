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
    ) = PromptEvidence.of(evidence, cited, JvmHasher).ids

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

        val all = PromptEvidence.of(evidence, setOf(ArtistFact.of(evidence.lines.first())), JvmHasher)

        assertSame(evidence, all)
    }
}
