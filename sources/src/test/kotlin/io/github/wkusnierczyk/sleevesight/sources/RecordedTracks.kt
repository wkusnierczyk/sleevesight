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
 * first occurrences (duplicates hit the cache, so they add no requests).
 */
object RecordedTracks {
    val positions: List<Int> =
        RegressionCase.entries
            .flatMap { it.tracks }
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
