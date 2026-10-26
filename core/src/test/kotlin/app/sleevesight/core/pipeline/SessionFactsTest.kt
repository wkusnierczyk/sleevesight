package app.sleevesight.core.pipeline

import app.sleevesight.core.model.SourceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SessionFactsTest {
    private val formed = ArtistFact(SourceId.WIKIPEDIA, "The band formed in 1960.")
    private val label = ArtistFact(SourceId.WIKIDATA, "The band's label is Parlophone.")

    @Test
    fun `a fact is cited by others once another track's insight cites it, never by the track's own`() =
        runTest {
            val facts = ListeningSessionFacts { 1L }

            facts.cite("band|one", setOf(formed))

            assertEquals(setOf(formed), facts.citedByOthers("band|two"))
            assertEquals(emptySet(), facts.citedByOthers("band|one"))
            facts.cite("band|two", setOf(formed, label))
            assertEquals(setOf(formed, label), facts.citedByOthers("band|three"))
            // Track one's own fact counts once track two cited it too; track two's label does not for two.
            assertEquals(setOf(formed, label), facts.citedByOthers("band|one"))
            assertEquals(setOf(formed), facts.citedByOthers("band|two"))
        }

    @Test
    fun `another session starts empty`() =
        runTest {
            var session: Long? = null
            val facts = ListeningSessionFacts { session }

            facts.cite("band|one", setOf(formed))
            assertEquals(setOf(formed), facts.citedByOthers("band|two"))
            session = 1L
            assertEquals(emptySet(), facts.citedByOthers("band|two"))
            facts.cite("band|two", setOf(label))
            session = 2L
            assertEquals(emptySet(), facts.citedByOthers("band|three"))
        }

    @Test
    fun `forgetting drops every fact, even with the session unchanged`() =
        runTest {
            // *Clear history* restarts the session numbers, so the session read next may be the same.
            val facts = ListeningSessionFacts { 1L }

            facts.cite("band|one", setOf(formed))
            facts.forget()
            assertEquals(emptySet(), facts.citedByOthers("band|two"))
            facts.cite("band|two", setOf(label))
            assertEquals(setOf(label), facts.citedByOthers("band|three"))
        }

    @Test
    fun `a session that cannot be read counts as the one last read`() =
        runTest {
            var failing = false
            val facts = ListeningSessionFacts { if (failing) error("history down") else 1L }

            facts.cite("band|one", setOf(formed))
            failing = true
            facts.cite("band|two", setOf(label))

            assertEquals(setOf(formed, label), facts.citedByOthers("band|three"))
        }

    @Test
    fun `a slow read of the session before cannot clear what the next session kept`() =
        runTest {
            val slow = CompletableDeferred<Unit>()
            var reads = 0
            val facts =
                ListeningSessionFacts {
                    // The first read is of session 1 and lands late; every later one is session 2.
                    if (++reads == 1) {
                        slow.await()
                        1L
                    } else {
                        2L
                    }
                }

            val early = async { facts.citedByOthers("band|one") }
            runCurrent()
            val cite = async { facts.cite("band|two", setOf(formed)) }
            runCurrent()
            slow.complete(Unit)
            early.await()
            cite.await()

            assertEquals(setOf(formed), facts.citedByOthers("band|three"))
        }

    @Test
    fun `facts match by source and folded text, not by text to the character`() {
        assertEquals(formed, ArtistFact(SourceId.WIKIPEDIA, "the band formed\u00A0in 1960"))
        assertEquals(
            formed.hashCode(),
            ArtistFact(SourceId.WIKIPEDIA, " The band  formed in 1960. ").hashCode(),
        )
        assertEquals(label, ArtistFact(SourceId.WIKIDATA, "The band\u2019s label is Parlophone."))
        // Thin, hair and narrow no-break spaces, as Wikipedia uses.
        assertEquals(formed, ArtistFact(SourceId.WIKIPEDIA, "The band\u2009formed\u200Ain\u202F1960."))
        assertEquals(
            ArtistFact(SourceId.WIKIPEDIA, "Formed in 1960 \u2013 1961."),
            ArtistFact(SourceId.WIKIPEDIA, "Formed in 1960 - 1961."),
        )
        assertNotEquals(formed, ArtistFact(SourceId.WIKIDATA, formed.text), "another source")
        assertNotEquals(formed, ArtistFact(SourceId.WIKIPEDIA, "The band formed in 1961."), "other words")
    }

    @Test
    fun `none keeps nothing`() =
        runTest {
            SessionFacts.None.cite("band|one", setOf(formed))

            assertEquals(emptySet(), SessionFacts.None.citedByOthers("band|two"))
        }
}
