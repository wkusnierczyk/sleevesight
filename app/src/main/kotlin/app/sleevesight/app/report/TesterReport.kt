package app.sleevesight.app.report

import app.sleevesight.app.diagnostics.DiagnosticsText
import app.sleevesight.app.diagnostics.LoggedEvent
import app.sleevesight.app.diagnostics.PageResult
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.outcomeKey
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.LEGACY_MODEL_NOTE
import app.sleevesight.core.synthesis.citesLegacyModelId
import app.sleevesight.store.TrackChangeRecord
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * The app's build, as a tester's report names it: its version, and whether it is the build for
 * Play's testing tracks (#451), in which developer mode and the report button are on by default.
 */
data class AppBuild(
    val versionName: String,
    val versionCode: Long,
    val testBuild: Boolean,
)

/**
 * What the app knows at the moment the tester taps the report button (#452), held as it was then
 * while the tester reviews it: the time ([at], in [zone]); the [build]; the [settings] (never a key:
 * the keys are not settings, and nothing here reaches the key store); what the player reports
 * ([playing], [playback], with the [position] worked out at the tap); the pipeline's [view], with
 * the track's evidence, insight and citation check; and the diagnostics: the pipeline's [events]
 * kept in memory and what skip detection has [learned] (null when nothing supplies it). The
 * track-change history is not in it: it is read only once the tester turns it on
 * ([TesterReports.history]), as of [at].
 */
@Suppress("LongParameterList") // Each thing the app knows at the tap, held apart.
class TesterSnapshot(
    val at: Instant,
    val zone: ZoneId,
    val build: AppBuild,
    val settings: Settings?,
    val playing: NowPlaying?,
    val playback: PlaybackInfo,
    val position: Duration?,
    val view: PipelineView,
    val events: List<LoggedEvent>,
    val learned: Map<String, Duration>?,
)

/**
 * The parts of a tester's report, in the order it holds them, each named by its [label]. The
 * tester can edit each and leave each out; [HISTORY] is left out until the tester turns it on
 * ([includedAtFirst]), since it lists every track played in the history kept.
 */
enum class TesterPart(
    val label: String,
    val includedAtFirst: Boolean = true,
) {
    APP("App"),
    SETTINGS("Settings"),
    NOW_PLAYING("Now playing"),
    DIAGNOSTICS("Diagnostics"),
    HISTORY("Track-change history", includedAtFirst = false),
}

/**
 * A tester's report as text (#452): each part as plain lines in English, which the tester reads,
 * edits and sends, and the developer reads. Built only from a [TesterSnapshot] and the history's
 * pages, so no key can reach it; the diagnostics' lines are the export's ([DiagnosticsText]).
 */
object TesterReport {
    /**
     * The longest report, in characters with its address and subject, that is handed to a mail app
     * or copied: 500 KB as Android passes text (UTF-16), half of what Android lets one app hand
     * another (Binder's 1 MB), so the intent's other parts and the mail app's own fit too. Past it,
     * handing it over would fail, or crash the app.
     */
    const val MAX_CHARS: Int = 250_000

    /** Whether [mail] is short enough to hand to a mail app or copy ([MAX_CHARS]). */
    fun fits(mail: Mail): Boolean = mail.asText().length <= MAX_CHARS

    /** The most track changes the history part holds: the latest, so a mail app can take the report. */
    const val HISTORY_LIMIT: Int = 100

    /** The first text of [part], as [snapshot] has it; null for [TesterPart.HISTORY], read apart ([history]). */
    fun part(
        part: TesterPart,
        snapshot: TesterSnapshot,
    ): String? =
        when (part) {
            TesterPart.APP -> app(snapshot)
            TesterPart.SETTINGS -> settings(snapshot.settings)
            TesterPart.NOW_PLAYING -> nowPlaying(snapshot)
            TesterPart.DIAGNOSTICS -> diagnostics(snapshot)
            TesterPart.HISTORY -> null
        }

    /** When it was reported, with the time zone, and the app's version and build. */
    fun app(snapshot: TesterSnapshot): String =
        buildString {
            appendLine("Reported: ${time(snapshot.at, snapshot.zone)}")
            val build = snapshot.build
            appendLine("Version: ${build.versionName} (${build.versionCode})")
            appendLine(
                if (build.testBuild) {
                    "Build: test (developer mode and the report button on by default)"
                } else {
                    "Build: not the test build"
                },
            )
        }.trimEnd()

