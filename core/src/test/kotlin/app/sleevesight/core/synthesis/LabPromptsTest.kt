package app.sleevesight.core.synthesis

import app.sleevesight.core.ai.CompletionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The prompt lab's variants (#1003): how a request's kind is told, and how a variant is put into it. */
class LabPromptsTest {
    @Test
    fun `each kind is told by the opening line its prompt is built with`() {
        assertEquals(PromptKind.KEY, LabPrompts.kindOfLine(firstLine(InsightPrompt.system(allowModel = false))))
        assertEquals(PromptKind.KEY, LabPrompts.kindOfLine(firstLine(InsightPrompt.system(allowModel = true))))
        assertEquals(PromptKind.SONG, LabPrompts.kindOfLine(ItemPrompt.task(CardKind.SONG)))
        assertEquals(PromptKind.SONG, LabPrompts.kindOfLine(ItemPrompt.PARTS_TASK))
        assertEquals(PromptKind.ARTIST, LabPrompts.kindOfLine(ItemPrompt.task(CardKind.ARTIST)))
        assertEquals(PromptKind.ALBUM, LabPrompts.kindOfLine(ItemPrompt.task(CardKind.ALBUM)))
        for (system in listOf(
            GenreListPrompt.system(GenreListMode.KNOWLEDGE),
            GenreListPrompt.system(GenreListMode.WEB),
            GenreListPrompt.system(GenreListMode.WEB, limit = 3),
        )) {
            assertEquals(PromptKind.GENRE_LIST, LabPrompts.kindOfLine(firstLine(system)))
        }
        for (allowModel in listOf(false, true)) {
            assertEquals(
                PromptKind.GENRE_REASONS,
                LabPrompts.kindOfLine(firstLine(GenreReasonPrompt.system(allowModel))),
            )
        }
        assertEquals(PromptKind.EXPLAIN, LabPrompts.kindOfLine(firstLine(ExplainPrompt.system(limit = null))))
        assertEquals(PromptKind.EXPLAIN, LabPrompts.kindOfLine(firstLine(ExplainPrompt.system(limit = 3))))
    }

    @Test
    fun `the prompts the lab does not vary are not taken for one it does`() {
        // *Explain the song* is the one search the lab varies.
        for (kind in SearchKind.entries - SearchKind.EXPLAIN) {
            assertNull(LabPrompts.kindOfLine(firstLine(AiSearchPrompt.system(kind))), kind.key)
        }
        assertNull(LabPrompts.kindOfLine(firstLine(VocalsPrompt.system(limit = null))))
        assertNull(LabPrompts.kindOfLine("Anything else."))
    }

    @Test
    fun `a variant replaces the system prompt's opening line only, the role read in for its placeholder`() {
        val system = InsightPrompt.system(allowModel = false)
        val request = CompletionRequest("m", "the track and its evidence", system = system)
        val varied = LabPrompts.withInstructions(request, "${LabPrompts.ROLE} Open with the sound.")
        assertEquals(
            InsightPrompt.ROLE + " Open with the sound." + system.removePrefix(InsightPrompt.ROLE),
            varied.system,
        )
        assertEquals(request.prompt, varied.prompt)
        assertEquals(PromptKind.KEY, LabPrompts.kindOf(request))
    }

    @Test
    fun `a prompt with no system prompt has its own opening line replaced, kept on one line`() {
        val prompt = ItemPrompt.task(CardKind.ARTIST) + "\n\nThe rest."
        val varied =
            LabPrompts.withInstructions(
                CompletionRequest("m", prompt),
                "Write about the sound.\nThen the rest.",
            )
        assertEquals("Write about the sound. Then the rest.\n\nThe rest.", varied.prompt)
    }

    @Test
    fun `blank instructions leave the request as it is`() {
        val request = CompletionRequest("m", "p", system = "s")
        assertSame(request, LabPrompts.withInstructions(request, "  "))
    }

    @Test
    fun `every drafted variant keeps the release line and adds to it`() {
        for (kind in PromptKind.entries) {
            val variants = LabPrompts.variants(kind)
            assertTrue(variants.isNotEmpty(), kind.key)
            for (variant in variants) {
                assertTrue(variant.instructions.startsWith(LabPrompts.ROLE), "${kind.key} ${variant.id}")
                assertTrue(LabPrompts.shown(kind, variant.instructions).startsWith(LabPrompts.release(kind)))
            }
        }
    }

    @Test
    fun `Explain the song's reply is shown as its explanation`() {
        assertEquals(
            "It is about leaving. [S1]",
            LabPrompts.shownReply(PromptKind.EXPLAIN, "{\"explanation\": \"It is about leaving. [S1]\"}"),
        )
        assertEquals("Not JSON.", LabPrompts.shownReply(PromptKind.EXPLAIN, " Not JSON. "))
        assertEquals("{\"a\": 1}", LabPrompts.shownReply(PromptKind.SONG, "{\"a\": 1}"))
    }

