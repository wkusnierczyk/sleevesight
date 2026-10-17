// Playlist positions are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package app.sleevesight.cli

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.model.outcomeKey
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.SourceResult

/** One playlist position's run. */
data class TrackResult(
    val n: Int,
    val state: TrackState,
)

/** How a gate criterion came out. */
enum class Verdict {
    PASS,
    FAIL,

    /**
     * The run could not test it: nothing in it applied (a source switched off, no insight asked
     * for), or, when the criterion is [Criterion.incomplete], a lookup or a call it needed failed.
     */
    NOT_CHECKED,
}

/**
 * One criterion's outcome. [incomplete] marks a [Verdict.NOT_CHECKED] that the run should have
 * checked but could not, because a source was unreachable or failed, or an insight call failed:
 * the CLI exits non-zero for it ([Cli.EXIT_UNCHECKED]), so a gate run cannot pass on what it did
 * not test.
 */
data class Criterion(
    val name: String,
    val verdict: Verdict,
    val detail: String,
    val incomplete: Boolean = false,
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

    /**
     * The criteria over [results]. [insightsAsked] is whether the run asked for insights: then a
     * failed insight call leaves the citation criteria incomplete rather than quietly smaller.
     */
    fun evaluate(
        results: List<TrackResult>,
        insightsAsked: Boolean,
    ): List<Criterion> = citations(results, insightsAsked) + collisions(results)

    private fun citations(
        results: List<TrackResult>,
        insightsAsked: Boolean,
    ): List<Criterion> {
        val withEvidence = results.filter { it.state.evidence?.isEmpty() == false }
        val written = withEvidence.mapNotNull { (it.state.insight as? InsightState.Written)?.grounded?.counts }
        val failed =
            withEvidence.mapNotNull { r ->
                (r.state.insight as? InsightState.Failed)?.let { r.n to it.reason }
            }
        val tally =
            "${written.size} of ${withEvidence.size} insights written" +
                if (failed.isEmpty()) {
                    ""
                } else {
                    "; ${failed.size} failed (${failed.joinToString {
                        "${it.first}: ${it.second}"
                    }})"
                }
        val unknown = written.sumOf { it.unknown }
        val uncited = written.sumOf { it.uncited }
        val sentences = written.sumOf { it.total }

        // The written insights decide first: one that fails a criterion fails it, whatever else failed.
        // Only when none fails does a failed call leave the criterion unchecked, since it might have.
        fun criterion(
            name: String,
            passes: Boolean,
            detail: String,
        ): Criterion =
            when {
                !insightsAsked -> Criterion(name, Verdict.NOT_CHECKED, "no insight was asked for")
                sentences == 0 ->
                    Criterion(
                        name,
                        Verdict.NOT_CHECKED,
                        "no insight was written ($tally)",
                        failed.isNotEmpty(),
                    )
                !passes -> Criterion(name, Verdict.FAIL, "$detail; $tally")
                failed.isNotEmpty() ->
                    Criterion(
                        name,
                        Verdict.NOT_CHECKED,
                        "$detail; $tally: not every insight could be checked",
                        true,
                    )
                else -> Criterion(name, Verdict.PASS, "$detail; $tally")
            }
        val share = if (sentences == 0) 0.0 else uncited.toDouble() / sentences
        return listOf(
            criterion(UNKNOWN_CITATIONS, unknown == 0, "$unknown unknown in $sentences sentences"),
            criterion(UNCITED, share < MAX_UNCITED, "$uncited of $sentences sentences (${percent(share)})"),
        )
    }

    /** One listed check on one track: passed, failed with a problem, or unchecked with a reason. */
    private sealed interface CheckOutcome {
        val label: String

        data class Passed(
            override val label: String,
        ) : CheckOutcome

        data class Failed(
            override val label: String,
            val problem: String,
        ) : CheckOutcome

        data class Unchecked(
            override val label: String,
            val reason: String,
        ) : CheckOutcome
    }

    private fun collisions(results: List<TrackResult>): Criterion {
        val outcomes =
            COLLISIONS.flatMap { collision ->
                results
                    .filter { it.n in collision.tracks }
                    .flatMap { result -> collision.checks.mapNotNull { check -> outcomeOf(result, check) } }
            }
        val failed = outcomes.filterIsInstance<CheckOutcome.Failed>().map { "${it.label}: ${it.problem}" }
        val unchecked = outcomes.filterIsInstance<CheckOutcome.Unchecked>().map { "${it.label}: ${it.reason}" }
        val passed = outcomes.filterIsInstance<CheckOutcome.Passed>().map { it.label }
        return when {
            failed.isNotEmpty() -> {
                Criterion(
                    COLLISIONS_NAME,
                    Verdict.FAIL,
                    (failed + unchecked.map { "unchecked: $it" }).joinToString("; "),
                )
            }

            unchecked.isNotEmpty() -> {
                Criterion(
                    COLLISIONS_NAME,
                    Verdict.NOT_CHECKED,
                    "unchecked: ${unchecked.joinToString("; ")}",
                    incomplete = true,
                )
            }

            passed.isEmpty() -> {
                Criterion(
                    COLLISIONS_NAME,
                    Verdict.NOT_CHECKED,
                    "no listed collision was in this run with its source on",
                )
            }

            else -> {
                Criterion(COLLISIONS_NAME, Verdict.PASS, "${passed.size} checks passed: ${passed.joinToString("; ")}")
            }
        }
    }

    // A source switched off (or not run) is not a check; one that failed or was unreachable is a
    // check that could not be made.
    private fun outcomeOf(
        result: TrackResult,
        check: Check,
    ): CheckOutcome? {
        val label = "track ${result.n}, ${check.source.tag}: ${check.rule}"
        return when (val state = result.state.sources[check.source]) {
            null, SourceState.Pending -> null
            is SourceState.Failed -> CheckOutcome.Unchecked(label, "the lookup failed (${state.reason})")
            is SourceState.Done ->
                when (state.result.outcome) {
                    Outcome.SourceOff -> null
                    Outcome.Unreachable -> CheckOutcome.Unchecked(label, "the source was unreachable")
                    else ->
                        check.problem(state.result)?.let { CheckOutcome.Failed(label, it) }
                            ?: CheckOutcome.Passed(label)
                }
        }
    }

    private const val UNKNOWN_CITATIONS = "Zero unknown citations"
    private const val UNCITED = "Uncited sentences under 5 percent"
    private const val COLLISIONS_NAME = "Every listed same-name collision rejected or correctly resolved"

    private const val PER_MILLE = 1000
    private const val PER_CENT = 10.0

    /** [share] as a percentage, to one decimal. */
    fun percent(share: Double): String = "${(share * PER_MILLE).toLong() / PER_CENT} %"
}
