package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GenreListMode
import app.sleevesight.core.synthesis.GenreListPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

/**
 * The AI's genre list (#762): asked before *Why is this …?* for a genre only MusicBrainz's tags give,
 * one call that names no genre, read against the vocabulary, and kept as a reason is; and no prompt,
 * the list's or a reason's, carries anything of MusicBrainz's tags (D12).
 */
class GenreListsTest {
    private val provider = FakeProvider { FakeProvider.done(LIST) }
    private val cache = MemoryCache()
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val ai = AiAccess(provider, Secret("k"))
    private val lists = GenreLists(ai, cache, JvmHasher, vocabulary)
    private val reasons = GenreReasons(ai, cache, JvmHasher)

    // *AI service knowledge* on (off by default), so the list can run.
    private val settings =
        Settings(aiProvider = ProviderId.ANTHROPIC, enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL)

    // Emily Jane White's "The Black Oak": MusicBrainz's one-vote recording tags (shoegaze, with its
    // votes, and rock), the album's Folk from Last.fm, and evidence with no tag in it.
    private val track: TrackState =
        run {
            val playing = nowPlaying("The Black Oak", "Emily Jane White", "Ode to Sentience")
            val drafts =
                listOf(
                    line(
                        SourceId.WIKIPEDIA,
                        "Emily Jane White is a singer-songwriter from the San Francisco Bay Area.",
                        Scope.ARTIST,
                    ),
                    line(SourceId.MUSICBRAINZ, "The Black Oak was released in 2010."),
                )
            val musicBrainz =
                vocabulary.labelsFor(
                    listOf("rock", "post-punk"),
                    level = GenreLevel.TRACK,
                    votes =
                        mapOf(
                            "post-punk" to 1,
                        ),
                )
            val lastFm = vocabulary.labelsFor(listOf("folk"), level = GenreLevel.ALBUM)
            TrackState(
                nowPlaying = playing,
                normalized = Normalizer(JvmAccentFolder).normalize(playing),
                sources =
                    mapOf(
                        SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false),
                    ),
                evidence = EvidenceSet.of(drafts, JvmHasher),
                genres =
                    combineGenres(
                        mapOf(LabelSource.MUSICBRAINZ to musicBrainz, LabelSource.LASTFM to lastFm),
                        emptyList(),
                    ),
            )
        }

    private suspend fun ask(): GenreListState {
        val key = checkNotNull(lists.key(track, settings))
        return lists.ask(key, track) { _, _ -> }
    }

    @Test
    fun `the list is one call, read against the vocabulary, and kept under its own key`() =
        runTest {
            val shown = mutableListOf<GenreListState>()
            val key = checkNotNull(lists.key(track, settings))
            val listed = assertIs<GenreListState.Listed>(lists.ask(key, track) { state, _ -> shown += state })
            assertEquals(1, provider.requests.size)
            // "Gothic Folk" is not on the app's genre list, so it is dropped.
            assertEquals(listOf("postpunk", "blues"), listed.genres.map { it.identity })
            assertEquals(GenreLevel.TRACK, listed.genres.first().level)
            assertEquals(listOf(GenreListState.Asking, listed), shown)
            assertEquals(CardKind.GENRE_LIST, key.kind)
            assertEquals(GenreListMode.KNOWLEDGE.key, key.subject)
            assertEquals(GenreListPrompt.VERSION, key.promptVersion)
            assertEquals(track.evidence?.fingerprint, key.evidenceFingerprint)
            assertTrue(key.modelKnowledge)
            // Found again with no call.
            assertEquals(listed, lists.kept(key)?.state)
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `none is asked with the model's knowledge off, insights off, no evidence, or no AI service`() {
        assertNull(lists.key(track, Settings(aiProvider = ProviderId.ANTHROPIC)))
        assertEquals(null, GenreLists.mode(Settings()))
        assertNull(lists.key(track, settings.copy(writeInsights = false)))
        assertNull(lists.key(track.copy(evidence = null), settings))
        assertNull(GenreLists(null, cache, JvmHasher, vocabulary).key(track, settings))
        assertEquals(listOf(GenreListMode.KNOWLEDGE), GenreLists.MODES)
    }

    @Test
    fun `a reply that names no genre of the list is a list of none, kept`() =
        runTest {
            provider.reply = { FakeProvider.done("""{"genres": ["Gothic Folk"]}""") }
            val listed = assertIs<GenreListState.Listed>(ask())
            assertTrue(listed.genres.isEmpty())
            assertEquals(1, cache.cards.size)
        }

    @Test
    fun `a failed call, or a reply that is no JSON object, fails, and is not kept`() =
        runTest {
            provider.reply = { listOf(CompletionEvent.Error(AiErrorKind.OFFLINE)) }
            assertEquals(GenreListState.Failed("offline"), ask())
            provider.reply = { FakeProvider.done("Post-punk and blues.") }
            assertEquals(GenreListState.Failed(GenreLists.MALFORMED), ask())
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun `no prompt carries a genre, tag or vote of MusicBrainz's, and the genres call names no genre (D12)`() =
        runTest {
            val mbOnly = listOf("rock", "post-punk")
            // Path B for MusicBrainz's genre: the genres call first.
            val listing = assertIs<ReasonPath.Listing>(reasons.path(track, "postpunk", settings, lists, listed = null))
            val listed = assertIs<GenreListState.Listed>(lists.ask(listing.key, track) { _, _ -> })
            val genresCall =
                provider.requests
                    .single()
                    .prompt
                    .lowercase()
            // It names no genre at all: none of the app's genre list, so none of the track's either.
            val named =
                vocabulary.tags.filter {
                    Regex(
                        "(?<![a-z])${Regex.escape(it)}(?![a-z])",
                    ).containsMatchIn(genresCall)
                }
            assertEquals(emptyList(), named)
            assertFalse("vote" in genresCall)
            // Then path A for a genre on the list (post-punk, the AI's now), and for Folk, Last.fm's.
            val ids = listed.genres.map { it.identity }.toSet()
            for ((genre, label) in listOf("postpunk" to "Post-Punk", "folk" to "Folk")) {
                val key = assertIs<ReasonPath.Reason>(reasons.path(track, genre, settings, lists, ids)).key
                reasons.write(key, track, label) { _, _ -> }
            }
            val reasonPrompts = provider.requests.drop(1).map { it.prompt.lowercase() }
            assertEquals(2, reasonPrompts.size)
            // Rock, which only MusicBrainz gives and the AI did not list, reaches neither; nor any vote.
            for (prompt in reasonPrompts) {
                assertFalse(Regex("(?<![a-z-])rock(?![a-z])").containsMatchIn(prompt), prompt)
                assertFalse("musicbrainz contributors" in prompt)
                assertFalse("vote" in prompt)
            }
            // Folk's reason names Folk, not post-punk, MusicBrainz's.
            assertFalse(mbOnly.any { it in reasonPrompts[1] })
        }

    private companion object {
        const val LIST = """{"genres": ["Post-Punk", "Gothic Folk", "Blues"]}"""
    }
}
