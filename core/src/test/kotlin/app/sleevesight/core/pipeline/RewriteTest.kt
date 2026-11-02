package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a rewrite changed (#854): each part of the card's key that the footer names, and the call
 * that rewrites an insight says it, keeps it with the card, and a later play reads it back.
 */
class RewriteTest {
    private val before =
        WrittenBy(5, ProviderId.ANTHROPIC, "claude-haiku-5-5", ReasoningEffort.HIGH, modelKnowledge = false, "fp1")

    @Test
    fun `nothing changed is no part`() {
        assertEquals(emptySet(), rewriteParts(before, before))
    }

    @Test
    fun `each part alone`() {
        val cases =
            mapOf(
                before.copy(evidenceFingerprint = "fp2") to RewritePart.UPDATED_SOURCES,
                before.copy(model = "claude-opus-5-5") to RewritePart.MODEL,
                before.copy(effort = ReasoningEffort.LOW) to RewritePart.MODEL,
                before.copy(effort = null) to RewritePart.MODEL,
                before.copy(provider = ProviderId.OPENAI) to RewritePart.MODEL,
                before.copy(modelKnowledge = true) to RewritePart.KNOWLEDGE_ON,
                before.copy(promptVersion = 6) to RewritePart.INSTRUCTIONS,
            )
        for ((with, part) in cases) assertEquals(setOf(part), rewriteParts(before, with), "$with")
        assertEquals(setOf(RewritePart.KNOWLEDGE_OFF), rewriteParts(before.copy(modelKnowledge = true), before))
    }

    @Test
    fun `what Look again found new is new findings, not updated sources`() {
        val with = before.copy(evidenceFingerprint = "fp2")

        assertEquals(setOf(RewritePart.NEW_FINDINGS), rewriteParts(before, with, foundNew = true))
        assertEquals(emptySet(), rewriteParts(before, with, foundNew = false), "nothing new found: no change")
    }

    @Test
    fun `what a look again found new is new findings, though another run writes from it`() {
        val with = before.copy(evidenceFingerprint = "fp2", model = "claude-opus-5-5")

        assertEquals(
            setOf(RewritePart.NEW_FINDINGS, RewritePart.MODEL),
            rewriteParts(before, with, foundIn = "fp2"),
        )
        assertEquals(
            setOf(RewritePart.NEW_FINDINGS),
            rewriteParts(before, before.copy(evidenceFingerprint = "fp2"), foundNew = false, foundIn = "fp2"),
            "a later look again finding nothing more keeps what the first found",
        )
    }

    @Test
    fun `evidence changed since what a look again found new is new findings and updated sources`() {
        val with = before.copy(evidenceFingerprint = "fp3")

        assertEquals(
            setOf(RewritePart.NEW_FINDINGS, RewritePart.UPDATED_SOURCES),
            rewriteParts(before, with, foundIn = "fp2"),
        )
    }

    @Test
    fun `several parts at once, and none where a side does not say`() {
        val with = before.copy(evidenceFingerprint = "fp2", model = "m", modelKnowledge = true, promptVersion = 6)

        assertEquals(
            setOf(
                RewritePart.UPDATED_SOURCES,
                RewritePart.MODEL,
                RewritePart.KNOWLEDGE_ON,
                RewritePart.INSTRUCTIONS,
            ),
            rewriteParts(before, with),
        )
        assertEquals(emptySet(), rewriteParts(before.copy(modelKnowledge = null, evidenceFingerprint = null), before))
    }

    @Test
    fun `the parts are kept in order and read back, an unknown key left out`() {
        val parts = setOf(RewritePart.INSTRUCTIONS, RewritePart.NEW_FINDINGS, RewritePart.MODEL)

        assertEquals("findings model instructions", RewritePart.encode(parts))
        assertEquals(parts, RewritePart.decode(RewritePart.encode(parts)))
        assertNull(RewritePart.encode(emptySet()))
        assertEquals(setOf(RewritePart.MODEL), RewritePart.decode("model gone"))
        assertEquals(emptySet(), RewritePart.decode(null))
    }

    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")

    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private fun TestScope.pipeline(cache: PipelineCache): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(FakeAdapter(SourceId.MUSICBRAINZ)),
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(FakeProvider(), Secret("sk-test")),
                cache = cache,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }

    // A control that writes, replacing [replaced] when there is one.
    private fun replacing(replaced: Rewriting?): RunControl =
        object : RunControl by RunControl.Unattended(write = true) {
            override fun rewriting(): Rewriting? = replaced
        }

    @Test
    fun `a call that rewrites says what changed, keeps it with the card, and a later play reads it back`() =
        runTest {
            val cache = MemoryCache()
            val pipeline = pipeline(cache)
            val first = assertIs<InsightState.Written>(pipeline.run(sundown, write = true).insight)
            assertEquals(emptySet(), first.rewritten, "written the first time")

            settings = settings.withModel(ProviderId.ANTHROPIC, "claude-other")
            val rewrite = pipeline.run(sundown, replacing(Rewriting(checkNotNull(first.writtenBy))))
            val rewritten = assertIs<InsightState.Written>(rewrite.insight)
            assertEquals(setOf(RewritePart.MODEL), rewritten.rewritten)
            assertEquals(rewritten.writtenBy, rewrite.writesWith)

            val later = assertIs<InsightState.Written>(pipeline.run(sundown, write = true).insight)
            assertTrue(later.cached)
            assertEquals(setOf(RewritePart.MODEL), later.rewritten)
        }

    @Test
    fun `a call that writes the first insight says nothing changed`() =
        runTest {
            val written = assertIs<InsightState.Written>(pipeline(MemoryCache()).run(sundown, replacing(null)).insight)

            assertEquals(emptySet(), written.rewritten)
        }
}
