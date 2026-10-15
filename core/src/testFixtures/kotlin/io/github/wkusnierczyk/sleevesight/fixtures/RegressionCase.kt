// Playlist positions are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package io.github.wkusnierczyk.sleevesight.fixtures

/**
 * The design's regression cases (`design/design.md`, *Test fixtures*), by playlist position in
 * [BelowSoundtrack]. A test that covers a case names it, so each case can be traced to its tests.
 */
enum class RegressionCase(
    val tracks: List<Int>,
    val description: String,
) {
    RICH_DATA(listOf(1), "Sundown, Gordon Lightfoot: rich data from every source"),
    VERSION_SUFFIX(listOf(7), "Fisherman's Blues - 2006 Remaster: version suffix"),
    DUPLICATES(listOf(8, 22, 4, 24), "8 and 22, 4 and 24: duplicates in the playlist, should hit the cache"),
    SAME_NAME_ARTIST(listOf(12, 20), "Reem: a Wikipedia article about a different Reem, must be rejected"),
    MULTIPLE_ARTISTS(listOf(14), "Up Down, Peleboy, Tony K: multiple credited artists"),
    THREE_ARTISTS(listOf(37), "The Ode To Newfoundland: three credited artists"),
    COVERS(listOf(45, 48, 76), "Covers: Wikipedia only describes the original song"),

    // Track 61 is also a cover of Taylor Swift's "willow" (album "Taylor Swift Covers (single)"), which
    // the identity check cannot confirm; the album title is a signal for the gate (#46).
    WRONG_LASTFM_ARTIST(listOf(61), "willow, Jessiah: the Last.fm name resolves to an unrelated band"),
    FEATURED_ARTIST(listOf(63), "Baby, Please Don't Go (feat. Van Morrison): featured artist in the title"),
    COMPILATION_RELEASE_YEAR(listOf(13, 36), "New York Groove, Snowbird: the release year is a compilation's"),
    NOTHING_DOCUMENTED(listOf(23, 30, 77), "Almost nothing documented anywhere; the card must say so"),
}