    /**
     * Each setting on a line, by its name in the code, from [SETTING_LINES], which lists every one;
     * and the AI model in use. Settings hold no key: the keys are kept apart, and never read here.
     */
    fun settings(settings: Settings?): String {
        if (settings == null) return "Not read yet."
        return buildString {
            for ((name, value) in SETTING_LINES) appendLine("$name: ${value(settings)}")
            val provider = settings.aiProvider
            appendLine(
                if (provider == null) {
                    "In use: no AI service"
                } else {
                    "In use: ${providerName(provider)}, ${settings.model ?: NONE}, " +
                        "effort ${effortName(settings.effort)}"
                },
            )
        }.trimEnd()
    }

    /**
     * What is playing as the player reports it, the playback, and the pipeline's state: its flags,
     * and for its track the sources, the MusicBrainz ids, the evidence and the insight with its
     * citation check.
     */
    fun nowPlaying(snapshot: TesterSnapshot): String =
        buildString {
            appendLine("As the player reports it")
            val playing = snapshot.playing
            if (playing == null) {
                appendLine("Nothing playing in a player followed.")
            } else {
                appendPlaying(playing)
            }
            val playback = snapshot.playback
            appendLine(
                "Playback: ${playback.status}, position ${snapshot.position?.let(::length) ?: NOT_REPORTED}, " +
                    "speed ${playback.speed}",
            )
            appendLine()
            appendPipeline(snapshot.view, playing)
        }.trimEnd()

    /** What skip detection has learned, and the pipeline's events kept, oldest first, as the export has them. */
    fun diagnostics(snapshot: TesterSnapshot): String =
        buildString {
            appendLine("Skip detection")
            DiagnosticsText.appendLearned(this, snapshot.learned)
            appendLine()
            appendLine("Pipeline events, oldest first (${snapshot.events.size})")
            DiagnosticsText.appendEvents(this, snapshot.events)
        }.trimEnd()

    /**
     * The track changes recorded up to [upTo], newest first, as the export has them, at most
     * [limit]: read a page at a time from [changes] (`Diagnostics.changes`) only when the tester
     * turns the part on. A page that cannot be read ends it, saying so.
     */
    suspend fun history(
        changes: suspend (before: Long?) -> PageResult,
        upTo: Instant,
        limit: Int = HISTORY_LIMIT,
    ): String {
        val out = StringBuilder()
        out.appendLine("Track changes recorded on this phone, newest first, at most the latest $limit")
        // One more than the limit is read, to know whether older ones are left out.
        val kept = mutableListOf<TrackChangeRecord>()
        var before: Long? = null
        var failed = false
        do {
            val page = changes(before)
            if (page is PageResult.Read) {
                kept += page.page.changes.filter { it.changedAt <= upTo }
                before = page.page.next
            } else {
                failed = true
            }
        } while (!failed && before != null && kept.size <= limit)
        kept.take(limit).forEach { DiagnosticsText.appendChange(out, it) }
        when {
            failed -> out.appendLine("The history could not be read.")
            kept.size > limit -> out.appendLine("Older changes left out.")
            kept.isEmpty() -> out.appendLine(NONE)
        }
        return out.toString().trimEnd()
    }

    /**
     * The report as a mail to [Mail.SUPPORT]: the tester's [note], then each of [parts] the tester
     * kept, as they left it, under its label. A part left out is not in it at all.
     */
    fun mail(
        snapshot: TesterSnapshot,
        note: String,
        parts: List<Pair<TesterPart, String>>,
    ): Mail {
        val body =
            buildString {
                appendLine("A tester report from Sleevesight.")
                appendLine()
                appendLine("## Note")
                appendLine(note.trim().ifEmpty { NONE })
                for ((part, text) in parts) {
                    appendLine()
                    appendLine("## ${part.label}")
                    appendLine(text.trim().ifEmpty { NONE })
                }
            }
        return Mail(
            to = Mail.SUPPORT,
            subject =
                "Sleevesight tester report: ${snapshot.build.versionName}, ${time(snapshot.at, snapshot.zone)}",
            body = body,
        )
    }

    // The time to the second, with its offset and its zone: `2026-10-25 14:03:12 +02:00 (Europe/Warsaw)`.
    private fun time(
        at: Instant,
        zone: ZoneId,
    ): String = TIME.format(at.toJavaInstant().atZone(zone)) + " (${zone.id})"

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
}

private fun StringBuilder.appendPlaying(playing: NowPlaying) {
    appendLine("Player: ${playing.player}")
    appendLine("Title: ${playing.title}")
    appendLine("Artists: ${playing.artists}")
    appendLine("Album: ${playing.album ?: NOT_REPORTED}")
    appendLine("Length: ${playing.duration?.let(::length) ?: NOT_REPORTED}")
    appendLine("Media id: ${playing.mediaId ?: NOT_REPORTED}")
}

