package app.sleevesight.core.pipeline

import app.sleevesight.core.model.SourceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `none keeps nothing`() =
        runTest {
            SessionFacts.None.cite("band|one", setOf(formed))

            assertEquals(emptySet(), SessionFacts.None.citedByOthers("band|two"))
        }
}
