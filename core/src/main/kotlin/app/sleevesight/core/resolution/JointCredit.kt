package app.sleevesight.core.resolution

/**
 * Every run of two or more consecutive [credits] that, written as MusicBrainz writes the credit (the
 * names with the join phrases between them), is [artist]'s name by [NameMatcher.sameName]; empty when
 * none is. A joint credit (#661): Spotify's "Derya Yıldırım & Grup Şimşek" is MusicBrainz's "Derya
 * Yıldırım" and "Grup Şimşek" joined by " & ", and "A & B" is also the first two of "A and B feat. C".
 * Every run is returned, so a name two different pairs write on one recording is seen twice and
 * collides, as two same-name credits do.
 *
 * Each credit in a run is written by its credited name or by MusicBrainz's name for the artist, in
 * every mix of the two, at most [MAX_WRITINGS] ways per run. A run stops growing once every way of
 * writing it is longer, as a key, than the artist's name, since a longer run only adds to it.
 */
internal fun NameMatcher.jointCredits(
    credits: List<MbCredit>,
    artist: String,
): Set<List<MbCredit>> {
    val wanted = nameKey(artist)
    val runs = mutableSetOf<List<MbCredit>>()
    if (wanted.isEmpty()) return runs
    for (start in credits.indices) {
        var written = writings(credits[start])
        for (end in start + 1 until credits.size) {
            val join = credits[end - 1].joinPhrase
            written =
                written
                    .flatMap { before -> writings(credits[end]).map { before + join + it } }
                    .distinct()
                    .take(MAX_WRITINGS)
            val keys = written.map(::nameKey)
            if (wanted in keys) runs += credits.subList(start, end + 1).toList()
            if (keys.all { it.length > wanted.length }) break
        }
    }
    return runs
}

/** The ways of writing a run, at most, so a run of credits with many aliases stays cheap. */
private const val MAX_WRITINGS = 16

// A credit as credited and as MusicBrainz names the artist.
private fun writings(credit: MbCredit): List<String> = listOf(credit.name, credit.artistName).distinct()
