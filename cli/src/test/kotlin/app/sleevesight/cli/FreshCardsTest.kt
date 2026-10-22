package app.sleevesight.cli

import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.synthesis.CardKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FreshCardsTest {
    private val key =
        CardKey(
            kind = CardKind.INSIGHT,
            track = Normalizer(JvmAccentFolder).normalize(Cli.nowPlaying(1)),
            duration = null,
            evidenceFingerprint = "f",
            promptVersion = 5,
            styleHash = "s",
            provider = ProviderId.ANTHROPIC,
            model = "m",
            modelKnowledge = false,
            effort = null,
        )

    @Test
    fun `a repeated run's cache serves no card, and still keeps the ones written`() {
        val kept = mutableMapOf<CardKey, String>()
        val stored =
            object : PipelineCache by PipelineCache.None {
                override suspend fun card(key: CardKey): String? = kept[key]

                override suspend fun keep(
                    key: CardKey,
                    text: String,
                ) {
                    kept[key] = text
                }
            }
        val fresh = FreshCards(stored)
        runBlocking {
            fresh.keep(key, "An insight [MB1].")
            assertEquals("An insight [MB1].", stored.card(key))
            assertNull(fresh.card(key))
        }
    }
}
