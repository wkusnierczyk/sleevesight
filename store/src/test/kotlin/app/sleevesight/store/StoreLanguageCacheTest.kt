package app.sleevesight.store

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.LanguageAdapter
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The lookups in the track's language (#902) in the retrieval cache: each under the track's key and
 * the language, apart from the English lookup's entry and from another language's, its lines with
 * their language as kept.
 */
class StoreLanguageCacheTest {
    private val fixture = CacheFixture()
    private val cache = StorePipelineCache(fixture.caches, EmptyCoroutineContext, ArtistKeeping.ALWAYS)
    private val track = fixture.normalizer.normalize("Tum Hi Ho", "Arijit Singh")

    private val english =
        object : SourceAdapter {
            override val id: SourceId = SourceId.WIKIPEDIA

            override suspend fun lookup(
                track: NormalizedTrack,
                resolution: Deferred<Resolution>,
            ): SourceResult = error("not looked up")
        }

    private val inLanguage =
        object : LanguageAdapter {
            override val id: SourceId = SourceId.WIKIPEDIA

            override suspend fun lookup(
                query: LanguageQuery,
                items: LanguageItems,
            ): SourceResult = error("not looked up")
        }

    private fun result(
        text: String,
        language: String?,
    ) = SourceResult(
        Outcome.SongArticle,
        listOf(
            EvidenceDraft(
                SourceId.WIKIPEDIA,
                Scope.RELEASE,
                text,
                "https://hi.wikipedia.org/wiki/x",
                fixture.clock.now,
                language = language,
            ),
        ),
        12.milliseconds,
    )

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `a lookup in a language is kept under the language, apart from the English one and other languages`() =
        runBlocking {
            val hindi = result("आशिक़ी 2 एक फ़िल्म है।", "hi")
            val englishResult = result("Aashiqui 2 is a film.", null)
            cache.keep(english, track, 240.seconds, englishResult)
            cache.keep(inLanguage, "hi", track, 240.seconds, hindi)

            assertEquals(hindi, cache.result(inLanguage, "hi", track, 240.seconds))
            assertEquals(
                "hi",
                cache
                    .result(inLanguage, "hi", track, 240.seconds)!!
                    .evidence
                    .single()
                    .language,
            )
            assertEquals(englishResult, cache.result(english, track, 240.seconds))
            assertNull(cache.result(inLanguage, "mr", track, 240.seconds))
            assertNull(cache.result(inLanguage, "hi", track, null))
            assertNull(
                cache.result(inLanguage, "hi", fixture.normalizer.normalize("Tum Hi Ho", "Someone Else"), 240.seconds),
            )
        }

    @Test
    fun `a line's language survives the retrieval cache's encoding, and none is kept as none`() {
        val kept = RetrievalCodec.decode(RetrievalCodec.encode(result("एक", "hi")))!!
        assertEquals("hi", kept.evidence.single().language)
        val unmarked = RetrievalCodec.encode(result("One.", null))
        assertEquals(false, "language" in unmarked)
        assertNull(
            RetrievalCodec
                .decode(unmarked)!!
                .evidence
                .single()
                .language,
        )
    }
}
