// Playlist positions are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package io.github.wkusnierczyk.sleevesight.sources

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.normalizer.Normalizer
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import io.github.wkusnierczyk.sleevesight.core.resolution.NameMatcher
import io.github.wkusnierczyk.sleevesight.fixtures.BelowSoundtrack
import io.github.wkusnierczyk.sleevesight.fixtures.RegressionCase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The fixture tracks whose responses are recorded in `fixtures/http/`: the regression cases'
 * first occurrences (duplicates hit the cache, so they add no requests), and [GATE_FINDINGS].
 */
object RecordedTracks {
    /**
     * Tracks outside the regression cases on which the Phase 0.5 gate run (#46) found resolution
     * errors, recorded for their tests: 55, Chuck Berry's own "Roll Over Beethoven" (#78, #76);
     * 62, "Misirlou", credited on MusicBrainz to "Dick Dale and His Del-Tones" (#77); 50, "When a
     * Law's Been Broken", credited on MusicBrainz to "Mr. A Balladeer" (#79).
     */
    val GATE_FINDINGS: List<Int> = listOf(50, 55, 62)

    val positions: List<Int> =
        (RegressionCase.entries.flatMap { it.tracks } + GATE_FINDINGS)
            .map { BelowSoundtrack.firstOccurrence(it) }
            .distinct()
            .sorted()

    private val normalizer = Normalizer(JvmAccentFolder)

    val names: NameMatcher = NameMatcher(JvmAccentFolder)

    /** Entry [n] of the playlist, normalized. */
    fun track(n: Int): NormalizedTrack {
        val entry = BelowSoundtrack.track(n)
        return normalizer.normalize(entry.title, entry.artist)
    }

    /** The length the player reports for entry [n] (`3:33`). */
    fun duration(n: Int): Duration {
        val (minutes, seconds) =
            BelowSoundtrack
                .track(n)
                .duration
                .split(':')
                .map { it.toInt() }
        return (minutes * 60 + seconds).seconds
    }
}
