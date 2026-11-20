package app.sleevesight.core.synthesis

// Spike #272, part 2: the Mac side of the on-device bench. Not part of the build: `mac/bench.sh`
// copies this file into `core/src/test/kotlin/app/sleevesight/core/synthesis/`, runs one of its
// tests, and removes it again.
//
// - `write bench prompts` writes every fixture track's insight prompt (system and user message, as
//   `InsightPrompt` builds them today, model knowledge off) to `$BENCH_OUT`, the bench app's asset.
// - `check bench replies` reads a results folder pulled from the phone (`$BENCH_RESULTS`, holding
//   `replies.jsonl`) and runs the app's `GroundingCheck` on every reply, writing `checked.jsonl` and
//   `checked-summary.txt` next to it.

import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.fixtures.BelowSoundtrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import kotlin.test.Test

class BenchSpike272 {
    private val normalizer = Normalizer(JvmAccentFolder)

    /** The first occurrence of each track in the fixture playlist, in playlist order. */
    private fun uniqueTracks(): List<Int> =
        BelowSoundtrack.tracks
            .map { it.n }
            .filter { BelowSoundtrack.firstOccurrence(it) == it }

    @Test
    fun `write bench prompts`() {
        val out = System.getenv("BENCH_OUT") ?: return
        val system = InsightPrompt.system(allowModel = false)
        val prompts =
            buildJsonArray {
                for (n in uniqueTracks()) {
                    val evidence = FixtureEvidence.of(n)
                    val nowPlaying = FixtureEvidence.nowPlaying(n)
                    val normalized = normalizer.normalize(nowPlaying)
                    if (evidence.nothingToNarrate(allowModel = false, artistsKnown = normalized.artists.isNotEmpty())) {
                        continue
                    }
                    val user = InsightPrompt.user(nowPlaying, normalized, evidence)
                    add(
                        buildJsonObject {
                            put("key", "$n")
                            put("n", n)
                            put("title", nowPlaying.title)
                            put("artist", nowPlaying.artists)
                            put("user", user)
                            put("chars", system.length + user.length)
                            putJsonArray("ids") { evidence.ids.forEach { add(it) } }
                        },
                    )
                }
            }
        val doc =
            buildJsonObject {
                put("promptVersion", InsightPrompt.VERSION)
                put("system", system)
                put("prompts", prompts)
            }
        File(out).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), doc) + "\n")
        println("wrote ${prompts.size} prompts to $out")
    }

    @Test
    fun `check bench replies`() {
        val dir = System.getenv("BENCH_RESULTS")?.let(::File) ?: return
        val replies =
            dir.resolve("replies.jsonl").readLines().filter { it.isNotBlank() }.map {
                Json.parseToJsonElement(it).jsonObject
            }
        val checked = StringBuilder()
        var sentences = 0
        var ok = 0
        var uncited = 0
        var unknown = 0
        var unsupported = 0
        var noEvidence = 0
        var clean = 0
        for (reply in replies) {
            val n = reply.getValue("n").jsonPrimitive.int
            val text = reply.getValue("reply").jsonPrimitive.content
            val nowPlaying = FixtureEvidence.nowPlaying(n)
            val subject = listOfNotNull(nowPlaying.title, nowPlaying.artists, nowPlaying.album)
            val result = GroundingCheck.check(text, FixtureEvidence.of(n), allowModel = false, subject = subject)
            val line =
                buildJsonObject {
                    put("run", reply.getValue("run").jsonPrimitive.content)
                    put("n", n)
                    when (result) {
                        InsightReply.NoEvidence -> {
                            noEvidence++
                            put("noEvidence", true)
                        }
                        is InsightReply.Written -> {
                            val c = result.grounded.counts
                            sentences += c.total
                            ok += c.ok
                            uncited += c.uncited
                            unknown += c.unknown
                            unsupported += c.unsupported
                            if (c.uncited + c.unknown + c.unsupported == 0) clean++
                            put("ok", c.ok)
                            put("hedge", c.hedge)
                            put("uncited", c.uncited)
                            put("unknown", c.unknown)
                            put("unsupported", c.unsupported)
                            putJsonArray("flagged") {
                                result.grounded.paragraphs
                                    .flatten()
                                    .filter { it.status != SentenceStatus.OK && it.status != SentenceStatus.HEDGE }
                                    .forEach { add("${it.status}: ${it.text}") }
                            }
                        }
                    }
                }
            checked.appendLine(line.toString())
        }
        dir.resolve("checked.jsonl").writeText(checked.toString())
        val summary =
            "replies ${replies.size}, NO_EVIDENCE $noEvidence, sentences $sentences, ok $ok, " +
                "uncited $uncited, unknown ids $unknown, unsupported (by words) $unsupported, " +
                "clean replies $clean\n"
        dir.resolve("checked-summary.txt").writeText(summary)
        print(summary)
    }
}
