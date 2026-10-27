package app.sleevesight.core.synthesis

import app.sleevesight.fixtures.Fixtures
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reply that corrects itself in the open (#597) shows and is checked as its fresh version only;
 * an insight's own text is never cut: not a sentence wrapped over lines, not a line of prose that
 * opens as an aside may, not a quotation, not prose going on with evidence of its own. The
 * corrections are the phone's (#597) and the six found in the CLI's recorded replies of #399's and
 * #424's measurements (917 distinct replies, 2026-10-21 to 2026-10-25, Claude Haiku 5.5), each as
 * written; five are taken out, and one, whose version cites a line its draft did not, is not.
 */
class SelfCorrectionTest {
    private fun written(
        reply: String,
        ids: Set<String> = IDS,
    ): GroundedText = assertIs<InsightReply.Written>(GroundingCheck.check(reply, ids, allowModel = false)).grounded

    @Test
    fun `the phone's reply shows only its corrected version, and its counts are that version's`() {
        val grounded = written(PHONE)
        assertEquals(PHONE_KEPT, grounded.text)
        assertEquals(GroundingCounts(ok = 1, model = 0, hedge = 0, uncited = 0, unknown = 0), grounded.counts)
        assertEquals(listOf(PHONE_KEPT), grounded.paragraphs.flatten().map { it.text })
        val withdrawn = assertNotNull(grounded.withdrawn)
        assertTrue(withdrawn.startsWith("Lara Rosseel released \"Andrew\""), withdrawn)
        assertTrue(withdrawn.endsWith("Here is the corrected insight:"), withdrawn)
    }

    @Test
    fun `each recorded correction keeps its last version, cited and uncommented`() {
        for ((reply, kept) in RECORDED) {
            val grounded = written(reply)
            assertEquals(kept, grounded.text, reply)
            assertEquals(0, grounded.counts.uncited, reply)
            assertNotNull(grounded.withdrawn, reply)
        }
    }

    @Test
    fun `quotation marks around the whole corrected version are taken off, a title's kept`() {
        val wrapped =
            "It came out in 2022 [MB1]. It sets the album's tone.\n\n" +
                "Wait, that second sentence is not supported, so here is the corrected insight:\n\n" +
                "\"It came out in 2022 [MB1].\""
        assertEquals("It came out in 2022 [MB1].", written(wrapped).text)
        val curly = wrapped.replace("\"It came", "“It came").replace("[MB1].\"", "[MB1].”")
        assertEquals("It came out in 2022 [MB1].", written(curly).text)
        val citedAfter = wrapped.replace("[MB1].\"", "[MB1]\"")
        assertEquals("It came out in 2022 [MB1]", written(citedAfter).text)
        // Opens with a quoted title and ends with one, a stop inside: no marks around the whole.
        val titles =
            "\"Andrew\" opens the album [MB1], and critics described it as a quiet farewell [W1]. " +
                "It sets the tone.\n\n" +
                "Wait, I'll drop the second sentence. Corrected reply:\n\n" +
                "\"Andrew\" opens the album [MB1]. It is on \"Hert.\""
        assertEquals("\"Andrew\" opens the album [MB1]. It is on \"Hert.\"", written(titles).text)
        // Opens with a quoted title and ends quoting a phrase, after its citation: marks kept, balanced.
        val phrase = titles.replace("\"Andrew\" opens the album [MB1]. It is on \"Hert.\"", PHRASE)
        assertEquals(PHRASE, written(phrase).text)
        val curlyPhrase = titles.replace("\"Andrew\" opens the album [MB1]. It is on \"Hert.\"", CURLY_PHRASE)
        assertEquals(CURLY_PHRASE, written(curlyPhrase).text)
    }

    @Test
    fun `an introduction in bold counts, and a 12-inch mark opens no quotation`() {
        val bold =
            "It came out in 2022 [MB1]. It sets the tone.\n\n**Corrected insight:**\n\nIt came out in 2022 [MB1]."
        assertEquals("It came out in 2022 [MB1].", written(bold).text)
        val twelveInch =
            "It came out as a 12\" single [MB1]. It was huge.\n\n" +
                "Wait, that second sentence is not supported, so I'll drop it.\n\nIt came out as a 12\" single [MB1]."
        assertEquals("It came out as a 12\" single [MB1].", written(twelveInch).text)
    }

    /**
     * Recorded, and not taken out: its version cites the player's line, `[P1]`, which its draft
     * did not, and a version that cites new evidence is read as prose going on, not a revision.
     */
    @Test
    fun `a correction whose version cites a line its draft did not shows as written`() {
        val grounded = written(NEW_EVIDENCE)
        assertEquals(NEW_EVIDENCE, grounded.text)
        assertNull(grounded.withdrawn)
    }

    @Test
    fun `a line that reads like an aside, before text that restates the draft, is kept when it is prose`() {
        val draft = "It was written in 1964 [W1]. It was released in 1965 [MB1].\n\n"
        val after = "\n\nIt was written in 1964 [W1]. It reached the top ten [MB1]."
        val lines =
            listOf(
                // A quoted lyric inside prose, in double or single quotation marks (an apostrophe
                // inside the quotation).
                "So \"I'll fix the roof\" sets up the closing pledge:",
                "So 'I'll fix it' sets up the closing pledge:",
                "So ‘I’ll fix it’ sets up the closing pledge:",
                "So the 12\" mix ends on \"Let me correct that line\" sung twice.",
                // An introduction that is not a sentence of its own.
                "The next verse is her revised reply:",
                "Kitty Wells answered it in 1952, and here is the reply:",
                // Lyric lines, in emphasis or not, that withdraw nothing of the reply.
                "*Let me fix it tonight, I'll drop the act.*",
                "So I'll cut you loose, I'll drop the act.",
                // Openers before words that are the reply's making elsewhere, and music here.
                "Here is the evidence the session was rushed:",
                "Actually, the band broke every rule in the studio.",
                "Actually, it quotes Monk's Evidence.",
                "Corrected versions followed in 1966.",
                // A sentence wrapped over two lines.
                "Actually, the surviving evidence\nplaces the session in 1964, so I'll drop it from the set.",
            )
        for (line in lines) {
            val reply = draft + line + after
            val grounded = written(reply)
            assertEquals(reply, grounded.text, line)
            assertNull(grounded.withdrawn, line)
        }
    }

    /**
     * Lyrics that read as a correction, after a draft that cites the one line the prose after them
     * cites again, as on a track documented by one line: the prose says something new, and does
     * not restate the draft (#606, from the reviews of #604).
     */
    @Test
    fun `a lyric that reads as a correction, before prose that goes on from the same line, is kept`() {
        val replies =
            listOf(
                // A single-quoted lyric inside prose.
                "The song turns a domestic task into a promise [W1].\n\n" +
                    "So 'I'll fix it' sets up the closing pledge:\n\nThe narrator will return home [W1].",
                // A lyric line of its own, after an introduction.
                "The song is about a breakup [W1]. The chorus pleads:\n\n" +
                    "Let me fix it, let me make it right.\n\nThe plea recurs on every verse [W1].",
                // The middle line of a lyric in italics over three lines, a stop ending the line before.
                "The song is a plea to an absent lover [W1]. The bridge goes:\n\n" +
                    "*I waited all night.\nLet me fix it, I'll drop it all.\nCome home.*\n\n" +
                    "The plea recurs on every verse [W1].",
                // The middle line of a lyric in single quotation marks over three lines.
                "The song circles one regret [W1]. The last verse runs:\n\n" +
                    "'I must fix it.\nI must fix it, I should cut it now.\nThen I go' [W1].",
            )
        for (reply in replies) {
            val grounded = written(reply)
            assertEquals(reply, grounded.text, reply)
            assertNull(grounded.withdrawn, reply)
        }
    }

    @Test
    fun `an apostrophe opens no quotation, before a word or a decade or after a plural`() {
        val version = "It defined the '90s for the Beatles' heirs, rock 'n' roll's too [MB1]."
        val reply = "$version It sets the tone.\n\nWait, I'll drop the second sentence.\n\n$version"
        val grounded = written(reply)
        assertEquals(version, grounded.text)
        assertNotNull(grounded.withdrawn)
    }

    @Test
    fun `a version corrected twice keeps its last`() {
        val reply =
            "A [MB1]. B.\n\nWait, the second sentence is uncited. Here is the corrected insight:\n\n" +
                "A [MB1]. C.\n\nActually, that sentence is not in the evidence either, so I'll drop it.\n\nA [MB1]."
        val grounded = written(reply)
        assertEquals("A [MB1].", grounded.text)
        val withdrawn = assertNotNull(grounded.withdrawn)
        assertTrue(withdrawn.startsWith("A [MB1]. B.\n\nWait") && withdrawn.endsWith("so I'll drop it."), withdrawn)
    }

    @Test
    fun `a sentence that opens with Actually or Wait, in a reply that does not correct itself, is kept`() {
        val replies =
            listOf(
                // A sentence of the insight that opens with "Actually", cited or not.
                "It was recorded in 1965 [MB1].\n\nActually, the band cut it in one take [W1].",
                "It was recorded in 1965 [MB1].\n\nActually, the band cut it in one take.\n\n" +
                    "It topped the charts [W1].",
                // A title that is an opener.
                "\"Wait\" first appeared on Rubber Soul [MB1].\n\nIt was written by Lennon and McCartney [MB2].",
                "It was recorded in 1965 [MB1].\nWait is the album's sixth track.\nIt topped the charts [W1].",
                // An aside with nothing after it: the reply shows as written, the aside flagged.
                "It was recorded in 1965 [MB1]. It is great.\n\nWait, that second sentence is not supported.",
                // A version after the aside that cites nothing.
                "It was recorded in 1965 [MB1].\n\nWait, I'll drop the first sentence. Here is the insight:\n\n" +
                    "It is great.",
                // An aside as the reply's first line: no draft before it.
                "Wait, here is the corrected insight:\n\nIt was recorded in 1965 [MB1].",
                // An aside that shares its line with the version.
                "It was recorded in 1965 [MB1]. It is great. Wait, I'll drop that. It topped the charts [W1].",
                // An aside before a version citing only new evidence: prose going on, not a revision.
                "It was recorded in 1965 [MB1]. It is great.\n\n" +
                    "Wait, that second sentence is not supported, so I'll drop it.\n\nIt topped the charts [W1].",
                // A sentence wrapped over two lines, its first line opening with "Actually".
                "The single was released in 1965 [MB1].\n\nActually, the surviving evidence\n" +
                    "places the recording session in 1964 [W1].",
                // Openers before words that are the reply's making elsewhere, and music here.
                "The session was rushed [MB1].\n\nHere is the evidence the session was rushed:\n\n" +
                    "It was cut in one day [W1].",
                "It was recorded in 1965 [MB1].\n\nActually, the band broke every rule in the studio.\n\n" +
                    "It topped the charts [W1].",
                "It opens the set [MB1].\nActually, it quotes Monk's Evidence.\nThe band recorded it live [W1].",
                "It was a hit [MB1].\nCorrected versions followed in 1966.\nIt topped the charts [W1].",
                // A quotation over several lines, one of them a first-person correction.
                "He told the press [W1]:\n\"Wait, the evidence is all there.\nI'll fix the sentence in the mix.\n" +
                    "Let me correct the reply later.\"\nIt came out in 1965 [MB1].",
                "He told the press [W1]:\n“Wait, the evidence is all there.\nI'll fix the sentence in the mix.”\n" +
                    "It came out in 1965 [MB1].",
            )
        for (reply in replies) {
            val grounded = written(reply)
            assertEquals(reply.trim(), grounded.text, reply)
            assertNull(grounded.withdrawn, reply)
        }
    }

    @Test
    fun `NO_EVIDENCE stays a reply of its own`() {
        assertEquals(InsightReply.NoEvidence, GroundingCheck.check("NO_EVIDENCE", IDS, allowModel = false))
    }

    /** No stored reply of the grounding check's (`grounding/replies.json`) is read as a correction. */
    @Test
    fun `no stored reply is read as a correction`() {
        val json = Json { ignoreUnknownKeys = true }
        val replies = json.decodeFromString<List<Stored>>(Fixtures.text("grounding/replies.json"))
        assertTrue(replies.isNotEmpty())
        for (reply in replies) assertNull(SelfCorrection.of(reply.reply), reply.name)
    }

    @Serializable
    private data class Stored(
        val name: String,
        val reply: String,
    )

    private companion object {
        val IDS = setOf("MB1", "MB2", "W1", "P1")

        const val PHRASE = "\"Andrew\" is described as \"a quiet farewell [W1].\""
        const val CURLY_PHRASE = "“Andrew” is described as “a quiet farewell [W1].”"

        // A recorded correction whose version cites the player's line, which its draft did not.
        const val NEW_EVIDENCE =
            "The Veil Between Worlds by Die Hexen was first released on 22 July 2022, on the album You Are Not " +
                "My Mother [MB1]. The track is the opening number of that record, so it sets the tone for the " +
                "whole album. (Oops, that second sentence is not supported by the evidence.)\n\nLet me correct " +
                "that and give you the supported version:\n\nThe Veil Between Worlds by Die Hexen was first " +
                "released on 22 July 2022, on the album You Are Not My Mother [MB1]. The player data confirms " +
                "that this recording sits on that same release [P1]."

        // The version the phone's reply should show.
        const val PHONE_KEPT =
            "\"Andrew\" is a track from Lara Rosseel's album \"Hert\", first released on 11 March 2022 [MB1]."

        // As the phone showed it (#597), Lara Rosseel's "Andrew".
        const val PHONE =
            "Lara Rosseel released \"Andrew\" on 11 March 2022 as part of the album \"Hert\" [MB1]. The track is " +
                "a good example of how the album sits together as a whole [MB1].\n\n" +
                "Wait, that second sentence is not supported by the evidence, so I'll drop it. Here is the " +
                "corrected insight:\n\n" + PHONE_KEPT

        // The recorded replies that corrected themselves, each with the version the card shows.
        val RECORDED: List<Pair<String, String>> =
            listOf(
                "The Veil Between Worlds by Die Hexen was first released on 22 July 2022 [MB1]. It is the opening " +
                    "track of nothing in particular; the evidence only places it on the album \"You Are Not My " +
                    "Mother\" [P1].\n\nWait, that second sentence adds a claim the evidence doesn't support, so " +
                    "I'll correct it. Here is the insight:\n\nThe Veil Between Worlds by Die Hexen was first " +
                    "released on 22 July 2022 [MB1]. This recording is on the album \"You Are Not My Mother\" [P1]." to
                    "The Veil Between Worlds by Die Hexen was first released on 22 July 2022 [MB1]. This recording " +
                    "is on the album \"You Are Not My Mother\" [P1].",
                "The Ode to Newfoundland is the official provincial anthem of Newfoundland and Labrador [W1]. This " +
                    "recording is on the release \"CV Home\" [P1].\n\nWait, the player metadata line only repeats " +
                    "the release and should get no sentence of its own. So the reply is just the first point:\n\n" +
                    "The Ode to Newfoundland is the official provincial anthem of Newfoundland and Labrador [W1]." to
                    "The Ode to Newfoundland is the official provincial anthem of Newfoundland and Labrador [W1].",
                "Alexander S.'s version of \"Here Comes The Sun\" was first released on 20 November 2023, as a " +
                    "single bearing the same title [MB1]. The song itself is credited to this artist's release, so " +
                    "the card can simply point to that date and single.\n\nWait, that second sentence adds nothing " +
                    "the evidence supports and breaks the citation rule, so here is the corrected reply:\n\n" +
                    "Alexander S.'s version of \"Here Comes The Sun\" was first released on 20 November 2023, as a " +
                    "single with the same title [MB1]." to
                    "Alexander S.'s version of \"Here Comes The Sun\" was first released on 20 November 2023, as a " +
                    "single with the same title [MB1].",
                "The Veil Between Worlds by Die Hexen was first released on 22 July 2022 on the album You Are Not " +
                    "My Mother [MB1]. The album title is the only other detail the player confirms about this " +
                    "recording [P1].\n\nWait, the second sentence repeats release info, which the rules say not to " +
                    "give its own sentence. Corrected reply:\n\nThe Veil Between Worlds by Die Hexen was first " +
                    "released on 22 July 2022 on the album You Are Not My Mother [MB1][P1]." to
                    "The Veil Between Worlds by Die Hexen was first released on 22 July 2022 on the album You Are " +
                    "Not My Mother [MB1][P1].",
                "The Ode to Newfoundland is the official provincial anthem of Newfoundland and Labrador [W1]. This " +
                    "recording is on the release CV Home [P1].\n\nWait, I should correct the second sentence: player " +
                    "metadata only repeats the release and gets no sentence of its own, so I drop it.\n\n" +
                    "The Ode to Newfoundland is the official provincial anthem of Newfoundland and Labrador [W1]." to
                    "The Ode to Newfoundland is the official provincial anthem of Newfoundland and Labrador [W1].",
            )
    }
}
