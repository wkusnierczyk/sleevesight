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
    ) = PromptEvidence.of(evidence, cited, JvmHasher, allowModel = false, artistsKnown = true).ids

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
    fun `when leaving lines out would leave nothing to narrate, the whole evidence is sent`() {
        val evidence = set(formed, release)

        val all = PromptEvidence.of(evidence, setOf(ArtistFact.of(evidence.lines.first())), JvmHasher, false, true)

        assertSame(evidence, all)
    }
}
