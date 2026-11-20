package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SelfCorrection
import app.sleevesight.core.synthesis.SelfRevision
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Properties of what a card shows of an insight reply ([readReply], #597, #798): a reply that
 * revises itself in the open never shows the model's comment on its draft, nor the draft, and an
 * insight written once, with no revision, is shown whole.
 */
class RevisionPropertiesTest {
    private val evidence =
        EvidenceSet.of(
            listOf(
                line(SourceId.MUSICBRAINZ, "Already Forgiven was released as a single in February 2020."),
                line(SourceId.MUSICBRAINZ, "Already Forgiven is on the album Are You in Love?"),
                line(SourceId.WIKIPEDIA, "Basia Bulat is a Canadian folk singer-songwriter."),
                line(SourceId.WIKIPEDIA, "Are You in Love? is the fifth studio album by Basia Bulat."),
                line(SourceId.LASTFM, "Basia Bulat recorded the album in Kentucky."),
            ),
            JvmHasher,
        )

    // A sentence an insight may hold: a fact, cited, about artists and titles in several scripts,
    // among them titles that open as a revision would, quoted ("Wait for Me", "I Will Survive").
    private val sentence: Arb<String> =
        Arb.bind(
            Arb.element(
                "Basia Bulat",
                "Sigur Rós",
                "Björk",
                "Mötley Crüe",
                "坂本龍一",
                "Земфира",
                "Hozier",
            ),
            Arb.element("Already Forgiven", "Hoppípolla", "Jóga", "Wait for Me", "I Will Survive", "Let It Be"),
            Arb.element("1965", "1984", "2001", "2020"),
            Arb.element(0, 1, 2, 3, 4),
        ) { artist, title, year, form ->
            when (form) {
                0 -> "$artist recorded \"$title\" in $year [MB1]."
                1 -> "\"$title\" was released as a single in February $year [MB2]."
                2 -> "It sits on the album \"Are You in Love?\", from $year [W2]."
                3 -> "$artist is a singer-songwriter who wrote \"$title\" [W1]."
                else -> "The album with \"$title\" was recorded in Kentucky in $year [L1]."
            }
        }

    private val insight: Arb<List<String>> = Arb.list(sentence, 1..4).map { it.distinct() }

    // The model's comment on its own draft, in the forms seen (#597, #798) and their like.
    private val comment =
        Arb.element(
            "Wait, I need to correct that: the evidence says the album is \"Are You in Love?\" and that the " +
                "track is listed on it [W2], so I'll keep only what is supported.",
            "Wait, that second sentence is not supported by the evidence, so I'll drop it. " +
                "Here is the corrected insight:",
            "Hmm, that is not right [MB1].",
            "**Correction:**",
            "Correction:",
            "Let me correct the second one [MB1].",
            "So I'll keep only the release [MB1].",
            "That is not supported by the evidence [MB1].",
            "Oops, I was wrong.",
            "On second thought, scratch that.",
            "Here is the corrected insight:",
        )

    private val gap = Arb.element(" ", "\n", "\n\n")

    @Test
    fun `a reply that revises itself never shows the comment or the draft`() =
        runTest {
            var cut = 0
            checkAll(CONFIG, insight, comment, insight, Arb.list(gap, 2..2)) { draft, said, fresh, gaps ->
                val written = "${draft.joinToString(" ")} $DRAFT"
                val reply = written + gaps[0] + said + gaps[1] + fresh.joinToString(" ")
                val shown = (readReply(reply, evidence, allowModel = false) as? InsightReply.Written)?.grounded?.text
                if (shown != null) {
                    assertNull(SelfRevision.of(shown), "still revises itself: \"$shown\"")
                    assertNull(SelfCorrection.of(shown), "still corrects itself: \"$shown\"")
                    for (sign in SIGNS) assertTrue(sign !in shown, "\"$sign\" shown in \"$shown\"")
                    assertTrue(DRAFT !in shown, "the draft shown in \"$shown\"")
                    cut++
                }
            }
            // Not only replies asked for once more: most are cut down to the fresh version and shown.
            assertTrue(cut > ITERATIONS / 2, "only $cut of $ITERATIONS replies were shown")
        }

    @Test
    fun `an insight written once is shown whole`() =
        runTest {
            checkAll(CONFIG, insight.filter { it.isNotEmpty() }, gap) { sentences, between ->
                val reply = sentences.joinToString(between)
                val shown = assertIs<InsightReply.Written>(readReply(reply, evidence, allowModel = false))
                assertEquals(reply.trim(), shown.grounded.text)
            }
        }

    private companion object {
        const val ITERATIONS = 200
        val CONFIG = PropTestConfig(seed = 798L, iterations = ITERATIONS)

        // The broken first draft of #798's reply, which no fresh version repeats.
        const val DRAFT = "It is the title track of nothing in particular here [MB2]."

        // The words of the comments above that an insight's own text never holds.
        val SIGNS =
            listOf(
                "Wait,",
                "I need to correct",
                "I'll keep only",
                "I'll drop",
                "Let me correct",
                "supported by the evidence",
                "Correction:",
                "corrected insight",
                "Hmm,",
                "Oops,",
                "On second thought",
            )
    }
}
