package io.github.wkusnierczyk.sleevesight.core.resolution

import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.normalizer.Normalizer
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import io.github.wkusnierczyk.sleevesight.core.resolution.IdentityCheck.Route
import io.github.wkusnierczyk.sleevesight.core.resolution.IdentityCheck.Verdict
import io.github.wkusnierczyk.sleevesight.core.sources.OriginalRecording
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedWork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The identity check's rules, over a hand-written Wikidata. */
class IdentityCheckRulesTest {
    private val track = Normalizer(JvmAccentFolder).normalize("Song", "Cover Act")

    private class FakeWikidata(
        val entities: Map<String, WdEntity>,
        val labels: Map<String, String>,
        val mbids: Map<String, List<String>> = emptyMap(),
        val failing: Boolean = false,
    ) : WikidataLookup {
        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            if (failing) Lookup.Failed("Offline") else Lookup.Found(entities.filterKeys { it in ids })

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
            Lookup.Found(
                labels.filterKeys {
                    it in
                        ids
                },
            )

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = Lookup.Found(mbids[id].orEmpty())

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> = Lookup.Found(emptyList())
    }

    private val song =
        WdEntity("Q1", "Song", "Song", mapOf(Wd.INSTANCE_OF to listOf("Q7366"), Wd.PERFORMER to listOf("Q10")))
    private val wikidata =
        FakeWikidata(
            mapOf("Q1" to song),
            mapOf("Q10" to "Original Act"),
            mapOf(
                "Q10" to listOf("mbid-original"),
            ),
        )
    private val check = IdentityCheck(wikidata, NameMatcher(JvmAccentFolder))

    private val coverAct = ResolvedArtist("Cover Act", "mbid-cover")
    private val resolved = Resolution(outcome = Outcome.SongArticle, recordingMbid = "r", artists = listOf(coverAct))

    @Test
    fun `the item MusicBrainz links to a cover's work is the original song`() =
        runTest {
            val cover = resolved.copy(work = ResolvedWork("w", "Song"), cover = true, wikidataIds = listOf("Q1"))
            val verdict = check.song("Q1", track, cover, Route.MUSICBRAINZ)
            assertIs<Verdict.Accepted>(verdict)
            assertEquals(Scope.ORIGINAL_SONG, verdict.scope)
        }

    @Test
    fun `a performer who is the original recording's artist makes the original song`() =
        runTest {
            val original =
                OriginalRecording(
                    "o",
                    "Song",
                    listOf(ResolvedArtist("Original Act", "mbid-original")),
                    "Original Act",
                    "1975",
                )
            val verdict = check.song("Q1", track, resolved.copy(cover = true, original = original), Route.NAME)
            assertIs<Verdict.Accepted>(verdict)
            assertEquals(Scope.ORIGINAL_SONG, verdict.scope)
        }

    @Test
    fun `found by name, a same-title song by someone else is rejected`() =
        runTest {
            assertEquals(
                Verdict.Rejected("Q1", "“Song” is by Original Act, not by Cover Act"),
                check.song("Q1", track, Resolution.NONE, Route.NAME),
            )
        }

    @Test
    fun `a resolved artist is matched by MusicBrainz id only, never by name`() =
        runTest {
            val namesake = FakeWikidata(mapOf("Q1" to song), mapOf("Q10" to "Cover Act"))
            val verdict = IdentityCheck(namesake, NameMatcher(JvmAccentFolder)).song("Q1", track, resolved, Route.NAME)
            assertIs<Verdict.Rejected>(verdict)
            val byName =
                IdentityCheck(
                    namesake,
                    NameMatcher(JvmAccentFolder),
                ).song("Q1", track, Resolution.NONE, Route.NAME)
            assertIs<Verdict.Accepted>(byName)
            assertEquals(Scope.SONG, byName.scope)
        }

    @Test
    fun `Wikidata not answering is unreachable, not a rejection`() =
        runTest {
            val down = IdentityCheck(FakeWikidata(emptyMap(), emptyMap(), failing = true), NameMatcher(JvmAccentFolder))
            assertEquals(Verdict.Unreachable, down.song("Q1", track, resolved, Route.NAME))
        }
}
