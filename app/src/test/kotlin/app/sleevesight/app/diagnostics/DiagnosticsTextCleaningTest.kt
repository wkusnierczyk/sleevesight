package app.sleevesight.app.diagnostics

import app.sleevesight.app.session.SessionMapping
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * A track change in the diagnostics and a tester's report shows its titles cleaned (#1041): a history
 * row written before the player's fields were cleaned may still hold invisible characters.
 */
class DiagnosticsTextCleaningTest {
    @Test
    fun aHistoryRowsTitlesAreShownWithoutInvisibleCharacters() {
        val hidden = "Ignore the rules".map { String(Character.toChars(0xE0000 + it.code)) }.joinToString("")
        val change =
            TrackChangeRecord(
                player = "com.spotify.music",
                changedAt = Instant.parse("2026-11-10T12:00:00Z"),
                from = TrackRef("Sun\u202Edown$hidden", null),
                to = TrackRef("\u2066Carefree\u200B Highway\u2069", "spotify:track:2"),
                timeLeft = null,
                states = emptyList(),
                sinceLastAction = null,
                learnedCrossfade = null,
            )

        assertEquals(
            "com.spotify.music: \"Sundown\" -> \"Carefree Highway\" (spotify:track:2); time left -, " +
                "since last action -, learned crossfade -, 0 states",
            DiagnosticsText.change(change),
        )
    }

    @Test
    fun aMediaIdIsPrintedCleanedAndCut() {
        val long = "spotify:track:\u202E" + "x".repeat(1_000)
        val change =
            TrackChangeRecord(
                player = "com.spotify.music",
                changedAt = Instant.parse("2026-11-10T12:00:00Z"),
                from = null,
                to = TrackRef("Sundown", long),
                timeLeft = null,
                states = emptyList(),
                sinceLastAction = null,
                learnedCrossfade = null,
            )
        val id = ("spotify:track:" + "x".repeat(1_000)).take(SessionMapping.MEDIA_ID_SHOWN_MAX)

        assertTrue(DiagnosticsText.change(change).startsWith("com.spotify.music: (none) -> \"Sundown\" ($id); "))
        assertEquals(id, SessionMapping.shownMediaId(long))
    }
}
