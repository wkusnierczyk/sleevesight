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
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One track's run: [n] is its playlist position, or its place in a tracklist (`--tracklist`), from 1.
 * [pass] is which pass of a repeated run (`--repeat`) it belongs to, from 1, or null when the run
 * is not repeated.
 */
data class TrackResult(
    val n: Int,
    val state: TrackState,
    val pass: Int? = null,
) {
    /** How the report names it: its number, and its pass when the run is repeated. */
    val label: String get() = pass?.let { "$n, pass $it" } ?: "$n"
}

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
 * either rejected or correctly resolved. The fourth, the review of the coverage numbers, is not a
 * check.
 *
 * Every criterion is judged over all the run's passes together (`--repeat`). Uncited sentences
 * in live replies are judged over a sample of at least [Sample.LIVE] sentences, since they vary
 * from one call to the next (#399): an insight is two to four sentences, so a pass of the
 * regression tracks has about 20, and one uncited sentence in it is already 5 percent. A smaller
 * sample is not judged, but fails on a gross share ([GROSS_UNCITED]).
 */
object Gate {
    /** The most uncited sentences may be of all the written insights' sentences, as a fraction. */
    const val MAX_UNCITED: Double = 0.05

    /**
     * The share of uncited sentences that fails a sample too small to judge ([Sample.LIVE]): one
     * pass of the regression tracks is about 20 sentences, so 15 percent is three uncited, well
     * above the one or two that live replies give by chance, and what a prompt that drops citations
     * wholesale would show at once.
     */
    const val GROSS_UNCITED: Double = 0.15

    /**
     * The least over which uncited sentences are judged: [sentences] over all of a run's passes,
     * whatever their number, so one pass of the whole playlist (about 280) is a full sample, as is
     * one of the regression tracks repeated (`--repeat`).
     */
    data class Sample(
        val sentences: Int = 0,
    ) {
        companion object {
            /**
             * For live replies: 50 sentences, at which one uncited sentence is 2 percent and it takes
             * three to fail. Three passes of the regression tracks (about 20 sentences each) reach it,
             * for a few cents.
             */
            val LIVE: Sample = Sample(sentences = 50)
        }
    }

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
     * [allowModel] is the run's *Model's own knowledge* switch, which decides, with whether the
     * track's report named its artists, whether a track with only the player's release line had
     * anything to narrate. [playlist] is whether [results] are the fixture playlist's positions:
     * the collisions are listed by position, so a run over a tracklist (`--tracklist`) leaves them
     * unchecked, as nothing in it applied. [sample] is the least over which uncited sentences are
     * judged ([uncitedFails]). Under it the uncited criterion is not checked, and the run does not
     * fail on it, but for a gross share; if an insight call failed too, it is incomplete, as any
     * criterion a failed call left unchecked, and the run exits [Cli.EXIT_UNCHECKED].
     */
    fun evaluate(
        results: List<TrackResult>,
        insightsAsked: Boolean,
        allowModel: Boolean = false,
        playlist: Boolean = true,
        sample: Sample = Sample(),
    ): List<Criterion> =
        citations(results, insightsAsked, allowModel, sample) +
            if (playlist) {
                collisions(results)
            } else {
                Criterion(
                    COLLISIONS_NAME,
                    Verdict.NOT_CHECKED,
                    "a tracklist run: the collisions are playlist positions",
                )
            }

    /**
     * Whether [uncited] of [sentences] fail the uncited criterion judged over [sample]: with a
     * sample that large, when their share is not under [MAX_UNCITED]; with a smaller one, which is
     * not judged, only when it is [GROSS_UNCITED] or more (one pass of 20: 3 uncited).
     */
    fun uncitedFails(
        uncited: Int,
        sentences: Int,
        sample: Sample,
    ): Boolean {
        if (sentences == 0) return false
        val limit = if (sentences >= sample.sentences) MAX_UNCITED else GROSS_UNCITED
        return uncited.toDouble() / sentences >= limit
    }

    private fun citations(
        results: List<TrackResult>,
        insightsAsked: Boolean,
        allowModel: Boolean,
        sample: Sample,
    ): List<Criterion> {
        // The tracks with something to narrate: the others get no call (`EvidenceSet.nothingToNarrate`).
        val withEvidence =
            results.filter {
                val artistsKnown =
                    it.state.normalized.artists
                        .isNotEmpty()
                it.state.evidence?.nothingToNarrate(allowModel, artistsKnown) == false
            }
        val writtenResults = withEvidence.filter { it.state.insight is InsightState.Written }
        val written = writtenResults.map { (it.state.insight as InsightState.Written).grounded.counts }
        val failed =
            withEvidence.mapNotNull { r ->
                (r.state.insight as? InsightState.Failed)?.let { r.label to it.reason }
            }
        val passes = passesWithSentences(writtenResults)
        val repeated = results.map { it.pass }.distinct().size > 1
        val tally =
            "${written.size} of ${withEvidence.size} insights written" +
                (if (repeated) " over $passes passes with sentences" else "") +
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
        // [judged] is false for the uncited criterion over a smaller sample than it needs, which it
        // [note]s: it fails then only on a gross share ([uncitedFails]).
        fun criterion(
            name: String,
            holds: Boolean,
            detail: String,
            judged: Boolean = true,
            note: String = "",
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
                !holds -> Criterion(name, Verdict.FAIL, "$detail; $tally$note")
                !judged -> Criterion(name, Verdict.NOT_CHECKED, "$detail; $tally$note", failed.isNotEmpty())
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
        val enough = sentences >= sample.sentences
        val fails = uncitedFails(uncited, sentences, sample)
        val note = if (enough) "" else uncitedNote(fails, sample, passes, sentences)
        return listOf(
            criterion(UNKNOWN_CITATIONS, unknown == 0, "$unknown unknown in $sentences sentences"),
            criterion(UNCITED, !fails, "$uncited of $sentences sentences (${percent(share)})", enough, note),
        )
    }

    // The passes whose [written] insights gave a sentence: one of only NO_EVIDENCE, failed calls or
    // empty replies gives none, and the report counts it out.
    private fun passesWithSentences(written: List<TrackResult>): Int =
        written
            .filter { ((it.state.insight as? InsightState.Written)?.grounded?.counts?.total ?: 0) > 0 }
            .map { it.pass }
            .distinct()
            .size

    // What the uncited criterion says over a smaller [sample] than it needs, [passes] passes with
    // [sentences]: that a gross share [fails] it, or how many passes at their length would judge it.
    private fun uncitedNote(
        fails: Boolean,
        sample: Sample,
        passes: Int,
        sentences: Int,
    ): String =
        if (fails) {
            "; ${(GROSS_UNCITED * PERCENT).roundToInt()} percent or more fails however small the sample"
        } else {
            val needed = ceil(sample.sentences * passes.toDouble() / sentences).toInt()
            "; judged over at least ${sample.sentences} sentences of live replies, which vary: about $needed " +
                "passes of this length (--repeat $needed)"
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
        val label = "track ${result.label}, ${check.source.tag}: ${check.rule}"
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
    private const val PERCENT = 100

    /** [share] as a percentage, to one decimal. */
    fun percent(share: Double): String = "${(share * PER_MILLE).toLong() / PER_CENT} %"
}
