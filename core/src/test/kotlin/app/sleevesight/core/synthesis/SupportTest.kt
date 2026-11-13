package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.fixtures.Fixtures
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** The grounding check's support check (#1036, [Support]): a cited sentence must be backed by its lines. */
class SupportTest {
    @Serializable
    private data class Reply(
        val name: String,
        val reply: String,
    )

    private val at = Instant.parse("2026-11-13T00:00:00Z")

    private fun line(
        text: String,
        source: SourceId = SourceId.WIKIPEDIA,
        language: String? = null,
    ) = EvidenceDraft(source, Scope.SONG, text, "https://example.org", at, language = language)

    // The statuses of [reply]'s sentences, checked against [lines] (`W1`, `W2`, … for Wikipedia's).
    private fun statuses(
        reply: String,
        lines: List<EvidenceDraft>,
        subject: List<String> = emptyList(),
    ): List<SentenceStatus> {
        val checked = GroundingCheck.check(reply, EvidenceSet.of(lines, JvmHasher), false, subject = subject)
        return assertIs<InsightReply.Written>(checked)
            .grounded.paragraphs
            .flatten()
            .map { it.status }
    }

    private val brokenWings =
        "“Broken Wings” reached number one on the Billboard Hot 100 on December 7, 1985, " +
            "and stayed there for two weeks."

    /**
     * The two stored replies written from real evidence (`grounding/replies.json`, the gate's tracks 1
     * and 41, whose evidence is [FixtureEvidence]'s) keep every sentence: none flips. Without the
     * track's names one does: "Gordon Lightfoot", whom the lines it cites call "Lightfoot".
     */
    @Test
    fun `the stored replies written from real evidence keep every sentence`() {
        val replies =
            Json { ignoreUnknownKeys = true }.decodeFromString<List<Reply>>(
                Fixtures.text("grounding/replies.json"),
            )
        val flipped =
            listOf(41 to replies[0], 1 to replies[1]).associate { (n, reply) ->
                val playing = FixtureEvidence.nowPlaying(n)
                val subject = listOfNotNull(playing.title, playing.artists, playing.album)
                val evidence = FixtureEvidence.of(n)

                fun unsupported(subject: List<String>): Int {
                    val checked = GroundingCheck.check(reply.reply, evidence, allowModel = true, subject = subject)
                    return assertIs<InsightReply.Written>(checked).grounded.counts.unsupported
                }
                reply.name to (unsupported(subject) to unsupported(emptyList()))
            }
        assertEquals(
            mapOf(
                "claude-haiku-5-5 on prompts/insight-41.txt" to (0 to 0),
                "claude-haiku-5-5 on prompts/insight-1.txt" to (0 to 1),
            ),
            flipped,
        )
    }

    /** The case the mock-up showed (#1036): a wrong length and year beside a valid citation. */
    @Test
    fun `a wrong year is unsupported, its true neighbour is not`() {
        val reply =
            "It topped the Billboard Hot 100 for four weeks in early 1986 [W1]. " +
                "It reached number one in December 1985 [W1]."
        assertEquals(
            listOf(SentenceStatus.UNSUPPORTED, SentenceStatus.OK),
            statuses(reply, listOf(line(brokenWings))),
        )
    }

    /**
     * A name its line does not hold, the producer's credited to the guitarist's line; mid-sentence,
     * since a name that opens one is not compared.
     */
    @Test
    fun `a name its line does not hold is unsupported`() {
        val lines =
            listOf(
                line("Single issued 25 March 1974 on Reprise, produced by Lenny Waronker."),
                line("Recorded in November 1973 at Eastern Sound in Toronto; Red Shea played electric lead guitar."),
            )
        assertEquals(
            listOf(SentenceStatus.UNSUPPORTED, SentenceStatus.OK, SentenceStatus.OK),
            statuses(
                "The lead guitar was played by Lenny Waronker [W2]. Red Shea played lead guitar [W2]. " +
                    "Lenny Waronker produced it [W2][W1].",
                lines,
            ),
        )
    }

