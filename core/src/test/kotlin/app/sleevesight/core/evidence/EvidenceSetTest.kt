package app.sleevesight.core.evidence

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.fixtures.BelowSoundtrack
import app.sleevesight.fixtures.RegressionCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class EvidenceSetTest {
    private val at = Instant.parse("2026-10-13T12:00:00Z")

    // Track 1, Sundown: rich data from every source, as the simulator turns it into lines.
    private val retrieval: Map<SourceId, List<EvidenceDraft>> =
        BelowSoundtrack.factsFor(RegressionCase.RICH_DATA.tracks.single()).let { facts ->
            mapOf(
                SourceId.WIKIPEDIA to
                    facts.wikipedia.facts.map { draft(SourceId.WIKIPEDIA, Scope.SONG, it, facts.wikipedia.url) },
                SourceId.LASTFM to
                    facts.lastFm.facts.map {
                        draft(
                            SourceId.LASTFM,
                            Scope.ARTIST,
                            it,
                            facts.lastFm.url,
                        )
                    },
                SourceId.WEB to
                    facts.web.results.flatMap { result ->
                        result.facts.map { draft(SourceId.WEB, Scope.WEB, it, result.url) }
                    },
                SourceId.PLAYER to
                    listOf(draft(SourceId.PLAYER, Scope.RELEASE, "From the release Sundown (1974).", null)),
            )
        }

    private fun draft(
        source: SourceId,
        scope: Scope,
        text: String,
        url: String?,
    ) = EvidenceDraft(source, scope, text, url, at)

    private fun set(drafts: List<EvidenceDraft>) = EvidenceSet.of(drafts, JvmHasher)

    private fun landingIn(vararg order: SourceId) = set(order.flatMap { retrieval.getValue(it) })

    @Test
    fun `nothing to narrate is no line, or the player's release alone without model knowledge and the artists`() {
        for (allowModel in listOf(false, true)) {
            for (artistsKnown in listOf(false, true)) {
                val case = "model knowledge $allowModel, artists known $artistsKnown"
                assertTrue(set(emptyList()).nothingToNarrate(allowModel, artistsKnown), "no line, $case")
                assertFalse(landingIn(SourceId.WEB).nothingToNarrate(allowModel, artistsKnown), case)
                assertFalse(landingIn(SourceId.WEB, SourceId.PLAYER).nothingToNarrate(allowModel, artistsKnown), case)
                // The release alone is narrated only with model knowledge on and the artists known (#204).
                assertEquals(
                    !(allowModel && artistsKnown),
                    landingIn(SourceId.PLAYER).nothingToNarrate(allowModel, artistsKnown),
                    "the release alone, $case",
                )
            }
        }
    }

    @Test
    fun `a set without some lines keeps each kept line's id, and is itself when none is left out`() {
        val full = EvidenceSet.of(retrieval.values.flatten(), JvmHasher)
        val artist =
            full.lines
                .filter { it.scope == Scope.ARTIST }
                .map { it.id }
                .toSet()

        val less = full.without(JvmHasher) { it.id in artist }

        assertEquals(full.lines.filter { it.id !in artist }, less.lines)
        assertTrue(artist.isNotEmpty() && less.ids.none { it in artist })
        assertNotEquals(full.fingerprint, less.fingerprint)
        assertTrue(full.without(JvmHasher) { false } === full)
    }

    @Test
    fun `ids are the source tag and a position within the source`() {
        val evidence = landingIn(SourceId.WEB, SourceId.PLAYER, SourceId.LASTFM, SourceId.WIKIPEDIA)
        assertEquals(listOf("W1", "W2", "W3", "W4", "W5", "W6"), evidence.ids.filter { it.startsWith("W") })
        assertEquals(listOf("L1", "L2", "L3"), evidence.ids.filter { it.startsWith("L") })
        assertEquals((1..8).map { "G$it" }, evidence.ids.filter { it.startsWith("G") })
        assertEquals(listOf("P1"), evidence.ids.filter { it.startsWith("P") })
        // Sorted by source, each source's own order kept.
        assertEquals(retrieval.getValue(SourceId.WIKIPEDIA).first().text, evidence["W1"]?.text)
        assertEquals(retrieval.getValue(SourceId.WIKIPEDIA).last().text, evidence["W6"]?.text)
        assertEquals(listOf("W", "L", "G", "P"), evidence.lines.map { it.source.tag }.distinct())
        assertNull(evidence["W7"])
    }

    @Test
    fun `the same retrieval gives the same ids and fingerprint, whatever order the sources land in`() {
        val one = landingIn(SourceId.WIKIPEDIA, SourceId.LASTFM, SourceId.WEB, SourceId.PLAYER)
        val other = landingIn(SourceId.PLAYER, SourceId.WEB, SourceId.WIKIPEDIA, SourceId.LASTFM)
        assertEquals(one.lines, other.lines)
        assertEquals(one.fingerprint, other.fingerprint)
        assertEquals(one, other)
        assertEquals(64, one.fingerprint.length)
    }

    @Test
    fun `a source's ids do not depend on the other sources`() {
        val alone = landingIn(SourceId.LASTFM)
        val all = landingIn(SourceId.WIKIPEDIA, SourceId.LASTFM, SourceId.WEB)
        assertEquals(alone.lines, all.lines.filter { it.source == SourceId.LASTFM })
    }

    @Test
    fun `any change to a line changes the fingerprint`() {
        val drafts = retrieval.values.flatten()
        val base = set(drafts).fingerprint
        val changes =
            mapOf(
                "text" to drafts.mapIndexed { i, d -> if (i == 0) d.copy(text = d.text + " ") else d },
                "scope" to drafts.mapIndexed { i, d -> if (i == 0) d.copy(scope = Scope.ARTIST) else d },
                "a line dropped" to drafts.drop(1),
                "a line added" to drafts + draft(SourceId.WEB, Scope.WEB, "One more.", null),
                "two lines swapped" to listOf(drafts[1], drafts[0]) + drafts.drop(2),
                "a line moved to another source" to
                    drafts.mapIndexed { i, d -> if (i == 0) d.copy(source = SourceId.WIKIDATA) else d },
            )
        for ((change, changed) in changes) {
            assertNotEquals(base, set(changed).fingerprint, change)
        }
    }

    @Test
    fun `the URL and the retrieval time are not part of the fingerprint`() {
        val drafts = retrieval.values.flatten()
        val refetched =
            drafts.map {
                it.copy(url = "https://example.org/", retrievedAt = Instant.parse("2026-11-01T00:00:00Z"))
            }
        assertEquals(set(drafts).fingerprint, set(refetched).fingerprint)
    }

    @Test
    fun `field boundaries are part of the fingerprint`() {
        val one = set(listOf(draft(SourceId.WEB, Scope.WEB, "a:b", null), draft(SourceId.WEB, Scope.WEB, "c", null)))
        val other = set(listOf(draft(SourceId.WEB, Scope.WEB, "a", null), draft(SourceId.WEB, Scope.WEB, "b:c", null)))
        assertNotEquals(one.fingerprint, other.fingerprint)
    }

    // Pins the encoding, the source order, the tags and the scope keys: the fingerprint is part of
    // a persisted cache key. The value was computed independently of this code, in Python, from the
    // encoding documented on `EvidenceSet`.
    @Test
    fun `the fingerprint format is fixed`() {
        val evidence =
            set(
                listOf(
                    draft(SourceId.LASTFM, Scope.ARTIST, "Canadian singer-songwriter.", null),
                    draft(SourceId.WIKIPEDIA, Scope.SONG, "Released in 1974 on Reprise.", null),
                    draft(SourceId.WIKIPEDIA, Scope.ORIGINAL_SONG, "Written by Gené.", null),
                ),
            )
        assertEquals(listOf("W1", "W2", "L1"), evidence.ids)
        assertEquals("cd591087279e96ba2aa214f2410314ed882d0a163bb8630e86b23c5624109d21", evidence.fingerprint)
    }

    @Test
    fun `the source order covers every source`() {
        assertEquals(SourceId.entries.toSet(), EvidenceSet.SOURCE_ORDER.toSet())
        assertEquals(SourceId.entries.size, EvidenceSet.SOURCE_ORDER.size)
    }

    @Test
    fun `a lone surrogate becomes U+FFFD when the draft is made`() {
        val high = draft(SourceId.WEB, Scope.WEB, "a\uD800", null)
        val low = draft(SourceId.WEB, Scope.WEB, "a\uDC01", null)
        assertEquals("a\uFFFD", high.text)
        assertEquals("a\uFFFD", low.text)
        assertEquals("a\uFFFD", high.copy(text = "a\uD801").text)
        // A pair is kept.
        assertEquals("a\uD83C\uDFB5", draft(SourceId.WEB, Scope.WEB, "a\uD83C\uDFB5", null).text)
        // The fingerprint hashes the stored text, so it is the same on every platform, and differs
        // from a literal `?`.
        val evidence = set(listOf(high))
        assertEquals("a\uFFFD", evidence["G1"]?.text)
        assertEquals("e83ba8ff851937cc00fe3aa062d69290ede7e357b018cf1779d891cb8669fdf0", evidence.fingerprint)
        assertNotEquals(evidence.fingerprint, set(listOf(draft(SourceId.WEB, Scope.WEB, "a?", null))).fingerprint)
    }

    @Test
    fun `an empty set has a fingerprint too`() {
        val empty = set(emptyList())
        assertEquals(emptyList(), empty.ids)
        // SHA-256 of no bytes.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.fingerprint)
    }

    @Test
    fun `the JVM hasher is SHA-256`() {
        val digest = JvmHasher.sha256("abc".encodeToByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )
    }
}
