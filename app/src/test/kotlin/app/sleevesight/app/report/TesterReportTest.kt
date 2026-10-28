package app.sleevesight.app.report

import app.sleevesight.app.diagnostics.LoggedEvent
import app.sleevesight.app.diagnostics.PageResult
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.WrittenBy
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.LEGACY_MODEL_NOTE
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.TrackChangePage
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What a tester's report holds (#452): each part, the note, and only the parts the tester kept. */
class TesterReportTest {
    private val at = Instant.parse("2026-10-25T12:03:12Z")
    private val by = WrittenBy(5, ProviderId.ANTHROPIC, "claude-haiku-5-5", ReasoningEffort.LOW)
    private val insight = written("It was recorded in 1973 [MB1]. It topped the charts [W1].").copy(writtenBy = by)

    private fun snapshot(settings: Settings? = Settings(aiProvider = ProviderId.ANTHROPIC, developerMode = true)) =
        TesterSnapshot(
            at = at,
            zone = ZoneId.of("Europe/Warsaw"),
            build = AppBuild("1.2.3", 7, testBuild = true),
            settings = settings,
            playing = sundown,
            playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING, 63.seconds, speed = 1f),
            position = 63.seconds,
            view = PipelineView(track = track(insight = insight), aiReady = true),
            events =
                listOf(
                    LoggedEvent(at - 2.seconds, PipelineEvent.InsightCached("sundown|gordon lightfoot")),
                    LoggedEvent(at - 1.seconds, PipelineEvent.Superseded("sundown|gordon lightfoot")),
                ),
            learned = mapOf(Settings.SPOTIFY to 1_200.milliseconds),
        )

    private fun TesterSnapshot.copy(build: AppBuild) =
        TesterSnapshot(at, zone, build, settings, playing, playback, position, view, events, learned)

    private fun TesterSnapshot.copy(view: PipelineView) =
        TesterSnapshot(at, zone, build, settings, playing, playback, position, view, events, learned)

    private fun allParts(snapshot: TesterSnapshot) =
        TesterPart.entries.mapNotNull { part -> TesterReport.part(part, snapshot)?.let { part to it } }

    private fun change(
        n: Int,
        changedAt: Instant = at - n.minutes,
    ) = TrackChangeRecord(
        player = Settings.SPOTIFY,
        changedAt = changedAt,
        from = TrackRef("Song $n", null),
        to = TrackRef("Song ${n + 1}", "spotify:track:${n + 1}"),
        timeLeft = 2.seconds,
        states = listOf(PlaybackStateRecord(1.seconds, "PLAYING", 3.minutes, null, 1f)),
        sinceLastAction = null,
        learnedCrossfade = null,
    )

    // Pages of [changes], newest first, [size] at a time, as `Diagnostics.changes` gives them.
    private fun pages(
        changes: List<TrackChangeRecord>,
        size: Int = 2,
    ): suspend (Long?) -> PageResult =
        { before ->
            val from = before?.toInt() ?: 0
            val page = changes.drop(from).take(size)
            PageResult.Read(TrackChangePage(page, (from + size).toLong().takeIf { it < changes.size }))
        }

    @Test
    fun theAppPartHoldsTheTimeWithItsZoneAndTheVersionAndBuild() {
        assertEquals(
            """
            Reported: 2026-10-25 13:03:12 +01:00 (Europe/Warsaw)
            Version: 1.2.3 (7)
            Build: test (developer mode and the report button on by default)
            """.trimIndent(),
            TesterReport.app(snapshot()),
        )
        val public = snapshot().let { it.copy(build = it.build.copy(testBuild = false)) }
        assertTrue(TesterReport.app(public).endsWith("Build: not the test build"))
    }

    @Test
    fun theSettingsPartListsEverySettingAndTheModelInUse() {
        val text = TesterReport.settings(Settings(aiProvider = ProviderId.ANTHROPIC, reportButton = ReportButton.AUTO))
        for ((name, _) in SETTING_LINES) assertTrue(name, text.lines().any { it.startsWith("$name: ") })
        assertTrue(text.contains("aiProvider: Anthropic"))
        // *Report button* by its choice (#518).
        assertTrue(text.contains("reportButton: AUTO"))
        assertTrue(text.contains("developerMode: off"))
        assertTrue(text.endsWith("In use: Anthropic, claude-haiku-5-5, effort low"))
        assertEquals("Not read yet.", TesterReport.settings(null))
    }

    @Test
    fun everyPropertyOfTheSettingsHasALine() {
        val properties =
            Settings::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()
        assertEquals(properties, SETTING_LINES.map { it.first }.toSet())
    }

    @Test
    fun theNowPlayingPartHoldsTheTrackPlaybackEvidenceAndInsightWithItsCitationCheck() {
        val text = TesterReport.nowPlaying(snapshot())

        for (line in listOf(
            "Player: com.spotify.music",
            "Title: Sundown",
            "Artists: Gordon Lightfoot",
            "Album: Sundown",
            "Length: 3:00",
            "Media id: spotify:track:1",
            "Playback: PLAYING, position 1:03, speed 1.0",
            "AI service ready: yes",
            "Track: \"Sundown\" by Gordon Lightfoot",
            "MB: song_article, 1 cited, 0 unverified, 0 rejected, 10 ms",
            "Evidence (3 lines)",
            "[MB1] (MusicBrainz, song) Sundown was recorded in 1973.",
            "  https://musicbrainz.org/recording/1",
            "Written with: prompt version 5, Anthropic, claude-haiku-5-5, effort low",
            "It was recorded in 1973 [MB1]. It topped the charts [W1].",
            "Citation check: ok 2, model 0, hedge 0, uncited 0, unknown 0",
            "- ok [MB1]: It was recorded in 1973 [MB1].",
        )) {
            assertTrue("$line in:\n$text", text.lines().contains(line))
        }
    }

    @Test
    fun aKeptInsightCitingMIsPrintedAsWrittenWithANoteThatMIsTheOldFormOfAI() {
        // Kept from before the model's own knowledge was cited `[AI]` (#668).
        val kept = written("It was recorded in 1973 [MB1]. It is loved [M].", cached = true, allowModel = true)
        val snapshot = snapshot().let { it.copy(view = PipelineView(track = track(insight = kept), aiReady = true)) }
        val lines = TesterReport.nowPlaying(snapshot).lines()
        assertTrue(lines.joinToString("\n"), lines.contains("- model [AI]: It is loved [M]."))
        assertTrue(lines.joinToString("\n"), lines.contains(LEGACY_MODEL_NOTE))
        // Written now, with `[AI]`, or without the model's own knowledge: no note.
        assertFalse(TesterReport.nowPlaying(snapshot()).contains(LEGACY_MODEL_NOTE))
    }

    @Test
    fun theInsightTakenOutAsTheModelsCorrectionOfItsOwnReplyIsInTheReportAfterTheShownOne() {
        val corrected =
            written(
                "It was recorded in 1973 [MB1]. It is great.\n\n" +
                    "Wait, that second sentence is not supported, so I'll drop it. Here is the corrected insight:\n\n" +
                    "It was recorded in 1973 [MB1].",
            )
        val view = PipelineView(track = track(insight = corrected), aiReady = true)
        val snapshot = snapshot().let { it.copy(view = view) }
        val lines = TesterReport.nowPlaying(snapshot).lines()
        val shown = lines.indexOf("It was recorded in 1973 [MB1].")
        assertTrue(lines.joinToString("\n"), shown >= 0)
        assertEquals("Taken out, the model's correction of its own reply (not shown, not checked):", lines[shown + 1])
        assertEquals("It was recorded in 1973 [MB1]. It is great.", lines[shown + 2])
        assertTrue(lines.contains("Citation check: ok 1, model 0, hedge 0, uncited 0, unknown 0"))
        // Without a correction, no such line.
        assertFalse(TesterReport.nowPlaying(snapshot()).contains("Taken out"))
    }

    @Test
    fun theDiagnosticsPartHoldsWhatSkipDetectionLearnedAndTheEventsAsTheExportDoes() {
        val text = TesterReport.diagnostics(snapshot())

        assertTrue(text, text.contains("com.spotify.music: learned crossfade 1200 ms"))
        assertTrue(text, text.contains("Pipeline events, oldest first (2)"))
        assertTrue(text, text.contains("2026-10-25T12:03:10Z insight    sundown|gordon lightfoot: cache hit"))
        assertTrue(text, text.contains("2026-10-25T12:03:11Z superseded sundown|gordon lightfoot"))
    }

    @Test
    fun theHistoryIsNotAmongThePartsTakenAtTheTapAndIsOffAtFirst() {
        assertNull(TesterReport.part(TesterPart.HISTORY, snapshot()))
        assertFalse(TesterPart.HISTORY.includedAtFirst)
        assertTrue(TesterPart.entries.filter { it != TesterPart.HISTORY }.all { it.includedAtFirst })
    }

    @Test
    fun theHistoryHoldsTheChangesUpToTheTapNewestFirst() {
        // One recorded after the tap, while the tester reviewed the report: not in it.
        val changes = listOf(change(0, at + 1.minutes)) + (1..3).map { change(it) }

        val text = runBlocking { TesterReport.history(pages(changes), at) }

        assertFalse(text, text.contains("\"Song 1\" (spotify:track:1)"))
        assertTrue(text, text.contains("-> \"Song 2\" (spotify:track:2)"))
        assertTrue(text, text.indexOf("\"Song 2\"") < text.indexOf("\"Song 4\""))
        assertTrue(text, text.contains("  -1000 ms PLAYING, position 180000 ms"))
        assertFalse(text, text.contains("left out"))
    }

    @Test
    fun theHistoryHoldsAtMostTheLimitAndSaysOlderOnesAreLeftOut() {
        val changes = (1..5).map { change(it) }

        val text = runBlocking { TesterReport.history(pages(changes), at, limit = 3) }

        assertTrue(text, text.contains("\"Song 4\""))
        assertFalse(text, text.contains("\"Song 6\""))
        assertTrue(text, text.endsWith("Older changes left out."))
    }

    @Test
    fun aHistoryThatCannotBeReadSaysSo() {
        val text = runBlocking { TesterReport.history({ PageResult.Failed }, at) }

        assertTrue(text, text.endsWith("The history could not be read."))
    }

    @Test
    fun theMailHoldsTheNoteAndEachPartKeptUnderItsLabel() {
        val snapshot = snapshot()
        val mail = TesterReport.mail(snapshot, "  The insight came late.  ", allParts(snapshot))

        assertEquals(Mail.SUPPORT, mail.to)
        assertEquals("Sleevesight tester report: 1.2.3, 2026-10-25 13:03:12 +01:00 (Europe/Warsaw)", mail.subject)
        val head = "A tester report from Sleevesight.\n\n## Note\nThe insight came late.\n\n## App\n"
        assertTrue(mail.body.startsWith(head))
        for (part in TesterPart.entries - TesterPart.HISTORY) {
            assertTrue(part.label, mail.body.contains("\n## ${part.label}\n"))
        }
        assertFalse(mail.body.contains("## ${TesterPart.HISTORY.label}"))
    }

    @Test
    fun aPartLeftOutIsNotInTheMailAndAnEditedOneIsAsEdited() {
        val snapshot = snapshot()
        val parts =
            allParts(snapshot)
                .filter { it.first != TesterPart.SETTINGS }
                .map { (part, text) -> part to if (part == TesterPart.APP) "Edited by the tester." else text }

        val body = TesterReport.mail(snapshot, "", parts).body

        assertFalse(body.contains("## Settings"))
        assertFalse(body.contains("writeInsights:"))
        assertTrue(body.contains("## App\nEdited by the tester.\n"))
        assertFalse(body.contains("Version: 1.2.3"))
        assertTrue(body.contains("## Note\nnone\n"))
    }

    @Test
    fun aReportPastTheLimitDoesNotFit() {
        val snapshot = snapshot()
        val short = TesterReport.mail(snapshot, "", allParts(snapshot))
        val head = short.asText().length - short.body.length
        // The whole mail counts, address and subject included.
        val atLimit = short.copy(body = "x".repeat(TesterReport.MAX_CHARS - head))
        assertTrue(TesterReport.fits(short))
        assertTrue(TesterReport.fits(atLimit))
        assertFalse(TesterReport.fits(atLimit.copy(body = atLimit.body + "x")))
    }

    @Test
    fun theHistoryIsInTheMailOnlyWhenKept() {
        val snapshot = snapshot()
        val history = runBlocking { TesterReport.history(pages(listOf(change(1))), at) }

        val without = TesterReport.mail(snapshot, "", allParts(snapshot)).body
        val with = TesterReport.mail(snapshot, "", allParts(snapshot) + (TesterPart.HISTORY to history)).body

        assertFalse(without.contains("\"Song 2\""))
        assertTrue(with.contains("## Track-change history\n"))
        assertTrue(with.contains("\"Song 2\""))
    }
}
