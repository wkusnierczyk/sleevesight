// Playlist positions are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package io.github.wkusnierczyk.sleevesight.cli

import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.pipeline.InsightState
import io.github.wkusnierczyk.sleevesight.core.pipeline.SourceState
import io.github.wkusnierczyk.sleevesight.core.pipeline.TrackState
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult

/** One playlist position's run. */
data class TrackResult(
    val n: Int,
    val state: TrackState,
)

/** How a gate criterion came out. */
enum class Verdict {
    PASS,
    FAIL,

    /** Nothing in this run could test it: the source was off, or no insight was written. */
    NOT_CHECKED,
}

data class Criterion(
    val name: String,
    val verdict: Verdict,
    val detail: String,
)

/**
 * The Phase 0.5 gate's criteria that a run can check (`design/design.md`, *Phase 0.5*; #46): zero
 * `unknown` citations, uncited sentences under 5 percent, and every listed same-name collision
 * either rejected or correctly resolved. The fourth, the owner's review of the coverage numbers,
 * is not a check.
 */
object Gate {
    /** The most uncited sentences may be of all the written insights' sentences, as a fraction. */
    const val MAX_UNCITED: Double = 0.05

    /**
     * A source's check of one collision: null when the result is right, else what is wrong.
     */
    class Check(
        val source: SourceId,
        val rule: String,
        val problem: (SourceResult) -> String?,
    )

    /** A same-name collision the design lists, with what a right result looks like per source. */
    class Collision(
        val tracks: List<Int>,
        val description: String,
        val checks: List<Check>,
    )

    private fun noWikipediaLines(vararg scopes: Scope): (SourceResult) -> String? =
        { result ->
            result.evidence
                .filter { it.source in WIKIPEDIA && it.scope in scopes }
                .takeIf { it.isNotEmpty() }
                ?.let { lines ->
                    "${lines.size} ${scopes.joinToString("/") { it.key }} line(s) cited: ${lines.first().text}"
                }
        }

    private val WIKIPEDIA = setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA)

    /**
     * The collisions of the design's regression cases (*Test fixtures*) and the identity check's
     * (`design/architecture.md`, *Entity resolution*), with what counts as rejected or correctly
     * resolved: an original song's article is right for a cover, the cover's own song article is not.
     */
    val COLLISIONS: List<Collision> =
        listOf(
            Collision(
                listOf(8, 22),
                "Peleboy's \"We In Here\" is not DMX's song of that name",
                listOf(
                    Check(SourceId.WIKIPEDIA, "no song article", noWikipediaLines(Scope.SONG, Scope.ORIGINAL_SONG)),
                ),
            ),
            Collision(
                listOf(12, 20),
                "Reem: Wikipedia's Reem is a different artist, and Last.fm's page is shared by several",
                listOf(
                    Check(SourceId.WIKIPEDIA, "no artist article", noWikipediaLines(Scope.ARTIST)),
                    Check(SourceId.LASTFM, "no cited Last.fm line") { result ->
                        result.evidence.takeIf { it.isNotEmpty() }?.let { "${it.size} Last.fm line(s) cited" }
                    },
                ),
            ),
            Collision(
                listOf(45, 48, 76),
                "Covers: Wikipedia describes the original song, never as this recording",
                listOf(
                    Check(
                        SourceId.WIKIPEDIA,
                        "no song line (original-song lines are right)",
                        noWikipediaLines(Scope.SONG),
                    ),
                ),
            ),
            Collision(
                listOf(61),
                "willow, Jessiah: Last.fm answers with Jesaiah, and Wikipedia's \"willow\" is Taylor Swift's",
                listOf(
                    Check(SourceId.LASTFM, "rejected") { result ->
                        if (result.outcome is Outcome.Rejected) null else "Last.fm was ${outcomeKey(result.outcome)}"
                    },
                    Check(SourceId.WIKIPEDIA, "no song article", noWikipediaLines(Scope.SONG)),
                ),
            ),
        )

    fun evaluate(results: List<TrackResult>): List<Criterion> {
        val written = results.mapNotNull { (it.state.insight as? InsightState.Written)?.grounded?.counts }
        val unknown = written.sumOf { it.unknown }
        val uncited = written.sumOf { it.uncited }
        val sentences = written.sumOf { it.total }
        return listOf(
            if (written.isEmpty()) {
                Criterion("Zero unknown citations", Verdict.NOT_CHECKED, "no insight was written")
            } else {
                Criterion(
                    "Zero unknown citations",
                    if (unknown == 0) Verdict.PASS else Verdict.FAIL,
                    "$unknown unknown in $sentences sentences of ${written.size} insights",
                )
            },
            if (sentences == 0) {
                Criterion("Uncited sentences under 5 percent", Verdict.NOT_CHECKED, "no insight was written")
            } else {
                val share = uncited.toDouble() / sentences
                Criterion(
                    "Uncited sentences under 5 percent",
                    if (share < MAX_UNCITED) Verdict.PASS else Verdict.FAIL,
                    "$uncited of $sentences sentences (${percent(share)})",
                )
            },
            collisions(results),
        )
    }

    private fun collisions(results: List<TrackResult>): Criterion {
        // Each check of each listed collision, for each of its tracks in this run with the source on.
        val outcomes =
            COLLISIONS.flatMap { collision ->
                results
                    .filter { it.n in collision.tracks }
                    .flatMap { result -> collision.checks.mapNotNull { check -> outcomeOf(result, check) } }
            }
        val failed = outcomes.filter { it.second != null }.map { "${it.first}: ${it.second}" }
        val passed = outcomes.filter { it.second == null }.map { it.first }
        val name = "Every listed same-name collision rejected or correctly resolved"
        return when {
            failed.isNotEmpty() -> {
                Criterion(name, Verdict.FAIL, failed.joinToString("; "))
            }

            passed.isEmpty() -> {
                Criterion(name, Verdict.NOT_CHECKED, "no listed collision was in this run with its source on")
            }

            else -> {
                Criterion(name, Verdict.PASS, "${passed.size} checks passed: ${passed.joinToString("; ")}")
            }
        }
    }

    // The check's label and its problem (null when right), or null when the source did not run.
    private fun outcomeOf(
        result: TrackResult,
        check: Check,
    ): Pair<String, String?>? {
        val done = result.state.sources[check.source] as? SourceState.Done
        return done?.result?.takeIf { it.outcome != Outcome.SourceOff }?.let {
            "track ${result.n}, ${check.source.tag}: ${check.rule}" to check.problem(it)
        }
    }

    private const val PER_MILLE = 1000
    private const val PER_CENT = 10.0

    /** [share] as a percentage, to one decimal. */
    fun percent(share: Double): String = "${(share * PER_MILLE).toLong() / PER_CENT} %"
}

/** An outcome as the design names it (`song_article`, `rejected`, …). */
fun outcomeKey(outcome: Outcome): String =
    when (outcome) {
        Outcome.SongArticle -> "song_article"
        Outcome.ArtistArticle -> "artist_article"
        Outcome.OriginalSong -> "original_song"
        is Outcome.Rejected -> "rejected"
        Outcome.None -> "none"
        Outcome.SourceOff -> "source_off"
        Outcome.Unreachable -> "unreachable"
    }
