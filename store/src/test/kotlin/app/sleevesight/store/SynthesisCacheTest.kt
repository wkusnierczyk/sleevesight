package app.sleevesight.store

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.synthesis.CardKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class SynthesisCacheTest {
    private val fixture = CacheFixture()
    private val synthesis = fixture.caches.synthesis

    private val key =
        SynthesisKey(
            kind = CardKind.INSIGHT,
            entityKey = "gordon lightfoot|sundown",
            evidenceFingerprint = "9f2c",
            promptVersion = 1,
            styleHash = "built-in",
            provider = ProviderId.ANTHROPIC,
            model = "claude-haiku-5-5",
            modelKnowledge = false,
            effort = ReasoningEffort.LOW,
        )

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `a card comes back as it was kept`() {
        val writtenAt = Instant.parse("2026-10-14T11:00:00Z")
        synthesis.put(key, "Lightfoot wrote it about Cathy Smith [W1].", writtenAt)

        assertEquals(CachedSynthesis("Lightfoot wrote it about Cathy Smith [W1].", writtenAt), synthesis.get(key))
    }

    @Test
    fun `a change in any part of the key misses`() {
        synthesis.put(key, "text")
        val changed =
            mapOf(
                "kind" to key.copy(kind = CardKind.GENRE),
                "entity" to key.copy(entityKey = "gordon lightfoot"),
                "evidence fingerprint" to key.copy(evidenceFingerprint = "9f2d"),
                "prompt version" to key.copy(promptVersion = 2),
                "style" to key.copy(styleHash = "brief"),
                "provider" to key.copy(provider = ProviderId.OPENAI),
                "model" to key.copy(model = "claude-sonnet-5"),
                "model knowledge" to key.copy(modelKnowledge = true),
                "effort" to key.copy(effort = ReasoningEffort.HIGH),
                "default effort" to key.copy(effort = null),
            )

        assertNotNull(synthesis.get(key.copy()))
        changed.forEach { (part, other) -> assertNull(synthesis.get(other), part) }
    }

    @Test
    fun `cards do not expire`() {
        synthesis.put(key, "text")
        fixture.clock.advance(3650.days)
        fixture.caches.purgeExpired()

        assertNotNull(synthesis.get(key))
    }

    @Test
    fun `a rewritten card replaces the kept one`() {
        synthesis.put(key, "first")
        synthesis.put(key, "second")

        assertEquals("second", synthesis.get(key)?.text)
        assertEquals(
            1,
            fixture.caches
                .stats()
                .synthesis.entries,
        )
    }
}
