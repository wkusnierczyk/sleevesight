package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.fixtures.Fixtures
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
     * Each reply in `grounding/replies.json`, checked here and by the simulator's own code
     * (`grounding/ground-check.js`, whose output is `replies.checked.json`), gives the same result,
     * but for the documented deviations in [DEVIATIONS].
     */
    @Test
    fun `the check agrees with the simulator on every stored reply`() {
        val replies = json.decodeFromString<List<Reply>>(Fixtures.text("grounding/replies.json"))
        val expected = json.decodeFromString<List<Expected>>(Fixtures.text("grounding/replies.checked.json"))
        assertEquals(replies.map { it.name }, expected.map { it.name }, "regenerate replies.checked.json")
        assertEquals(DEVIATIONS, replies.map { it.name }.filter { it in DEVIATIONS }.toSet(), "a deviation is gone")
        for ((reply, want) in replies.zip(expected).filter { it.first.name !in DEVIATIONS }) {
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

    /** The stored outputs came from this copy of the simulator's code; a change there means regenerating them. */
    @Test
    fun `the simulator's check is the one the stored outputs came from`() {
        val html = Fixtures.text("simulator/simulator.html")
        val code = html.substring(html.indexOf("function splitSentences(text)"), html.indexOf("const countChecks"))
        assertEquals(Fixtures.text("grounding/simulator-ground-check.js.txt"), code)
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

    @Test
    fun `a stop after an ordinary word still ends a sentence`() {
        val reply = "It went gold in the US. Plans changed. [W1] Then it went to No. 1 (vs. two). [W2]"
        assertEquals(
            listOf("It went gold in the US.", "Plans changed. [W1]", "Then it went to No. 1 (vs. two). [W2]"),
            GroundingCheck.splitSentences(reply),
        )
    }

    /** The prompt's fixed sentence for a gap is a hedge, as written; other wordings as the simulator's (#46). */
    @Test
    fun `the prompt's gap sentence is a hedge, other wordings as before`() {
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
                "It was covered. [W99] He was Canadian. [M]"
        val written = assertIs<InsightReply.Written>(GroundingCheck.check(reply, evidence, allowModel = true))
        assertEquals(GroundingCounts(ok = 1, model = 1, hedge = 1, uncited = 1, unknown = 1), written.grounded.counts)
        assertEquals(5, written.grounded.counts.total)
    }

    @Test
    fun `no evidence is a reply of its own`() {
        val empty = EvidenceSet.of(emptyList(), JvmHasher)
        assertEquals(InsightReply.NoEvidence, GroundingCheck.check(" NO_EVIDENCE ", empty, allowModel = false))
    }

    private companion object {
        /** Stored replies the check reads differently from the simulator, on purpose. */
        val DEVIATIONS = setOf("abbreviation splits")
    }
}