// The pipeline's view: what it offers, and its track, which may not be the one playing (the next
// track's run waits for its debounce).
private fun StringBuilder.appendPipeline(
    view: PipelineView,
    playing: NowPlaying?,
) {
    appendLine("Pipeline")
    appendLine("AI service ready: ${yes(view.aiReady)}")
    appendLine("Offline: ${yes(view.offline)}")
    appendLine("Needs a key: ${view.needsKey.joinToString { it.tag }.ifEmpty { NONE }}")
    appendLine("Write offered: ${yes(view.writeKey != null)}")
    appendLine("Rewrite offered: ${yes(view.rewrite)}")
    appendLine("Write again offered: ${yes(view.retry)}")
    appendLine("Stop offered: ${yes(view.stopCall != null)}")
    appendLine("Automatic writing paused: ${yes(view.lapsed)}")
    appendLine("Card paid for shown: ${yes(view.cardShown)}")
    view.rewriteFailed?.let { appendLine("Rewrite failed: $it") }
    val track = view.track
    if (track == null) {
        appendLine("Track: none yet")
        return
    }
    val its = track.nowPlaying
    val same = playing != null && its.title == playing.title && its.artists == playing.artists
    appendLine("Track: \"${its.title}\" by ${its.artists}${if (same) "" else ", not the track playing"}")
    appendLine()
    appendSources(track)
    appendLine()
    appendMusicBrainz(track)
    appendLine()
    appendEvidence(track)
    appendLine()
    appendInsight(track.insight)
}

private fun StringBuilder.appendSources(track: TrackState) {
    appendLine("Sources")
    if (track.sources.isEmpty()) appendLine(NONE)
    for ((source, state) in track.sources) appendLine("${source.tag}: ${sourceLine(state)}")
}

// What one source's lookup ended with, as the diagnostics' event lines name its outcome.
private fun sourceLine(state: SourceState): String =
    when (state) {
        SourceState.Pending -> "pending"
        is SourceState.Failed -> "failed, ${state.reason}"
        is SourceState.Done ->
            with(state.result) {
                "${outcomeKey(outcome)}, ${evidence.size} cited, ${unverified.size} unverified, " +
                    "${rejections.size} rejected, ${latency.inWholeMilliseconds} ms" +
                    if (state.cached) ", kept from before" else ""
            }
    }

private fun StringBuilder.appendEvidence(track: TrackState) {
    val lines = track.evidence?.lines
    if (lines == null) {
        appendLine("Evidence: not assembled yet")
        return
    }
    appendLine("Evidence (${lines.size} lines)")
    for (line in lines) {
        appendLine(InsightPrompt.evidenceLine(line))
        line.url?.let { appendLine("  $it") }
    }
}

private fun StringBuilder.appendInsight(insight: InsightState) {
    appendLine("Insight")
    when (insight) {
        is InsightState.Idle -> appendLine(if (insight.canWrite) "Not written; Write is offered" else "Not written")
        is InsightState.Writing -> {
            appendLine("Being written; so far:")
            appendLine(insight.partial.trim().ifEmpty { NONE })
        }
        is InsightState.Written -> appendWritten(insight)
        InsightState.NoEvidence -> appendLine("Nothing to write from")
        is InsightState.Failed -> appendLine("Failed: ${insight.reason}")
        InsightState.Stopped -> appendLine("Stopped")
    }
}

private fun StringBuilder.appendWritten(insight: InsightState.Written) {
    appendLine(
        "Written" + (if (insight.cached) ", kept from before" else "") +
            if (insight.complete) "" else ", cut off at the token limit",
    )
    insight.writtenBy?.let {
        appendLine(
            "Written with: prompt version ${it.promptVersion}, ${providerName(it.provider)}, ${it.model}, " +
                "effort ${effortName(it.effort)}",
        )
    }
    insight.usage?.let { appendLine("Tokens: ${it.inputTokens} in, ${it.outputTokens} out") }
    insight.held?.let { appendLine("Lines the prompt held: ${it.sorted().joinToString()}") }
    appendLine(insight.grounded.text.trim())
    insight.grounded.withdrawn?.let {
        appendLine("Taken out, the model's correction of its own reply (not shown, not checked):")
        appendLine(it.trim())
    }
    val counts = insight.grounded.counts
    appendLine(
        "Citation check: ok ${counts.ok}, model ${counts.model}, hedge ${counts.hedge}, " +
            "uncited ${counts.uncited}, unknown ${counts.unknown}",
    )
    for (sentence in insight.grounded.paragraphs.flatten()) {
        val cites = if (sentence.cites.isEmpty()) "" else " ${sentence.cites.joinToString(" ") { "[$it]" }}"
        appendLine("- ${sentence.status.name.lowercase()}$cites: ${sentence.text}")
    }
    if (citesLegacyModelId(insight.grounded.text)) appendLine(LEGACY_MODEL_NOTE)
}

