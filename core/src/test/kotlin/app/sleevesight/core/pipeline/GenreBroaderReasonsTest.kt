package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

/**
 * *Why is this …?* for a genre's broader genre (#1127): asked only where a source other than
 * MusicBrainz gives the genre or the broader genre itself (Last.fm, Wikidata, the AI's own list);
 * for a genre only MusicBrainz gives, the broader genre is shown with no AI request, and never
 * reaches a prompt.
 */
class GenreBroaderReasonsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val provider = FakeProvider { FakeProvider.done("Its smooth vocals are R&B [W1].") }
    private val cache = MemoryCache()
    private val reasons =
        GenreReasons(AiAccess(provider, Secret("k")), cache, JvmHasher) { vocabulary.broader(it)?.identity }
    private val lists = GenreLists(AiAccess(provider, Secret("k")), cache, JvmHasher, vocabulary)
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    // *AI service knowledge* on: the AI's genre list could run, and is still never asked for the broader genre.
    private val knowing = settings.copy(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL)

    // The genre and its broader genre, by identity, as the vocabulary gives them.
    private val narrower = checkNotNull(vocabulary.identity("contemporary r&b"))
    private val broader = checkNotNull(vocabulary.broader(narrower)).identity

    // *Thriller*: Contemporary R&B on the track's row, from [credits]; [also] on the artist's row.
    private fun track(
        credits: List<LabelSource>,
        also: Pair<String, LabelSource>? = null,
    ): TrackState {
        val playing = nowPlaying("Thriller", "Michael Jackson", "Thriller")
        val drafts = listOf(line(SourceId.WIKIPEDIA, "Thriller is a song by Michael Jackson.", Scope.SONG))
        val label = GenreLabel("contemporary r&b", "Contemporary R&B", narrower, level = GenreLevel.TRACK)
        val rows =
            listOfNotNull(
                GenreRow(GenreLevel.TRACK, null, listOf(TrackLabel(label, credits.map { LabelCredit(it, null) }))),
                also?.let { (tag, source) ->
                    val other = vocabulary.labelsFor(listOf(tag), level = GenreLevel.ARTIST).single()
                    val credited = TrackLabel(other, listOf(LabelCredit(source, null)))
                    GenreRow(GenreLevel.ARTIST, "Michael Jackson", listOf(credited))
                },
            )
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(
                    SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false),
                ),
            evidence = EvidenceSet.of(drafts, JvmHasher),
            genres = TrackGenres(rows),
        )
    }

    @Test
    fun `the vocabulary gives Contemporary R&B its broader genre, R&B`() {
        assertEquals("R&B", vocabulary.broader(narrower)?.label)
        // R&B has none the vocabulary holds.
        assertNull(vocabulary.broader(broader))
    }

    @Test
    fun `no reason is offered for the broader genre of a genre only MusicBrainz gives`() {
        val only = track(listOf(LabelSource.MUSICBRAINZ))
        for (now in listOf(settings, knowing)) {
            assertNull(reasons.key(only, broader, now))
            // No AI request at all: neither the reason nor the AI's genre list first.
            assertEquals(ReasonPath.None, reasons.path(only, broader, now, lists, listed = null))
            assertEquals(ReasonPath.None, reasons.path(only, broader, now, lists, setOf("pop")))
        }
        // Nor a disabled button for want of an AI service: there is nothing it could ask.
        val none = GenreReasons(null, cache, JvmHasher) { vocabulary.broader(it)?.identity }
        assertEquals(ReasonPath.None, none.path(only, broader, knowing, lists, listed = null))
    }

    @Test
    fun `the reason is offered when Last-fm or Wikidata gives the genre`() {
        for (source in listOf(LabelSource.LASTFM, LabelSource.WIKIDATA)) {
            val given = track(listOf(LabelSource.MUSICBRAINZ, source))
            val path = assertIs<ReasonPath.Reason>(reasons.path(given, broader, settings, lists, listed = null))
            assertEquals(broader, path.key.subject)
            val none = GenreReasons(null, cache, JvmHasher) { vocabulary.broader(it)?.identity }
            assertEquals(ReasonPath.NoService, none.path(given, broader, settings, lists, listed = null))
        }
    }

    @Test
    fun `the reason is offered when the AI's own list gives the genre or the broader genre`() {
        val only = track(listOf(LabelSource.MUSICBRAINZ))
        assertIs<ReasonPath.Reason>(reasons.path(only, broader, knowing, lists, setOf(narrower)))
        assertIs<ReasonPath.Reason>(reasons.path(only, broader, knowing, lists, setOf(broader)))
    }

    @Test
    fun `the reason is offered when another source gives the broader genre itself`() {
        val only = track(listOf(LabelSource.MUSICBRAINZ), also = "r&b" to LabelSource.LASTFM)
        assertIs<ReasonPath.Reason>(reasons.path(only, broader, settings, lists, listed = null))
        // The same broader genre, only from MusicBrainz too: not.
        val neither = track(listOf(LabelSource.MUSICBRAINZ), also = "r&b" to LabelSource.MUSICBRAINZ)
        assertNull(reasons.key(neither, broader, settings))
    }

    @Test
    fun `R&B only MusicBrainz gives, opened as itself, is written at once beside a Last-fm narrower genre`() {
        // Contemporary R&B from Last.fm on the track's row, R&B from MusicBrainz alone on the artist's:
        // Last.fm's genre and the CC0 relation let R&B reach a prompt, so its own chip skips the AI's
        // genre list (#762's path B), the reason's key shared with the broader view's.
        val given = track(listOf(LabelSource.LASTFM), also = "r&b" to LabelSource.MUSICBRAINZ)
        val own =
            assertIs<ReasonPath.Reason>(reasons.path(given, broader, knowing, lists, listed = null, asBroader = false))
        val opened =
            assertIs<ReasonPath.Reason>(reasons.path(given, broader, knowing, lists, listed = null, asBroader = true))
        assertEquals(own.key, opened.key)
    }

    @Test
    fun `nothing is offered for a genre that is neither the track's nor the broader genre of one`() {
        val given = track(listOf(LabelSource.LASTFM))
        assertEquals(ReasonPath.None, reasons.path(given, "jazz", settings, lists, listed = null))
        assertFalse(reasons.allowed(given, "jazz"))
    }

    @Test
    fun `a prompt never carries the broader genre of a genre only MusicBrainz gives`() =
        runTest {
            val only = track(listOf(LabelSource.MUSICBRAINZ))
            assertFalse(reasons.allowed(only, broader))
            assertNull(reasons.key(only, broader, knowing))
            // Given by Last.fm, its reason is asked for the broader genre, which is all the prompt names.
            val given = track(listOf(LabelSource.MUSICBRAINZ, LabelSource.LASTFM))
            val key = assertNotNull(reasons.key(given, broader, settings))
            reasons.write(key, given, "R&B") { _, _ -> }
            val prompt = provider.requests.single().prompt
            assertTrue("<genre>\nR&B\n</genre>" in prompt)
            assertFalse("Contemporary" in prompt, prompt)
        }
}