    /** Numbers in words, digit groups, ordinals, dates in another order, decades and two-digit years. */
    @Test
    fun `numbers and dates are read loosely`() {
        val lines =
            listOf(
                line("Arthur R. Scammell wrote the song around age fifteen, in the 1920s."),
                line("Last.fm counts 12838 listeners; the album was his ninth, released 1974-03-25."),
                line("He toured from 1970 to 1975 with twenty-one shows."),
            )
        val reply =
            "Scammell wrote it at 15 [W1]. Last.fm counts 12,838 listeners [W2]. It was his 9th album [W2]. " +
                "It came out on 25 March 1974 [W2]. He toured in the '70s, from 1970-75 [W3]. " +
                "There were 21 shows [W3]. It dates from the 20s [W1]."
        assertEquals(List(7) { SentenceStatus.OK }, statuses(reply, lines))
    }

    /**
     * Multi-word names: any case, diacritics, a shared stem, the sources' own names, places, the
     * track's names.
     */
    @Test
    fun `names are read loosely`() {
        val lines =
            listOf(
                line("Beyonce Knowles recorded it with the Newfoundlanders Choir."),
                line("Released by Island Records in the United States."),
            )
        val reply =
            "It was recorded by Beyonc\u00e9 Knowles with the Newfoundland Choir [W1]. " +
                "It is a Canadian recording [W1]. " +
                "According to Wikipedia, Island Records released it [W2]. " +
                "Their single Crazy In Love came out in the United States [W2]."
        assertEquals(List(4) { SentenceStatus.OK }, statuses(reply, lines, subject = listOf("Crazy In Love")))
        assertEquals(
            listOf(SentenceStatus.OK, SentenceStatus.OK, SentenceStatus.OK, SentenceStatus.UNSUPPORTED),
            statuses(reply, lines),
        )
    }

    /**
     * A name that opens a sentence is not compared ("Despite", "Released"); the same name later in
     * a sentence is ("Arthur Smith").
     */
    @Test
    fun `a name that opens a sentence is not compared`() {
        val lines = listOf(line("Scammell wrote the song in 1928; it was released later."))
        assertEquals(
            listOf(SentenceStatus.OK, SentenceStatus.OK, SentenceStatus.UNSUPPORTED),
            statuses(
                "Despite its sound, Scammell wrote it in 1928 [W1]. Released later, it sold [W1]. " +
                    "It was written by Arthur Smith [W1].",
                lines,
            ),
        )
    }

    /** A line in another language (#902) is translated by the model: what cites it is not compared. */
    @Test
    fun `a sentence citing a line in another language is not compared`() {
        val lines =
            listOf(
                line("Das Lied erreichte am 7. Dezember 1985 Platz 1 der Billboard Hot 100.", language = "de"),
                line("It was a hit."),
            )
        assertEquals(
            listOf(SentenceStatus.OK, SentenceStatus.UNSUPPORTED),
            statuses(
                "It reached number one in the United States in December 1985 [W1]. " +
                    "It reached number one for Island Records [W2].",
                lines,
            ),
        )
    }

    /** Only a sentence citing lines alone is compared: one citing the model's knowledge too is not. */
    @Test
    fun `a sentence from the model's knowledge is not compared`() {
        val reply = "It was recorded in 1999 in Paris [W1][AI]."
        val checked =
            GroundingCheck.check(reply, EvidenceSet.of(listOf(line("It was a hit.")), JvmHasher), allowModel = true)
        val sentence =
            assertIs<InsightReply.Written>(checked)
                .grounded.paragraphs
                .single()
                .single()
        assertEquals(SentenceStatus.MODEL, sentence.status)
    }

    /** The counts carry the sentences unsupported, and a sentence unsupported still cites a source. */
    @Test
    fun `an unsupported sentence is counted and still cites a source`() {
        val checked =
            GroundingCheck.check(
                "It topped the chart in 1986 [W1].",
                EvidenceSet.of(listOf(line(brokenWings)), JvmHasher),
                allowModel = false,
            )
        val grounded = assertIs<InsightReply.Written>(checked).grounded
        assertEquals(
            GroundingCounts(ok = 0, model = 0, hedge = 0, uncited = 0, unknown = 0, unsupported = 1),
            grounded.counts,
        )
        assertEquals(true, grounded.citesSource)
    }