    @Test
    fun `a card's kind is the lab's where the lab varies its prompt`() {
        assertEquals(PromptKind.KEY, LabPrompts.kindOfCard(CardKind.INSIGHT, null))
        assertEquals(PromptKind.GENRE_REASONS, LabPrompts.kindOfCard(CardKind.GENRE, "Jazz"))
        assertEquals(PromptKind.EXPLAIN, LabPrompts.kindOfCard(CardKind.SEARCH, SearchKind.EXPLAIN.key))
        assertNull(LabPrompts.kindOfCard(CardKind.SEARCH, SearchKind.GENRES.key))
        assertNull(LabPrompts.kindOfCard(CardKind.VOCALS, null))
    }

    @Test
    fun `a comparison takes two to four variants, the release prompt and two drafts until set`() {
        val settings = LabSettings()
        assertEquals(listOf(LabPrompts.RELEASE, "A", "B"), settings.compared(PromptKind.SONG))
        assertEquals(listOf(LabPrompts.RELEASE, "A"), settings.compared(PromptKind.ARTIST))
        val four = settings.compare(PromptKind.SONG, LabPrompts.CUSTOM, on = true)
        assertEquals(listOf(LabPrompts.RELEASE, "A", "B", LabPrompts.CUSTOM), four.compared(PromptKind.SONG))
        assertSame(four, four.compare(PromptKind.SONG, "C", on = true))
        assertEquals(
            listOf("A", "B", LabPrompts.CUSTOM),
            four.compare(PromptKind.SONG, LabPrompts.RELEASE, false).compared(PromptKind.SONG),
        )
    }

    @Test
    fun `the variant in use is the release prompt's until set, and an unknown one reads as unset`() {
        val settings = LabSettings()
        assertEquals(LabPrompts.RELEASE, settings.inUse(PromptKind.KEY))
        assertNull(settings.instructions(PromptKind.KEY, settings.inUse(PromptKind.KEY)))
        assertEquals("B", settings.use(PromptKind.SONG, "B").inUse(PromptKind.SONG))
        assertEquals(LabPrompts.RELEASE, settings.use(PromptKind.ARTIST, "Z").inUse(PromptKind.ARTIST))
        assertEquals(LabPrompts.ROLE, settings.instructions(PromptKind.ALBUM, LabPrompts.CUSTOM))
        assertEquals(
            "Mine.",
            settings.withCustom(PromptKind.ALBUM, "Mine.").instructions(PromptKind.ALBUM, LabPrompts.CUSTOM),
        )
    }

    @Test
    fun `the settings and the log read back as written`() {
        val settings =
            LabSettings()
                .use(PromptKind.SONG, "B")
                .compare(PromptKind.KEY, LabPrompts.CUSTOM, on = true)
                .withCustom(PromptKind.KEY, "{role} Shorter.")
        assertEquals(
            settings.inUse(PromptKind.SONG),
            LabJson.readSettings(LabJson.settings(settings)).inUse(PromptKind.SONG),
        )
        assertEquals(
            settings.compared(PromptKind.KEY),
            LabJson.readSettings(LabJson.settings(settings)).compared(PromptKind.KEY),
        )
        assertEquals("{role} Shorter.", LabJson.readSettings(LabJson.settings(settings)).custom(PromptKind.KEY))
        val log =
            listOf(
                LabComparison(
                    time = Instant.parse("2026-11-17T19:58:12Z"),
                    app = "0.8.0-debug",
                    kind = PromptKind.SONG,
                    title = "Phil's First Tear",
                    artist = "Kolonel Djafaar",
                    album = null,
                    service = "Anthropic",
                    model = "claude-haiku-5-5",
                    effort = null,
                    evidence = "<evidence>…</evidence>",
                    instructions = mapOf(LabPrompts.RELEASE to "r", "A" to "a"),
                    versions =
                        listOf(
                            LabVersion(1, "A", "Text. [L1]", mark = LabMark.GOOD, note = "Right."),
                            LabVersion(2, LabPrompts.RELEASE, null, failed = "timed out"),
                        ),
                ),
            )
        assertEquals(log, LabJson.readLog(LabJson.log(log)))
        assertEquals(log, LabJson.readLog(LabJson.log(log, readable = true)))
        assertEquals(LabSettings(), LabJson.readSettings("not json"))
        assertEquals(emptyList(), LabJson.readLog("[{\"kind\": \"nothing\"}]"))
    }

    private fun firstLine(text: String): String = text.substringBefore('\n')
}
