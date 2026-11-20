package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Properties of how the sources' genre tags weigh (#799, #875, #1019), over tags drawn from the
 * bundled vocabulary: a track's tag with a single vote never outweighs the album's and the
 * artists' tags among the chips, and a vote more never lowers a genre's rank.
 */
class GenreWeightingPropertiesTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    // One tag per genre, so two tags never name one genre; Instrumental left out, since its chip is
    // weighed by signs of vocals (#996) rather than by votes alone.
    private val genreTags: List<String> =
        vocabulary.tags
            .sorted()
            .mapNotNull { tag -> vocabulary.identity(tag)?.let { it to tag } }
            .filter { (identity, _) -> identity != TrackGenres.INSTRUMENTAL }
            .distinctBy { (identity, _) -> identity }
            .map { (_, tag) -> tag }

    // A few distinct tags, in an order of their own, at most [max].
    private fun tags(max: Int) = Arb.shuffle(genreTags).map { it.take(max) }

    private fun counted(
        tags: List<String>,
        counts: List<Int>,
    ): List<CountedTag> = tags.mapIndexed { i, tag -> CountedTag(tag, counts[i % counts.size]) }

    @Test
    fun `a track's tag with one vote never outweighs the album's and the artists' tags`() =
        runTest {
            checkAll(
                CONFIG,
                Arb.bind(tags(4), Arb.int(1..4)) { all, n -> all.take(n) },
                Arb.bind(tags(TAGS), Arb.int(0..3), Arb.int(1..TAGS)) { all, album, artist ->
                    all.take(album) to all.drop(album).take(artist)
                },
                Arb.list(Arb.int(1..100), 1..TAGS),
            ) { track, (album, artist), counts ->
                // The recording's tags as MusicBrainz gives them, one vote each (#799's case).
                val recording =
                    vocabulary.labelsFor(
                        track,
                        level = GenreLevel.TRACK,
                        votes = track.associateWith { 1 },
                        counts = track.associateWith { 1 },
                        capped = false,
                        fullCount = MusicBrainzLabels.FULL_WEIGHT_VOTES,
                    )
                val genres =
                    combineGenres(
                        mapOf(
                            LabelSource.MUSICBRAINZ to recording,
                            LabelSource.LASTFM to
                                vocabulary.sourceLabels(counted(album, counts), null, GenreLevel.ALBUM) +
                                vocabulary.sourceLabels(counted(artist, counts), null, GenreLevel.ARTIST, ARTIST),
                        ),
                        listOf(ARTIST),
                    )
                val backing =
                    genres.rows
                        .filter { it.level != GenreLevel.TRACK }
                        .flatMap { row -> row.labels.map { it.genre.identity } }
                        .toSet()
                // Every chip of a genre only the track's one-vote tags give is weak...
                for (chip in genres.chips()) {
                    val alone = chip.row.level == GenreLevel.TRACK && chip.label.genre.identity !in backing
                    if (alone) assertTrue(chip.weak, "${chip.label.genre.label} is backed by one vote")
                }
                // ...so the panel never opens on one while the album or an artist has a genre.
                val opening = assertNotNull(genres.opening())
                assertTrue(
                    opening.row.level != GenreLevel.TRACK || opening.label.genre.identity in backing,
                    "opens on ${opening.label.genre.label}, a one-vote track tag, over $backing",
                )
            }
        }

    @Test
    fun `a vote more never lowers a genre's rank`() =
        runTest {
            checkAll(
                CONFIG,
                Arb.bind(tags(TAGS * 2), Arb.int(1..TAGS), Arb.int(0..TAGS)) { all, mb, lastFm ->
                    all.take(mb) to all.drop(mb).take(lastFm)
                },
                Arb.list(Arb.int(1..MAX_VOTES), TAGS..TAGS),
                Arb.list(Arb.int(1..100), TAGS..TAGS),
                Arb.int(0 until TAGS),
            ) { (mbTags, lastFmTags), votes, weights, pick ->
                val voted = mbTags[pick % mbTags.size]
                val before = mbTags.mapIndexed { i, tag -> tag to votes[i] }.toMap()
                val after = before + (voted to before.getValue(voted) + 1)
                val identity = checkNotNull(vocabulary.identity(voted))

                fun rank(votesOf: Map<String, Int>): Int =
                    rankLabels(
                        mapOf(
                            LabelSource.MUSICBRAINZ to
                                vocabulary.labelsFor(
                                    mbTags,
                                    level = GenreLevel.TRACK,
                                    votes = votesOf,
                                    counts = votesOf,
                                    capped = false,
                                    fullCount = MusicBrainzLabels.FULL_WEIGHT_VOTES,
                                ),
                            LabelSource.LASTFM to
                                vocabulary.sourceLabels(counted(lastFmTags, weights), null, GenreLevel.TRACK),
                        ),
                    ).indexOfFirst { it.genre.identity == identity }

                val was = rank(before)
                val now = rank(after)
                assertTrue(was >= 0 && now >= 0, "$voted ranked ${was + 1}, then ${now + 1}: a genre left out")
                assertTrue(now <= was, "$voted fell from ${was + 1} to ${now + 1} with a vote more")
            }
        }

    // A regression guard: `rankLabels` walks `LABEL_TIE_ORDER`, never the map, so this holds by
    // construction today and fails only if the ranking comes to depend on the map's order.
    @Test
    fun `the order the sources are given in does not change the ranking`() =
        runTest {
            checkAll(
                CONFIG,
                Arb.bind(tags(TAGS * 2), Arb.int(0..TAGS), Arb.int(0..TAGS)) { all, mb, lastFm ->
                    all.take(mb) to all.drop(mb).take(lastFm)
                },
                Arb.list(Arb.int(1..MAX_VOTES), 1..TAGS),
            ) { (mbTags, lastFmTags), counts ->
                val mb = vocabulary.sourceLabels(counted(mbTags, counts), null, GenreLevel.TRACK)
                val lastFm = vocabulary.sourceLabels(counted(lastFmTags, counts.reversed()), null, GenreLevel.TRACK)
                assertEquals(
                    rankLabels(mapOf(LabelSource.MUSICBRAINZ to mb, LabelSource.LASTFM to lastFm)),
                    rankLabels(mapOf(LabelSource.LASTFM to lastFm, LabelSource.MUSICBRAINZ to mb)),
                )
            }
        }

    private companion object {
        @OptIn(ExperimentalKotest::class)
        val CONFIG = PropTestConfig(seed = 799L, iterations = 300)
        const val ARTIST = "Emily Jane White"
        const val TAGS = 5
        const val MAX_VOTES = 30
    }
}