/**
 * Every setting, by its name in `Settings`, with how its value reads. A test checks that each of
 * `Settings`' properties is here, so a setting added later is added here too.
 */
internal val SETTING_LINES: List<Pair<String, (Settings) -> String>> =
    listOf(
        "layout" to { it.layout.name },
        "aiProvider" to { s -> s.aiProvider?.let(::providerName) ?: NONE },
        "aiModels" to { s ->
            s.aiModels.entries
                .joinToString { "${providerName(it.key)} ${it.value}" }
                .ifEmpty { DEFAULTS }
        },
        "aiEfforts" to { s ->
            s.aiEfforts.entries
                .joinToString { "${providerName(it.key)} ${effortName(it.value)}" }
                .ifEmpty { DEFAULTS }
        },
        "followedPlayers" to { s ->
            s.followedPlayers.entries
                .joinToString { "${it.key} ${onOff(it.value)}" }
                .ifEmpty { NONE }
        },
        "onlyEnabledPlayersOnHome" to { onOff(it.onlyEnabledPlayersOnHome) },
        "enabledSources" to { s -> s.enabledSources.joinToString { it.name }.ifEmpty { NONE } },
        "webEngines" to { s -> s.webEngines.joinToString { it.name }.ifEmpty { NONE } },
        "labelSources" to { s -> s.labelSources.joinToString { it.name }.ifEmpty { NONE } },
        "showGenres" to { onOff(it.showGenres) },
        "writeInsights" to { onOff(it.writeInsights) },
        "writeAutomatically" to { it.writeAutomatically.name },
        "pauseAutoWriteAfter" to { s -> s.pauseAutoWriteAfter?.let { "$it track changes" } ?: "off" },
        "crossfade" to { s ->
            when (val crossfade = s.crossfade) {
                Crossfade.Autodetect -> "autodetect"
                is Crossfade.Fixed -> "fixed, ${crossfade.value}"
            }
        },
        "prepareNextTrack" to { it.prepareNextTrack.name },
        "lookUpInBackground" to { onOff(it.lookUpInBackground) },
        "artistFactsOncePerSession" to { onOff(it.artistFactsOncePerSession) },
        "readAloudMusic" to { it.readAloudMusic.name },
        "readTrackLineFirst" to { onOff(it.readTrackLineFirst) },
        "readNewInsightsAloud" to { onOff(it.readNewInsightsAloud) },
        "cacheLifetimes" to { s -> s.cacheLifetimes.entries.joinToString { "${it.key.tag} ${it.value}" } },
        "keepArtistResults" to { onOff(it.keepArtistResults) },
        "artistCacheLifetime" to { it.artistCacheLifetime.toString() },
        "cacheSizeLimit" to { "${it.cacheSizeLimit} bytes" },
        "historyLifetime" to { s -> s.historyLifetime?.toString() ?: "forever" },
        "sessionGap" to { it.sessionGap.toString() },
        "keepSourcesUnfolded" to { onOff(it.keepSourcesUnfolded) },
        "showPhoneName" to { onOff(it.showPhoneName) },
        "showDiagnostics" to { onOff(it.showDiagnostics) },
        "showGroundingDetails" to { onOff(it.showGroundingDetails) },
        "developerMode" to { onOff(it.developerMode) },
        "reportButton" to { it.reportButton.name },
        "linkStyle" to { it.linkStyle.name },
        "markDeveloperItems" to { onOff(it.markDeveloperItems) },
        "theme" to { it.theme.name },
        "themeButton" to { onOff(it.themeButton) },
        "font" to { it.font.name },
        "introAtStart" to { onOff(it.introAtStart) },
        "lyricsLinks" to { onOff(it.lyricsLinks) },
        "lyricsSearch" to { onOff(it.lyricsSearch) },
        "dismissed" to { s ->
            s.dismissed
                .sorted()
                .joinToString { it.name }
                .ifEmpty { NONE }
        },
    )

private fun onOff(on: Boolean) = if (on) "on" else "off"

private fun yes(yes: Boolean) = if (yes) "yes" else "no"

private const val NONE = "none"
private const val DEFAULTS = "the defaults"
private const val NOT_REPORTED = "not reported"