    /**
     * Codex, PR #1238: an ordinary opening word before a capital one ("Released March") was read as
     * a name. The first word is never compared.
     */
    @Test
    fun `an opening word before a month is not a name`() {
        assertEquals(
            listOf(SentenceStatus.OK),
            statuses("Released March 1974 on Reprise [W1].", listOf(line("Single issued 25 March 1974 on Reprise."))),
        )
    }

    /** Codex, PR #1238: places and their peoples are named in many ways, and are not compared. */
    @Test
    fun `places and peoples are not compared`() {
        val lines = listOf(line("It topped the US chart and was a hit in Britain."))
        assertEquals(
            List(3) { SentenceStatus.OK },
            statuses(
                "It reached number one in the United States [W1]. It was a British hit [W1]. " +
                    "It charted in the USA and the United Kingdom [W1].",
                lines,
            ),
        )
    }

    /**
     * Codex, PR #1238: the album, which `CardEntity.Track` does not name, is in the player's release
     * line, whose words count as backed for every sentence, cited or not.
     */
    @Test
    fun `the player's release line backs the album's name`() {
        val lines =
            listOf(
                line("This recording is on the release \"Sundown Sessions\" (1974).", SourceId.PLAYER),
                line("It topped the charts."),
            )
        // `P1` is the player's line, `W1` Wikipedia's.
        assertEquals(listOf(SentenceStatus.OK), statuses("It opens the album Sundown Sessions [W1].", lines))
    }

    /**
     * Codex, PR #1238: a decade beside a year in it, a date written another way, and an age worked
     * out from two years are backed; only years are compared.
     */
    @Test
    fun `decades, dates in another form and worked-out ages are backed`() {
        val lines =
            listOf(
                line("It was released in 1974."),
                line("Single issued 25 March 1974."),
                line("He was born in 1938 and recorded the song in 1973."),
            )
        assertEquals(
            List(3) { SentenceStatus.OK },
            statuses(
                "It came out in the 1970s [W1]. It was issued on 1974-03-25 [W2]. He was 35 when he recorded it [W3].",
                lines,
            ),
        )
    }

    /**
     * Codex, PR #1238, round 2: a single capitalised word is never compared, so a people's name
     * beside its country's passes.
     */
    @Test
    fun `a single capitalised word is not compared`() {
        val lines =
            listOf(
                line("It was released in Switzerland."),
                line("It charted in Poland, Greece and Turkey."),
            )
        assertEquals(
            List(2) { SentenceStatus.OK },
            statuses("It was a Swiss release [W1]. It was a Polish, Greek and Turkish hit [W2].", lines),
        )
    }

    /** Codex, PR #1238, round 2: a year's last two digits, `'01`, back it. */
    @Test
    fun `a year's two-digit form backs it`() {
        assertEquals(
            listOf(SentenceStatus.OK),
            statuses("It came out in 2001 [W1].", listOf(line("It came out in '01."))),
        )
    }

    /** Codex, PR #1238, round 2: digit groups are joined on both sides, so `1,974` is `1974`. */
    @Test
    fun `digit groups are joined before years are compared`() {
        assertEquals(
            listOf(SentenceStatus.OK),
            statuses("Last.fm counts 1974 listeners [W1].", listOf(line("Last.fm counts 1,974 listeners."))),
        )
    }

    /** Codex, PR #1238, round 2: a chart place in other words, and decades in words, either way. */
    @Test
    fun `chart places and decades in words are backed`() {
        val lines =
            listOf(
                line("It reached number 1."),
                line("It came out in 1974."),
                line("It was a hit of the Seventies."),
            )
        assertEquals(
            List(3) { SentenceStatus.OK },
            statuses("It reached No. One [W1]. It is from the Seventies [W2]. It came out in 1974 [W3].", lines),
        )
    }
}
