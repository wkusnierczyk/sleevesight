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
    fun `an unresolved artist's name does not match a performer MusicBrainz knows`() =
        runTest {
            // "Wonderland" by an unresolved Reem, against a Wonderland by a Reem with a MusicBrainz id.
            val reem = Normalizer(JvmAccentFolder).normalize("Wonderland", "Reem")
            val wonderland =
                WdEntity(
                    "Q2",
                    "Wonderland",
                    "Wonderland",
                    mapOf(
                        Wd.INSTANCE_OF to listOf("Q7366"),
                        Wd.PERFORMER to listOf("Q20"),
                    ),
                )
            val other =
                FakeWikidata(
                    mapOf("Q2" to wonderland),
                    mapOf("Q20" to "Reem"),
                    mapOf(
                        "Q20" to listOf("mbid-other-reem"),
                    ),
                )
            val verdict =
                IdentityCheck(
                    other,
                    NameMatcher(JvmAccentFolder),
                ).song("Q2", reem, Resolution.NONE, Route.NAME)
            assertIs<Verdict.Rejected>(verdict)
        }

    @Test
    fun `a credited writer confirms a song only when no one is named performing it`() =
        runTest {
            val songwriter = Normalizer(JvmAccentFolder).normalize("Song", "Writer")
            val performed =
                WdEntity(
                    "Q3",
                    "Song",
                    "Song",
                    mapOf(
                        Wd.INSTANCE_OF to listOf("Q7366"),
                        Wd.PERFORMER to listOf("Q30"),
                        Wd.COMPOSER to listOf("Q31"),
                    ),
                )
            val anthem =
                WdEntity(
                    "Q4",
                    "Anthem",
                    "Anthem",
                    mapOf(
                        Wd.INSTANCE_OF to listOf("Q7366"),
                        Wd.COMPOSER to listOf("Q31"),
                    ),
                )
            val wikidata =
                FakeWikidata(
                    mapOf("Q3" to performed, "Q4" to anthem),
                    mapOf(
                        "Q30" to "Singer",
                        "Q31" to "Writer",
                    ),
                )
            val writerCheck = IdentityCheck(wikidata, NameMatcher(JvmAccentFolder))
            assertIs<Verdict.Rejected>(writerCheck.song("Q3", songwriter, Resolution.NONE, Route.NAME))
            val verdict = writerCheck.song("Q4", songwriter, Resolution.NONE, Route.NAME)
            assertIs<Verdict.Accepted>(verdict)
            assertEquals(Scope.SONG, verdict.scope)
        }

    @Test
    fun `the item MusicBrainz links is the original song when its performers are others, cover marked or not`() =
        runTest {
            val unmarked = resolved.copy(work = ResolvedWork("w", "Song"), cover = false, wikidataIds = listOf("Q1"))
            val verdict = check.song("Q1", track, unmarked, Route.MUSICBRAINZ)
            assertIs<Verdict.Accepted>(verdict)
            assertEquals(Scope.ORIGINAL_SONG, verdict.scope)
        }

    @Test
    fun `the item MusicBrainz links, naming no performer, is the track's own song`() =
        runTest {
            val unperformed = WdEntity("Q5", "Song", "Song", mapOf(Wd.INSTANCE_OF to listOf("Q7366")))
            val linked =
                IdentityCheck(FakeWikidata(mapOf("Q5" to unperformed), emptyMap()), NameMatcher(JvmAccentFolder))
            val own = resolved.copy(work = ResolvedWork("w", "Song"), cover = false, wikidataIds = listOf("Q5"))
            val verdict = linked.song("Q5", track, own, Route.MUSICBRAINZ)
            assertIs<Verdict.Accepted>(verdict)
            assertEquals(Scope.SONG, verdict.scope)
        }

    @Test
    fun `the item MusicBrainz links stays the track's own song when a performer is a credited artist by name`() =
        runTest {
            val ownPerformer =
                WdEntity(
                    "Q6",
                    "Song",
                    "Song",
                    mapOf(
                        Wd.INSTANCE_OF to listOf("Q7366"),
                        Wd.PERFORMER to listOf("Q60"),
                    ),
                )
            // An own-name performer with no P434, against a resolved artist.
            val noId = FakeWikidata(mapOf("Q6" to ownPerformer), mapOf("Q60" to "Cover Act"))
            val linked = resolved.copy(work = ResolvedWork("w", "Song"), cover = false, wikidataIds = listOf("Q6"))
            val verdict = IdentityCheck(noId, NameMatcher(JvmAccentFolder)).song("Q6", track, linked, Route.MUSICBRAINZ)
            assertIs<Verdict.Accepted>(verdict)
            assertEquals(Scope.SONG, verdict.scope)
            // An unresolved co-artist whose performer item has a P434.
            val coArtist = Normalizer(JvmAccentFolder).normalize("Song", "Band, Singer")
            val withId =
                FakeWikidata(
                    mapOf("Q6" to ownPerformer),
                    mapOf("Q60" to "Singer"),
                    mapOf(
                        "Q60" to listOf("mbid-singer"),
                    ),
                )
            val partly =
                linked.copy(
                    artists = listOf(ResolvedArtist("Band", "mbid-band"), ResolvedArtist("Singer", null)),
                )
            val coVerdict =
                IdentityCheck(
                    withId,
                    NameMatcher(JvmAccentFolder),
                ).song("Q6", coArtist, partly, Route.MUSICBRAINZ)
            assertIs<Verdict.Accepted>(coVerdict)
            assertEquals(Scope.SONG, coVerdict.scope)
        }

    @Test
    fun `Wikidata not answering is unreachable, not a rejection`() =
        runTest {
            val down = IdentityCheck(FakeWikidata(emptyMap(), emptyMap(), failing = true), NameMatcher(JvmAccentFolder))
            assertEquals(Verdict.Unreachable, down.song("Q1", track, resolved, Route.NAME))
        }
}
