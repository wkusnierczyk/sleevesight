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

    private fun entries(): Long =
        fixture.caches
            .stats()
            .synthesis.entries

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
    fun `with Keep history for at Forever, an insight is kept until evicted`() {
        fixture.settings = fixture.settings.copy(historyLifetime = null)
        synthesis.put(key, "text")
        fixture.clock.advance(3650.days)
        assertEquals(0, fixture.caches.purgeExpired())

        assertNotNull(synthesis.get(key))
    }

    @Test
    fun `an insight is served for Keep history for, and goes with the purge after it`() {
        fixture.settings = fixture.settings.copy(historyLifetime = 7.days)
        synthesis.put(key, "Lightfoot wrote it about Cathy Smith [W1].")
        fixture.clock.advance(6.days)
        assertNotNull(synthesis.get(key))

        fixture.clock.advance(1.days)
        // Not served once past it, and deleted as it is read.
        assertNull(synthesis.get(key))
        assertEquals(0, entries())

        // One past it that is not read goes with the purge.
        synthesis.put(key, "Lightfoot wrote it about Cathy Smith [W1].", fixture.clock.now - 8.days)
        val recent = key.copy(entityKey = "gordon lightfoot|carefree highway")
        synthesis.put(recent, "text", fixture.clock.now - 6.days)
        assertEquals(1, fixture.caches.purgeExpired())
        assertNull(synthesis.get(key))
        assertNotNull(synthesis.get(recent))
    }

    @Test
    fun `a shorter Keep history for drops the older insights at once, and the purge deletes them`() {
        // Written under the default, 90 days, and well within it.
        synthesis.put(key, "Lightfoot wrote it about Cathy Smith [W1].")
        fixture.clock.advance(30.days)
        val older = key.copy(entityKey = "gordon lightfoot|if you could read my mind")
        synthesis.put(older, "text", fixture.clock.now - 31.days)
        val recent = key.copy(entityKey = "gordon lightfoot|carefree highway")
        synthesis.put(recent, "text", fixture.clock.now - 3.days)
        assertNotNull(synthesis.get(key))

        fixture.settings = fixture.settings.copy(historyLifetime = 7.days)
        // Not served any longer, under the setting as it is now; the purge deletes the one not read.
        assertNull(synthesis.get(key))
        assertEquals(1, fixture.caches.purgeExpired())
        assertNotNull(synthesis.get(recent))
        assertEquals(1, entries())
        fixture.assertTotals()
    }

    @Test
    fun `a genre's reason is kept for Keep history for, and goes with the purge after it`() {
        val reason = key.copy(kind = CardKind.GENRE)
        fixture.settings = fixture.settings.copy(historyLifetime = 7.days)
        synthesis.put(reason, "It is folk [W1].")
        synthesis.put(key, "text")
        fixture.clock.advance(6.days)
        assertNotNull(synthesis.get(reason))

        fixture.clock.advance(1.days)
        // Not served once past it, nor is the insight written with it (#643), and the purge
        // deletes a reason past it that was not read.
        assertNull(synthesis.get(reason))
        assertNull(synthesis.get(key))
        synthesis.put(reason, "It is folk [W1].", fixture.clock.now - 8.days)
        val recent = key.copy(entityKey = "gordon lightfoot|carefree highway")
        synthesis.put(recent, "text")
        assertEquals(1, fixture.caches.purgeExpired())
        assertNotNull(synthesis.get(recent))
        assertEquals(
            1,
            fixture.caches
                .stats()
                .synthesis.entries,
        )
    }

    @Test
    fun `with Keep history for at Forever, a genre's reason is kept until evicted`() {
        val reason = key.copy(kind = CardKind.GENRE)
        fixture.settings = fixture.settings.copy(historyLifetime = null)
        synthesis.put(reason, "It is folk [W1].")
        fixture.clock.advance(3650.days)
        fixture.caches.purgeExpired()
        assertNotNull(synthesis.get(reason))
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
