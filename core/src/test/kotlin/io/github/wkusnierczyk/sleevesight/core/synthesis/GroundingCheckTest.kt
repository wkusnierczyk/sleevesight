package io.github.wkusnierczyk.sleevesight.core.synthesis

import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceSet
import io.github.wkusnierczyk.sleevesight.core.platform.JvmHasher
import io.github.wkusnierczyk.sleevesight.fixtures.Fixtures
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
     * (`grounding/ground-check.js`, whose output is `replies.checked.json`), gives the same result.
     */
    @Test
    fun `the check agrees with the simulator on every stored reply`() {
        val replies = json.decodeFromString<List<Reply>>(Fixtures.text("grounding/replies.json"))
        val expected = json.decodeFromString<List<Expected>>(Fixtures.text("grounding/replies.checked.json"))
        assertEquals(replies.map { it.name }, expected.map { it.name }, "regenerate replies.checked.json")
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

    /** The stored outputs came from this copy of the simulator's code; a change there means regenerating them. */
    @Test
    fun `the simulator's check is the one the stored outputs came from`() {
        val html = Fixtures.text("simulator/simulator.html")
        val code = html.substring(html.indexOf("function splitSentences(text)"), html.indexOf("const countChecks"))
        assertEquals(Fixtures.text("grounding/simulator-ground-check.js.txt"), code)
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
}
