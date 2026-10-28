package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.fixtures.Fixtures
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroundingCheckTest {
    @Serializable
    private data class Reply(
        val name: String,
        val reply: String,
        val ids: List<String>,
        val allowModel: Boolean,
    )

    @Serializable
    private data class Expected(
        val name: String,
        val none: Boolean,
        val paragraphs: List<List<ExpectedSentence>>,
    )

    @Serializable
    private data class ExpectedSentence(
        @SerialName("s") val text: String,
        val cites: List<String>,
        val status: String,
    )

    private val json = Json

    /**
     * Each reply in `grounding/replies.json` gives the result stored for it in
     * `replies.checked.json`. The stored results are the check's own: a change to what it reads
     * changes them with it.
     */
    @Test
    fun `every stored reply gives its stored result`() {
        val replies = json.decodeFromString<List<Reply>>(Fixtures.text("grounding/replies.json"))
        val expected = json.decodeFromString<List<Expected>>(Fixtures.text("grounding/replies.checked.json"))
        assertEquals(replies.map { it.name }, expected.map { it.name }, "replies.checked.json, reply for reply")
        for ((reply, want) in replies.zip(expected)) {
            val got = GroundingCheck.check(reply.reply, reply.ids.toSet(), reply.allowModel)
            val actual =
                when (got) {
                    InsightReply.NoEvidence -> {
                        Expected(reply.name, none = true, paragraphs = emptyList())
                    }

                    is InsightReply.Written -> {
                        Expected(
                            reply.name,
                            none = false,
                            paragraphs =
                                got.grounded.paragraphs.map { paragraph ->
                                    paragraph.map { ExpectedSentence(it.text, it.cites, it.status.name.lowercase()) }
                                },
                        )
                    }
                }
            assertEquals(want, actual, reply.name)
        }
    }

    /** The simulator splits after `Dr.`; the check does not (#46). */
    @Test
    fun `an abbreviation before a name does not end a sentence`() {
        val reply = "Produced by Lenny Waronker with Dr. Smith on guitar. [W1]"
        assertEquals(listOf(reply), GroundingCheck.splitSentences(reply))
    }

    @Test
    fun `initials do not end a sentence`() {
        val reply =
            "It reached the U.S. Billboard Hot 100 and the top ten in the U.K. [W1] R.E.M. covered it with " +
                "J. Smith. [W2] Mary J. Blige sang it in St. John's (feat. Van Morrison). [W3]"
        assertEquals(
            listOf(
                "It reached the U.S. Billboard Hot 100 and the top ten in the U.K. [W1]",
                "R.E.M. covered it with J. Smith. [W2]",
                "Mary J. Blige sang it in St. John's (feat. Van Morrison). [W3]",
            ),
            GroundingCheck.splitSentences(reply),
        )
        val checked = GroundingCheck.check(reply, setOf("W1", "W2", "W3"), allowModel = false)
        val written = assertIs<InsightReply.Written>(checked)
        assertEquals(3, written.grounded.counts.ok)
        assertEquals(0, written.grounded.counts.uncited)
    }

    @Test
    fun `initials before a word that opens a sentence end one, so an uncited sentence shows`() {
        val reply =
            "The band toured the U.S. It reached number one. [W1] It appeared on side B. The film used it. [W2] " +
                "It reached the U.S. Billboard Hot 100. [W3]"
        assertEquals(
            listOf(
                "The band toured the U.S.",
                "It reached number one. [W1]",
                "It appeared on side B.",
                "The film used it. [W2]",
                "It reached the U.S. Billboard Hot 100. [W3]",
            ),
            GroundingCheck.splitSentences(reply),
        )
        val written =
            assertIs<InsightReply.Written>(GroundingCheck.check(reply, setOf("W1", "W2", "W3"), allowModel = false))
        assertEquals(3, written.grounded.counts.ok)
        assertEquals(2, written.grounded.counts.uncited)
    }

    @Test
    fun `an opener with a curly apostrophe ends a sentence after initials, as a straight one does`() {
        for (opener in listOf("It’s", "It's")) {
            val reply = "It was a hit in the U.S. $opener a cover. [W1]"
            assertEquals(
                listOf("It was a hit in the U.S.", "$opener a cover. [W1]"),
                GroundingCheck.splitSentences(reply),
            )
        }
    }

    /** The simulator splits after `e.g.` before a capital; the check does not, nor with marks (#574). */
    @Test
    fun `e g and i e do not end a sentence, with or without emphasis marks after them`() {
        val replies =
            listOf(
                "He covered several hits, e.g. Thriller and Billie Jean. [W1]",
                "He covered several hits, e.g. *Thriller* and *Billie Jean*. [W1]",
                "He covered several hits, e.g. **Thriller** and _Billie Jean_. [W1]",
                "It opens the B-side, i.e. The Wreck. [W1]",
                "It opens the B-side, i.e. *The Wreck*. [W1]",
                "E.g. *Thriller* sold well. [W1]",
            )
        for (reply in replies) assertEquals(listOf(reply), GroundingCheck.splitSentences(reply), reply)
    }

    @Test
    fun `etc ends a sentence before a capital, as in the simulator, so an uncited one shows`() {
        val replies =
            listOf(
                "The arrangement uses guitars, strings, etc. Lightfoot recorded it in Toronto. [W1]",
                "The arrangement uses guitars, strings, etc. It was recorded in Toronto. [W1]",
                "The arrangement uses guitars, strings, etc. *Rolling Stone* praised it. [W1]",
            )
        for (reply in replies) {
            val written = assertIs<InsightReply.Written>(GroundingCheck.check(reply, setOf("W1"), allowModel = false))
            assertEquals(
                listOf(SentenceStatus.UNCITED, SentenceStatus.OK),
                written.grounded.paragraphs
                    .flatten()
                    .map { it.status },
                reply,
            )
        }
    }

    @Test
    fun `a stop after an ordinary word still ends a sentence`() {
        val reply = "It went gold in the US. Plans changed. [W1] Then it went to No. 1 (vs. two). [W2]"
        assertEquals(
            listOf("It went gold in the US.", "Plans changed. [W1]", "Then it went to No. 1 (vs. two). [W2]"),
            GroundingCheck.splitSentences(reply),
        )
    }

    /** The simulator joins a sentence ending in a quoted title to the next; the check does not (#423). */
    @Test
    fun `a stop inside closing quotes ends a sentence when citations follow the quotes`() {
        for (end in listOf(".\"", ".”", "?'", "!’", ".'\"", ".’”")) {
            val reply = "Its title track is \"Sundown$end [MB1] It was a single. [W1]"
            assertEquals(
                listOf("Its title track is \"Sundown$end [MB1]", "It was a single. [W1]"),
                GroundingCheck.splitSentences(reply),
                end,
            )
        }
    }

    /** A title ends in a stop mid-sentence; the cost is an uncited sentence that ends in one, joined. */
    @Test
    fun `a stop inside closing quotes with no citation after them does not end a sentence`() {
        val replies =
            listOf(
                "On \"Help!\" John Lennon sings lead. [W1]",
                "The album \"Help!\" (1965) reached number one. [W1]",
                "Hendrix's \"Are You Experienced?\" (1967) was his debut. [W1]",
                "The album \"DAMN.\" (2017) won the Pulitzer Prize. [W1]",
                "It topped the \"U.S.\" Billboard chart. [W1]",
                "He called it \"Sundown.\" It charted. [W1]",
                "He called it \"Help!\" It charted. [W1]",
            )
        for (reply in replies) assertEquals(listOf(reply), GroundingCheck.splitSentences(reply))
    }

    @Test
    fun `a closing quote or apostrophe without a stop does not end a sentence`() {
        val reply = "The song \"Sundown\" Gordon wrote in 1973 opens the Beatles' Rubber Soul. [W1]"
        assertEquals(listOf(reply), GroundingCheck.splitSentences(reply))
    }

    @Test
    fun `an uncited sentence after a quoted, cited one is counted as uncited`() {
        val reply = "The title track is \"Sundown.\" [MB1] It was his biggest hit. It topped the chart. [W1]"
        val written =
            assertIs<InsightReply.Written>(GroundingCheck.check(reply, setOf("MB1", "W1"), allowModel = false))
        assertEquals(
            listOf(SentenceStatus.OK, SentenceStatus.UNCITED, SentenceStatus.OK),
            written.grounded.paragraphs
                .flatten()
                .map { it.status },
        )
        assertEquals(1, written.grounded.counts.uncited)
    }

    /**
     * Prompt version 2's fixed sentence for a gap is a hedge, as written; other wordings as the
     * simulator's (#46). Version 3 asks for none, and the check still reads it (#193).
     */
    @Test
    fun `version 2's gap sentence is a hedge, other wordings as before`() {
        val reply =
            "It was a single. [P1] Little else is documented. Beyond that, little else is documented about it. " +
                "Little more is known. Little is known about the B-side."
        val written = assertIs<InsightReply.Written>(GroundingCheck.check(reply, setOf("P1"), allowModel = false))
        assertEquals(
            listOf(
                SentenceStatus.OK,
                SentenceStatus.HEDGE,
                SentenceStatus.UNCITED,
                SentenceStatus.UNCITED,
                SentenceStatus.HEDGE,
            ),
            written.grounded.paragraphs
                .flatten()
                .map { it.status },
        )
    }

    @Test
    fun `a written reply counts its sentences by status`() {
        val evidence = FixtureEvidence.of(1)
        val reply =
            "Sundown topped the US chart. [W3] It was a hit everywhere. Little is known about the B-side. " +
                "It was covered. [W99] He was Canadian. [AI]"
        val written = assertIs<InsightReply.Written>(GroundingCheck.check(reply, evidence, allowModel = true))
        assertEquals(GroundingCounts(ok = 1, model = 1, hedge = 1, uncited = 1, unknown = 1), written.grounded.counts)
        assertEquals(5, written.grounded.counts.total)
    }

    @Test
    fun `a written reply says whether the model's own knowledge was allowed, whatever its sentences came out as`() {
        val evidence = FixtureEvidence.of(1)
        // `[AI]` beside a line that does not exist: no sentence comes out `MODEL` either way.
        val reply = "Sundown topped the US chart. [W3] He was Canadian. [AI][W99]"
        for (allowModel in listOf(true, false)) {
            val written = assertIs<InsightReply.Written>(GroundingCheck.check(reply, evidence, allowModel))
            assertEquals(0, written.grounded.counts.model)
            assertEquals(allowModel, written.grounded.allowModel)
        }
    }

    @Test
    fun `the model's own knowledge is cited AI, its source's tag`() {
        assertEquals("AI", GroundingCheck.MODEL_ID)
        assertEquals(SourceId.MODEL.tag, GroundingCheck.MODEL_ID)
        val evidence = FixtureEvidence.of(1)
        val written =
            assertIs<InsightReply.Written>(GroundingCheck.check("He was Canadian. [AI]", evidence, allowModel = true))
        assertEquals(
            listOf(SentenceStatus.MODEL),
            written.grounded.paragraphs
                .flatten()
                .map { it.status },
        )
    }

    @Test
    fun `a reply written before 0_1_8 citing M reads as citing the model's own knowledge, as AI`() {
        // Cards kept in the synthesis cache under the same prompt version still cite `[M]` (#668).
        val evidence = FixtureEvidence.of(1)
        val reply = "Sundown topped the US chart. [W3] He was Canadian. [M] It was a hit [W3][M]."
        val allowed = assertIs<InsightReply.Written>(GroundingCheck.check(reply, evidence, allowModel = true))
        val sentences = allowed.grounded.paragraphs.flatten()
        assertEquals(listOf(listOf("W3"), listOf("AI"), listOf("W3", "AI")), sentences.map { it.cites })
        assertEquals(
            listOf(SentenceStatus.OK, SentenceStatus.MODEL, SentenceStatus.MODEL),
            sentences.map { it.status },
        )
        val off = assertIs<InsightReply.Written>(GroundingCheck.check(reply, evidence, allowModel = false))
        assertEquals(
            listOf(SentenceStatus.OK, SentenceStatus.UNKNOWN, SentenceStatus.UNKNOWN),
            off.grounded.paragraphs
                .flatten()
                .map { it.status },
        )
    }

    @Test
    fun `a reply citing M, as written before 0_1_8, is told from one citing AI`() {
        assertTrue(citesLegacyModelId("He was Canadian. [M]"))
        assertTrue(citesLegacyModelId("It was a hit [W3][M]."))
        assertFalse(citesLegacyModelId("He was Canadian. [AI]"))
        assertFalse(citesLegacyModelId("It was a hit [MB1]."))
    }

    @Test
    fun `no evidence is a reply of its own`() {
        val empty = EvidenceSet.of(emptyList(), JvmHasher)
        assertEquals(InsightReply.NoEvidence, GroundingCheck.check(" NO_EVIDENCE ", empty, allowModel = false))
    }

    @Test
    fun `a streaming reply may be NO_EVIDENCE while it is a start of the marker or the marker as the check reads it`() {
        val empty = EvidenceSet.of(emptyList(), JvmHasher)
        val held =
            listOf("", " ", "N", "NO", " NO_EVI") +
                listOf("NO_EVIDENCE", "NO_EVIDENCE.", "NO_EVIDENCE: the sources say nothing.")
        for (partial in held) {
            assertTrue(GroundingCheck.mayBeNoEvidence(partial), partial)
        }
        // Each held form that is the whole marker reads as no evidence once checked.
        for (reply in held.drop(5)) assertEquals(InsightReply.NoEvidence, GroundingCheck.check(reply, empty, false))
        val shown = listOf("No", "NO single", "NO_EVIDENCES", "NO_EVIDENCE_X", "It is a song")
        for (partial in shown) assertFalse(GroundingCheck.mayBeNoEvidence(partial), partial)
    }
}
