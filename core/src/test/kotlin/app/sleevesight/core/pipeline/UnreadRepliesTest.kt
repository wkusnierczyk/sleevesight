package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.AiSearchPrompt
import app.sleevesight.core.synthesis.JsonReplies
import app.sleevesight.core.synthesis.ReplyShape
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import app.sleevesight.core.synthesis.SearchReading
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reply that held no JSON object that parses (#1446): a lyrics reply in words alone, a model
 * declining in prose, is nothing found, not a failure; braces that do not parse, a blank reply, or
 * words alone for another kind stay failures; and each is told for a developer-mode log line with its
 * shape and its start.
 */
class UnreadRepliesTest {
    private val provider = FakeProvider { FakeProvider.done("{}") }
    private val cache = MemoryCache()
    private val answers = AiSearchAnswers(GenreVocabulary.load(JvmResourceReader), JvmAccentFolder)
    private val told = mutableListOf<UnreadReply>()
    private val searches =
        AiSearches(AiAccess(provider, Secret("k")), cache, JvmHasher, answers, unread = { told += it })
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val playing = nowPlaying("Dernière danse", "Indila", "Mini World")
    private val track = TrackState(playing, Normalizer(JvmAccentFolder).normalize(playing), sources = emptyMap())
    private val facts = SearchFacts(track.normalized.title, track.normalized.artists, "Mini World")

    private suspend fun search(kind: SearchKind): SearchState? {
        val key = checkNotNull(searches.key(track, kind, settings))
        return searches.search(key, kind, track, facts, { _, _ -> })
    }

    @Test
    fun aLyricsReplyInWordsAloneIsNothingUsable() {
        for (reply in listOf(DECLINE, LINKED_DECLINE, "I don't know where its lyrics are.")) {
            assertEquals(SearchReading.NothingUsable, answers.read(SearchKind.LYRICS, reply), reply)
            assertEquals(
                SearchReading.NothingUsable,
                answers.read(SearchKind.LYRICS, reply, pages = listOf("https://genius.com/a")),
                reply,
            )
        }
    }

    @Test
    fun aLyricsReplyBlankOrWithBracesThatDoNotParseIsStillMalformed() {
        for (reply in listOf("", "  \n", "{\"lyrics_urls\": [\"https://genius.com/x\"", "Here: {lyrics_urls: none}")) {
            assertEquals(SearchReading.Malformed, answers.read(SearchKind.LYRICS, reply), reply)
        }
    }

    @Test
    fun wordsAloneForAnotherKindAreStillMalformed() {
        for (kind in listOf(SearchKind.DATES, SearchKind.GENRES, SearchKind.DEFINITION, SearchKind.SPELLING)) {
            assertEquals(SearchReading.Malformed, answers.read(kind, DECLINE), kind.key)
        }
    }

    @Test
    fun aReplysShapeIsBlankNoBracesUnparsedOrAnObject() {
        val cases =
            listOf(
                "" to ReplyShape.BLANK,
                " \n " to ReplyShape.BLANK,
                DECLINE to ReplyShape.NO_BRACES,
                "[\"https://genius.com/x\"]" to ReplyShape.NO_BRACES,
                "} and {" to ReplyShape.UNPARSED,
                "{\"lyrics_urls\": [\"https://genius.com/x\"" to ReplyShape.UNPARSED,
                "{\"lyrics_urls\": [}" to ReplyShape.UNPARSED,
                "Sure: {\"lyrics_urls\": []} " to ReplyShape.OBJECT,
                "```json\n{}\n```" to ReplyShape.OBJECT,
            )
        for ((reply, shape) in cases) assertEquals(shape, JsonReplies.shapeOf(reply), reply)
    }

    @Test
    fun aLyricsDeclineInProseIsNothingFoundNotKeptAndTold() =
        runTest {
            provider.reply = { FakeProvider.done(DECLINE) }
            val way = SearchWay(cost = QuestionCost(calls = 1))
            assertEquals(SearchState.NothingFound(way, lasting = false), search(SearchKind.LYRICS))
            val key = checkNotNull(searches.key(track, SearchKind.LYRICS, settings))
            assertEquals(null, cache.cards[key])
            assertEquals(listOf(UnreadReply(SearchKind.LYRICS, ReplyShape.NO_BRACES, DECLINE, NOTHING)), told)
            // Asked again later: nothing kept to read, so the next answer is the provider's.
            assertEquals(null, searches.kept(key, SearchKind.LYRICS, track))
            provider.reply = { FakeProvider.done("{\"lyrics_urls\": [\"$GENIUS\"]}") }
            val again = search(SearchKind.LYRICS)
            assertTrue(again is SearchState.Answered, "$again")
            assertTrue(searches.kept(key, SearchKind.LYRICS, track)?.state is SearchState.Answered)
            assertEquals(1, told.size)
        }

    @Test
    fun nothingFoundFromAnObjectIsStillKept() =
        runTest {
            provider.reply = { FakeProvider.done("{}") }
            assertEquals(SearchState.NothingFound(SearchWay(cost = QuestionCost(calls = 1))), search(SearchKind.LYRICS))
            val key = checkNotNull(searches.key(track, SearchKind.LYRICS, settings))
            assertTrue(searches.kept(key, SearchKind.LYRICS, track)?.state is SearchState.NothingFound)
        }

    @Test
    fun aReplyThatCannotBeReadFailsAndIsToldForEveryKind() =
        runTest {
            val cases =
                listOf(
                    SearchKind.DATES to DECLINE,
                    SearchKind.GENRES to "{\"genres\": [",
                    SearchKind.LYRICS to "{\"lyrics_urls\": [\"https://genius.com/x\"",
                    SearchKind.LYRICS to "",
                )
            for ((kind, reply) in cases) {
                told.clear()
                provider.reply = { FakeProvider.done(reply) }
                val state = search(kind)
                assertTrue(state is SearchState.Failed, "$kind $reply")
                val one = told.single()
                assertEquals(kind to UnreadReply.Read.FAILED, one.kind to one.read)
                assertEquals(JsonReplies.shapeOf(reply), one.shape)
            }
        }

    @Test
    fun aReplyThatIsAnObjectIsNotTold() =
        runTest {
            provider.reply = { FakeProvider.done("Here you are: {\"lyrics_urls\": []}") }
            assertTrue(search(SearchKind.LYRICS) is SearchState.NothingFound)
            assertEquals(emptyList(), told)
        }

    @Test
    fun theLogLineNamesTheKindShapeAndReadingAndShowsTheStartOnOneLine() {
        val head = "No.\nI can't\\won't share lyrics.\r"
        val reply = head + "x".repeat(300)
        val line = UnreadReply(SearchKind.LYRICS, ReplyShape.NO_BRACES, reply, NOTHING).line()
        val start = "No.\\nI can't\\\\won't share lyrics.\\r" + "x".repeat(UnreadReply.START - head.length)
        assertEquals(
            "Unread AI reply: kind=lyrics shape=no_braces read=nothing_found length=${reply.length} start=$start",
            line,
        )
        assertTrue('\n' !in line && '\r' !in line)
        val failed = UnreadReply(SearchKind.DATES, ReplyShape.BLANK, "", UnreadReply.Read.FAILED).line()
        assertEquals("Unread AI reply: kind=dates shape=blank read=failed length=0 start=", failed)
    }

    @Test
    fun theLogLineDoesNotCutACharacterInTwo() {
        val reply = "a".repeat(UnreadReply.START - 1) + "🎵" + "b"
        val line = UnreadReply(SearchKind.LYRICS, ReplyShape.NO_BRACES, reply, NOTHING).line()
        assertTrue(line.endsWith("start=" + "a".repeat(UnreadReply.START - 1)), line)
    }

    @Test
    fun aProseReplyAfterEveryWebSearchOfItsOwnFailedIsToldOnce() =
        runTest {
            // The model searched with its tool, every search failed, and it then ended in words alone.
            val loop =
                FakeProvider(usesTools = true) { request ->
                    if (request.exchanges.isEmpty()) {
                        listOf(
                            CompletionEvent.ToolUse(ToolTurn(listOf(ToolCall("c", "exa_search", QUERY)), "[]")),
                            CompletionEvent.Done(FakeProvider.USAGE, StopReason.TOOL_USE),
                        )
                    } else {
                        FakeProvider.done(UNABLE)
                    }
                }
            val made =
                AiSearches(
                    AiAccess(loop, Secret("k")),
                    cache,
                    JvmHasher,
                    answers,
                    web = { _, _ -> WebSearchOutcome.Failed },
                    unread = { told += it },
                )
            val key = checkNotNull(made.key(track, SearchKind.LYRICS, settings))
            val upTo = AiSearches.Asking(AiWebSearch.UP_TO, 3)
            val last = made.search(key, SearchKind.LYRICS, track, facts, { _, _ -> }, asking = upTo)
            assertEquals(SearchState.Failed(AiSearches.WEB_SEARCH_FAILED), last)
            val expected = UnreadReply(SearchKind.LYRICS, ReplyShape.NO_BRACES, UNABLE, UnreadReply.Read.NOT_READ)
            assertEquals(listOf(expected), told)
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun theLyricsPromptsSayOnlyTheAddressesAreAskedFor() {
        assertTrue(AiSearchPrompt.LYRICS_ADDRESSES_ONLY in AiSearchPrompt.build(SearchKind.LYRICS, facts))
        assertTrue(AiSearchPrompt.LYRICS_ADDRESSES_ONLY !in AiSearchPrompt.build(SearchKind.DATES, facts))
    }

    private companion object {
        const val DECLINE =
            "I can't share the lyrics of \"Dernière danse\", as they are under copyright, but you can " +
                "find them on licensed lyrics sites."
        const val QUERY = "{\"query\":\"x\"}"
        const val UNABLE = "I'm temporarily unable to search for lyrics pages right now."
        const val GENIUS = "https://genius.com/Indila-derniere-danse-lyrics"
        val NOTHING = UnreadReply.Read.NOTHING_FOUND
        const val LINKED_DECLINE = "I can't help with lyrics; try https://genius.com or https://www.musixmatch.com."
    }
}
